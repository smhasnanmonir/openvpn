package ovpn

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/smhasnanmonir/openvpn/desktop/internal/model"
	"github.com/smhasnanmonir/openvpn/desktop/internal/security"
	"github.com/smhasnanmonir/openvpn/desktop/internal/settings"
)

// EventKind tells the app what changed.
type EventKind int

const (
	EventState EventKind = iota
	EventLog
	EventStats
	EventDetails
	EventExited
)

// Event is emitted by a running Session.
type Event struct {
	Kind    EventKind
	State   model.VPNState
	Error   string
	Log     model.LogEntry
	Stats   model.TrafficStats
	Details model.ConnectionDetails
}

// Spec describes one connection attempt.
type Spec struct {
	Helper            HelperInfo
	Config            string
	Credentials       model.Credentials
	Settings          settings.Settings
	FallbackDNS       []string
	DataDir           string
	Verb              int
	StaticChallenge   bool
	PerAppMode        string
	PerAppExecutables []string
}

// Session owns one OpenVPN process and its management connection.
type Session struct {
	spec        Spec
	cmd         *exec.Cmd
	mgmt        *ManagementClient
	events      chan Event
	done        chan struct{}
	tempDir     string
	stopped     chan struct{}
	stopOnce    sync.Once
	finish      sync.Once
	mu          sync.Mutex
	exited      bool
	logMu       sync.Mutex
	recent      []string
	serviceConn net.Conn
	serviceEnc  *json.Encoder
	serviceMu   sync.Mutex
}

// Start writes the session files, launches the privileged helper (which
// replaces itself with openvpn), and connects to the management interface.
func Start(ctx context.Context, spec Spec) (*Session, error) {
	if strings.TrimSpace(spec.Config) == "" {
		return nil, errors.New("empty OpenVPN configuration")
	}
	if spec.Helper.ServiceAddr != "" {
		return startService(ctx, spec)
	}
	if spec.DataDir == "" {
		return nil, errors.New("no data directory for session files")
	}
	if spec.Verb <= 0 {
		spec.Verb = 3
	}
	sessionsDir := filepath.Join(spec.DataDir, "sessions")
	if err := os.MkdirAll(sessionsDir, 0o700); err != nil {
		return nil, err
	}
	tempDir, err := os.MkdirTemp(sessionsDir, "s-")
	if err != nil {
		return nil, err
	}
	_ = os.Chmod(tempDir, 0o700)

	s := &Session{
		spec:    spec,
		events:  make(chan Event, 512),
		tempDir: tempDir,
		stopped: make(chan struct{}),
	}

	configPath := filepath.Join(tempDir, "profile.ovpn")
	if err := writePrivate(configPath, spec.Config); err != nil {
		s.cleanup()
		return nil, err
	}

	password := randomHex(24)
	mgmtPassPath := filepath.Join(tempDir, "management.pass")
	if err := writePrivate(mgmtPassPath, password+"\n"); err != nil {
		s.cleanup()
		return nil, err
	}

	port, err := freePort()
	if err != nil {
		s.cleanup()
		return nil, err
	}
	mgmtAddr := fmt.Sprintf("127.0.0.1:%d", port)

	args := []string{
		"run",
		"--config", configPath,
		"--management", mgmtAddr,
		"--management-password-file", mgmtPassPath,
		"--require-dns", boolArg(spec.Settings.RequireTunnelDNS),
		"--kill-switch", boolArg(spec.Settings.KillSwitch),
		"--verb", strconv.Itoa(spec.Verb),
	}
	if len(spec.FallbackDNS) > 0 {
		args = append(args, "--fallback-dns", strings.Join(spec.FallbackDNS, ","))
	}
	if spec.PerAppMode != "" && spec.PerAppMode != "off" {
		args = append(args, "--per-app-mode", spec.PerAppMode)
		for _, exe := range spec.PerAppExecutables {
			args = append(args, "--per-app-exec", exe)
		}
	}

	if spec.Credentials.Username != "" || spec.Credentials.Password != "" || spec.Credentials.Response != "" {
		credsPath := filepath.Join(tempDir, "credentials")
		if err := writePrivate(credsPath, credentialsFile(spec.Credentials, spec.StaticChallenge)); err != nil {
			s.cleanup()
			return nil, err
		}
		args = append(args, "--credentials", credsPath)
	}
	if spec.Credentials.PrivateKeyPassword != "" {
		keyPassPath := filepath.Join(tempDir, "key.pass")
		if err := writePrivate(keyPassPath, spec.Credentials.PrivateKeyPassword+"\n"); err != nil {
			s.cleanup()
			return nil, err
		}
		args = append(args, "--key-password-file", keyPassPath)
	}

	cmd, err := spec.Helper.Command(args)
	if err != nil {
		s.cleanup()
		return nil, err
	}
	s.cmd = cmd

	stdout, err := cmd.StdoutPipe()
	if err != nil {
		s.cleanup()
		return nil, err
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		s.cleanup()
		return nil, err
	}
	configureProcessGroup(cmd)
	if err := cmd.Start(); err != nil {
		s.cleanup()
		return nil, fmt.Errorf("could not start OpenVPN: %w", err)
	}

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		s.readProcessOutput(stdout)
	}()
	go func() {
		defer wg.Done()
		s.readProcessOutput(stderr)
	}()

	mgmtCtx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	mgmt, err := DialManagement(mgmtCtx, mgmtAddr, password, 20*time.Second)
	if err != nil {
		killProcessGroup(cmd)
		s.cleanup()
		if detail := s.recentOutput(); detail != "" {
			return nil, fmt.Errorf("%w\nOpenVPN output:\n%s", err, detail)
		}
		return nil, err
	}
	s.mgmt = mgmt

	go s.readManagement(mgmt)

	go func() {
		waitErr := cmd.Wait()
		wg.Wait()
		s.mu.Lock()
		s.exited = true
		s.mu.Unlock()
		if mgmt != nil {
			mgmt.Close()
		}
		s.cleanup()
		errText := ""
		if waitErr != nil {
			errText = waitErr.Error()
		}
		select {
		case <-s.stopped:
			s.emit(Event{Kind: EventExited})
		default:
			s.emit(Event{Kind: EventExited, Error: errText})
		}
		s.finish.Do(func() {
			close(s.events)
			close(s.done)
		})
	}()

	return s, nil
}

// Events is closed when the process exits and all events were delivered.
func (s *Session) Events() <-chan Event { return s.events }

// Done is closed after Events, once the session files and child are cleaned up.
func (s *Session) Done() <-chan struct{} { return s.done }

// Stop asks OpenVPN to disconnect, then kills it if it does not exit. It is
// safe to call more than once.
func (s *Session) Stop() {
	s.stopOnce.Do(func() {
		close(s.stopped)
	})
	if s.serviceConn != nil {
		if s.mgmt != nil {
			_ = s.mgmt.Signal("SIGTERM")
		}
		s.sendServiceStop()
		_ = s.serviceConn.Close()
		return
	}
	if s.mgmt != nil {
		_ = s.mgmt.Signal("SIGTERM")
	}
	s.mu.Lock()
	exited := s.exited
	s.mu.Unlock()
	if exited || s.cmd == nil || s.cmd.Process == nil {
		return
	}
	go func() {
		deadline := time.Now().Add(10 * time.Second)
		for time.Now().Before(deadline) {
			s.mu.Lock()
			done := s.exited
			s.mu.Unlock()
			if done {
				return
			}
			time.Sleep(100 * time.Millisecond)
		}
		killProcessGroup(s.cmd)
	}()
}

func (s *Session) recentOutput() string {
	s.logMu.Lock()
	defer s.logMu.Unlock()
	return strings.Join(s.recent, "\n")
}

func (s *Session) emit(ev Event) {
	select {
	case s.events <- ev:
	case <-s.stopped:
		// Keep the final EventExited even after Stop: the app needs to learn
		// that the process is gone.
		if ev.Kind == EventExited {
			select {
			case s.events <- ev:
			case <-time.After(2 * time.Second):
			}
		}
	}
}

func (s *Session) readProcessOutput(r interface{ Read([]byte) (int, error) }) {
	scanner := bufio.NewScanner(r)
	scanner.Buffer(make([]byte, 64*1024), 1024*1024)
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			continue
		}
		if details, ok := parseHelperDetails(line); ok {
			s.emit(Event{Kind: EventDetails, Details: details})
			continue
		}
		level := classifyLog(line)
		clean := security.Sanitize(line)
		s.logMu.Lock()
		s.recent = append(s.recent, clean)
		if len(s.recent) > 40 {
			s.recent = s.recent[len(s.recent)-40:]
		}
		s.logMu.Unlock()
		s.emit(Event{Kind: EventLog, Log: model.LogEntry{
			Time:    time.Now(),
			Level:   level,
			Message: clean,
		}})
		if strings.Contains(line, "AUTH_FAILED") {
			s.emit(Event{Kind: EventState, State: model.StateAuthFailed, Error: "authentication failed"})
		}
	}
}

func (s *Session) readManagement(mgmt *ManagementClient) {
	for ev := range mgmt.Events() {
		switch ev.Kind {
		case "state":
			if ev.State == "" {
				continue
			}
			state, ok := stateFromManagement(ev.State, ev.Description)
			if !ok {
				continue
			}
			var errText string
			if state == model.StateAuthFailed {
				errText = "authentication failed"
			}
			s.emit(Event{Kind: EventState, State: state, Error: errText})
			if state == model.StateConnected {
				d := model.ConnectionDetails{
					ServerIP:   ev.RemoteIP,
					ServerPort: ev.RemotePort,
					TunName:    ev.TunName,
				}
				if isIPv4(ev.LocalIP) {
					d.VPNIP4 = ev.LocalIP
				} else if isIPv6(ev.LocalIP) {
					d.VPNIP6 = ev.LocalIP
				}
				s.emit(Event{Kind: EventDetails, Details: d})
			}
		case "bytecount":
			s.emit(Event{Kind: EventStats, Stats: model.TrafficStats{
				BytesIn:  ev.BytesIn,
				BytesOut: ev.BytesOut,
			}})
		case "log":
			s.emit(Event{Kind: EventLog, Log: model.LogEntry{
				Time:    time.Now(),
				Level:   ev.LogLevel,
				Message: security.Sanitize(ev.Message),
			}})
		case "raw":
			s.emit(Event{Kind: EventLog, Log: model.LogEntry{
				Time:    time.Now(),
				Level:   model.LogInfo,
				Message: "management: " + security.Sanitize(ev.Message),
			}})
		}
	}
}

func stateFromManagement(state, description string) (model.VPNState, bool) {
	up := strings.ToUpper(state)
	desc := strings.ToUpper(description)
	if strings.Contains(desc, "AUTH_FAILED") {
		return model.StateAuthFailed, true
	}
	switch up {
	case "CONNECTED":
		return model.StateConnected, true
	case "RECONNECTING":
		return model.StateReconnecting, true
	case "EXITING":
		return model.StateDisconnecting, true
	case "CONNECTING", "RESOLVE", "TCP_CONNECT", "WAIT", "AUTH", "GET_CONFIG", "ASSIGN_IP", "ADD_ROUTES":
		return model.StateConnecting, true
	default:
		return "", false
	}
}

func classifyLog(line string) model.LogLevel {
	lower := strings.ToLower(line)
	switch {
	case strings.Contains(lower, "auth_failed"),
		strings.Contains(lower, "error"),
		strings.Contains(lower, "fatal"),
		strings.Contains(lower, "cannot open tun"),
		strings.Contains(lower, "tls error"),
		strings.Contains(lower, "certificate verify failed"):
		return model.LogError
	case strings.Contains(lower, "warning"),
		strings.Contains(lower, "warn"):
		return model.LogWarn
	default:
		return model.LogInfo
	}
}

// parseHelperDetails reads the DNS status line printed by the helper's route-up
// script, for example:
//
//	[openvpn-desktop] dns=1.1.1.1,8.8.8.8 fallback=0
func parseHelperDetails(line string) (model.ConnectionDetails, bool) {
	const prefix = "[openvpn-desktop] dns="
	if !strings.HasPrefix(line, prefix) {
		return model.ConnectionDetails{}, false
	}
	rest := strings.TrimPrefix(line, prefix)
	parts := strings.Fields(rest)
	d := model.ConnectionDetails{}
	if len(parts) > 0 {
		d.DNSServers = strings.TrimSuffix(parts[0], ",")
	}
	for _, p := range parts {
		if p == "fallback=1" {
			d.DNSFallback = true
		}
	}
	return d, true
}

func writePrivate(path, content string) error {
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		return err
	}
	return os.Chmod(path, 0o600)
}

func credentialsFile(c model.Credentials, staticChallenge bool) string {
	pass := c.Password
	if staticChallenge && c.Response != "" {
		// OpenVPN's SCRV1 format: SCRV1:<base64(password)>:<base64(response)>.
		pass = "SCRV1:" + base64.StdEncoding.EncodeToString([]byte(c.Password)) +
			":" + base64.StdEncoding.EncodeToString([]byte(c.Response))
	}
	return c.Username + "\n" + pass + "\n"
}

func freePort() (int, error) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port, nil
}

func randomHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return strconv.FormatInt(time.Now().UnixNano(), 16)
	}
	return hex.EncodeToString(b)
}

func boolArg(v bool) string {
	if v {
		return "1"
	}
	return "0"
}

func isIPv4(s string) bool {
	ip := net.ParseIP(s)
	return ip != nil && ip.To4() != nil
}

func isIPv6(s string) bool {
	ip := net.ParseIP(s)
	return ip != nil && ip.To4() == nil
}

func (s *Session) cleanup() {
	if s.tempDir == "" {
		return
	}
	// Best-effort overwrite of the credential files before unlinking them.
	for _, name := range []string{"credentials", "key.pass", "management.pass"} {
		p := filepath.Join(s.tempDir, name)
		if st, err := os.Stat(p); err == nil && st.Mode().IsRegular() {
			if f, err := os.OpenFile(p, os.O_WRONLY, 0o600); err == nil {
				zeros := make([]byte, st.Size())
				_, _ = f.Write(zeros)
				_ = f.Sync()
				f.Close()
			}
		}
	}
	_ = os.RemoveAll(s.tempDir)
	s.tempDir = ""
}
