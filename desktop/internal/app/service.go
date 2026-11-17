// Package app is the desktop client's business logic. It owns the vault, the
// active OpenVPN session, the bounded retry supervisor, the log ring buffer and
// the public-IP check. The MyGo view reads thread-safe snapshots and calls the
// methods here; it never touches OpenVPN directly.
package app

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/netip"
	"os"
	"runtime"
	"strings"
	"sync"
	"time"

	"github.com/smhasnanmonir/openvpn/desktop/internal/captiveportal"
	"github.com/smhasnanmonir/openvpn/desktop/internal/dnspolicy"
	"github.com/smhasnanmonir/openvpn/desktop/internal/model"
	"github.com/smhasnanmonir/openvpn/desktop/internal/ovpn"
	"github.com/smhasnanmonir/openvpn/desktop/internal/profile"
	"github.com/smhasnanmonir/openvpn/desktop/internal/settings"
	"github.com/smhasnanmonir/openvpn/desktop/internal/vault"
)

const maxLogLines = 500

// Notifier receives user-visible events. main wires it to a desktop
// notification (and a no-op fallback when notifications are unavailable).
type Notifier interface {
	Notify(title, body string)
}

type nopNotifier struct{}

func (nopNotifier) Notify(string, string) {}

// Service is safe for concurrent use.
type Service struct {
	mu       sync.RWMutex
	vault    *vault.Vault
	dataDir  string
	helper   ovpn.HelperInfo
	notifier Notifier

	snap     model.Snapshot
	logs     []model.LogEntry
	publicIP model.PublicIPState
	portal   model.PortalState

	session       *ovpn.Session
	desired       bool
	attempts      int
	ctx           context.Context
	cancel        context.CancelFunc
	closed        bool
	portalChecked bool
}

// New creates a service for an unlocked vault.
func New(v *vault.Vault, dataDir string, helper ovpn.HelperInfo, notifier Notifier) *Service {
	if notifier == nil {
		notifier = nopNotifier{}
	}
	s := &Service{
		vault:    v,
		dataDir:  dataDir,
		helper:   helper,
		notifier: notifier,
		snap:     model.Snapshot{State: model.StateDisconnected},
	}
	if id := v.Selected(); id != "" {
		if p, ok := v.Profile(id); ok {
			s.snap.ProfileID = id
			s.snap.ProfileName = p.Summary.Name
			s.snap.ProfileEmoji = p.Summary.Emoji
			s.snap.Details.ServerHost = p.Summary.Metadata.ServerHost
			s.snap.Details.ServerPort = p.Summary.Metadata.ServerPort
			s.snap.Details.Protocol = p.Summary.Metadata.ProtocolLabel()
		}
	}
	return s
}

// Vault exposes the vault for the settings/profile screens.
func (s *Service) Vault() *vault.Vault { return s.vault }

// Helper returns how the privileged helper will be launched.
func (s *Service) Helper() ovpn.HelperInfo {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.helper
}

// SetHelper updates the helper discovery after a successful install.
func (s *Service) SetHelper(h ovpn.HelperInfo) {
	s.mu.Lock()
	s.helper = h
	s.mu.Unlock()
}

// Snapshot returns a copy of the current UI state.
func (s *Service) Snapshot() model.Snapshot {
	s.mu.RLock()
	defer s.mu.RUnlock()
	snap := s.snap
	snap.Stats = s.snap.Stats
	return snap
}

// Logs returns a copy of the log ring buffer, oldest first.
func (s *Service) Logs() []model.LogEntry {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]model.LogEntry, len(s.logs))
	copy(out, s.logs)
	return out
}

// Portal returns the last captive-portal probe.
func (s *Service) Portal() model.PortalState {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.portal
}

// CheckPortal probes the configured connectivity URL and classifies the
// result. It runs in the background; the UI polls Portal().
func (s *Service) CheckPortal() {
	url := s.vault.Settings().PortalProbeURL
	s.mu.Lock()
	s.portal = model.PortalState{Status: model.PortalChecking, ProbeURL: url}
	s.mu.Unlock()
	go func() {
		res := captiveportal.Probe(context.Background(), nil, url)
		state := portalState(res, url)
		s.mu.Lock()
		s.portal = state
		s.mu.Unlock()
		switch state.Status {
		case model.PortalDetected:
			s.addLog(model.LogWarn, "A captive portal was detected. Sign in on the network, then reconnect the VPN.")
		case model.PortalOffline:
			s.addLog(model.LogWarn, "Network check failed: "+state.Error)
		case model.PortalInternet:
			s.addLog(model.LogInfo, "Network check: internet is reachable.")
		}
	}()
}

func portalState(res captiveportal.Result, probeURL string) model.PortalState {
	state := model.PortalState{URL: res.PortalURL, Error: res.Err, ProbeURL: probeURL, CheckedAt: time.Now()}
	switch res.Status {
	case captiveportal.StatusInternet:
		state.Status = model.PortalInternet
	case captiveportal.StatusPortal:
		state.Status = model.PortalDetected
	default:
		state.Status = model.PortalOffline
	}
	return state
}

// PauseForPortal stops the tunnel so the user can sign in on the underlying
// network. The UI must not render the session as protected while this is set.
func (s *Service) PauseForPortal() error {
	snap := s.Snapshot()
	if !snap.State.IsActive() && snap.State != model.StateAuthFailed && snap.State != model.StateError {
		return errors.New("there is no active VPN session to pause")
	}
	s.Disconnect()
	s.mu.Lock()
	s.snap.PausedForPortal = true
	s.snap.ConnectedAt = time.Time{}
	s.mu.Unlock()
	s.addLog(model.LogWarn, "VPN paused for captive-portal sign-in. Traffic is not protected.")
	return nil
}

// ResumeFromPortal checks the network and reconnects if the portal has been
// completed. It runs in the background.
func (s *Service) ResumeFromPortal() error {
	snap := s.Snapshot()
	if snap.ProfileID == "" {
		return errors.New("select a profile first")
	}
	id := snap.ProfileID
	creds := s.vault.Credentials(id)
	url := s.vault.Settings().PortalProbeURL
	s.mu.Lock()
	s.portal = model.PortalState{Status: model.PortalChecking, ProbeURL: url}
	s.mu.Unlock()
	go func() {
		res := captiveportal.Probe(context.Background(), nil, url)
		state := portalState(res, url)
		s.mu.Lock()
		s.portal = state
		s.mu.Unlock()
		if state.Status != model.PortalInternet {
			s.addLog(model.LogWarn, "The network still looks like a captive portal. Finish signing in, then reconnect.")
			return
		}
		s.mu.Lock()
		s.snap.PausedForPortal = false
		s.mu.Unlock()
		if err := s.Connect(id, creds); err != nil {
			s.addLog(model.LogError, "Could not reconnect after the portal: "+err.Error())
		}
	}()
	return nil
}

func (s *Service) maybeCheckPortal() {
	if !s.vault.Settings().AutoPortalDetect {
		return
	}
	s.mu.Lock()
	if s.portalChecked {
		s.mu.Unlock()
		return
	}
	s.portalChecked = true
	s.mu.Unlock()
	s.CheckPortal()
}

// ClearLogs empties the in-memory log buffer. It does not touch any file,
// because the client never writes logs to disk.
func (s *Service) ClearLogs() {
	s.mu.Lock()
	s.logs = nil
	s.mu.Unlock()
}

// PublicIP returns the last public-IP check state.
func (s *Service) PublicIP() model.PublicIPState {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.publicIP
}

// Settings returns the persisted settings.
func (s *Service) Settings() settings.Settings { return s.vault.Settings() }

// UpdateSettings applies a settings mutation and validates it.
func (s *Service) UpdateSettings(fn func(*settings.Settings) error) error {
	if err := s.vault.UpdateSettings(fn); err != nil {
		return err
	}
	s.addLog(model.LogInfo, "Settings saved.")
	return nil
}

// Profiles lists stored profiles.
func (s *Service) Profiles() []model.ProfileSummary {
	p, _ := s.vault.Profiles()
	return p
}

// Profile returns a stored profile.
func (s *Service) Profile(id string) (model.StoredProfile, bool) {
	return s.vault.Profile(id)
}

// Credentials returns the stored credentials for a profile.
func (s *Service) Credentials(id string) model.Credentials {
	return s.vault.Credentials(id)
}

// SetCredentials stores credentials (never the static-challenge response).
func (s *Service) SetCredentials(id string, c model.Credentials) error {
	c.Response = ""
	if err := s.vault.SetCredentials(id, c); err != nil {
		return err
	}
	s.addLog(model.LogInfo, "Credentials saved for the selected profile.")
	return nil
}

// SelectProfile persists the selected profile.
func (s *Service) SelectProfile(id string) error {
	if err := s.vault.Select(id); err != nil {
		return err
	}
	p, ok := s.vault.Profile(id)
	if !ok {
		return errors.New("profile not found")
	}
	s.mu.Lock()
	s.snap.ProfileID = id
	s.snap.ProfileName = p.Summary.Name
	s.snap.ProfileEmoji = p.Summary.Emoji
	s.snap.Details.ServerHost = p.Summary.Metadata.ServerHost
	s.snap.Details.ServerPort = p.Summary.Metadata.ServerPort
	s.snap.Details.Protocol = p.Summary.Metadata.ProtocolLabel()
	s.mu.Unlock()
	s.addLog(model.LogInfo, "Selected profile: "+p.Summary.Name)
	return nil
}

// DeleteProfile removes a profile and its credentials.
func (s *Service) DeleteProfile(id string) error {
	if err := s.vault.DeleteProfile(id); err != nil {
		return err
	}
	s.mu.Lock()
	if s.snap.ProfileID == id {
		s.snap.ProfileID = ""
		s.snap.ProfileName = ""
		s.snap.ProfileEmoji = ""
		s.snap.Details = model.ConnectionDetails{}
	}
	s.mu.Unlock()
	s.addLog(model.LogInfo, "Profile deleted.")
	return nil
}

// Connect builds the hardened configuration and starts the session. It returns
// a policy error synchronously (wrong trust anchor, AEAD-only mismatch, missing
// credentials) so the UI can explain it without a retry loop.
func (s *Service) Connect(profileID string, creds model.Credentials) error {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return errors.New("the application is closing")
	}
	if s.desired || s.session != nil {
		s.mu.Unlock()
		return errors.New("a connection is already active")
	}
	s.mu.Unlock()

	stored, ok := s.vault.Profile(profileID)
	if !ok {
		return errors.New("select a profile first")
	}
	cfg := s.vault.Settings()
	if err := cfg.Validate(); err != nil {
		return err
	}
	if cfg.PerAppMode != settings.PerAppOff && runtime.GOOS != "linux" {
		return errors.New("per-app split tunneling is implemented for Linux only")
	}
	if cfg.KillSwitch && runtime.GOOS != "linux" {
		return errors.New("the nftables kill switch is implemented for Linux only")
	}
	if stored.Summary.Metadata.NeedsPassword && creds.Username == "" {
		return errors.New("this profile needs a username and password; enter them on the Profiles tab")
	}
	if stored.Summary.Metadata.StaticChallenge != "" && creds.Response == "" {
		return errors.New("this profile uses a static challenge; enter the response before connecting")
	}
	fallback := dnspolicy.ParseFallback(cfg.FallbackDNS)
	runtimeConfig, err := profile.BuildRuntimeConfig(stored.Config, profile.BuildOptions{
		Settings:    cfg,
		FallbackDNS: fallback,
	})
	if err != nil {
		return err
	}

	ctx, cancel := context.WithCancel(context.Background())
	s.mu.Lock()
	if s.desired || s.session != nil {
		s.mu.Unlock()
		cancel()
		return errors.New("a connection is already active")
	}
	s.desired = true
	s.attempts = 0
	s.portalChecked = false
	s.ctx = ctx
	s.cancel = cancel
	s.snap.State = model.StatePreparing
	s.snap.Error = ""
	s.snap.PausedForPortal = false
	s.snap.ProfileID = profileID
	s.snap.ProfileName = stored.Summary.Name
	s.snap.ProfileEmoji = stored.Summary.Emoji
	s.snap.ConnectedAt = time.Time{}
	s.snap.Stats = model.TrafficStats{}
	s.snap.DNSFallbackUsed = false
	s.snap.KillSwitch = cfg.KillSwitch
	s.snap.Details.ServerHost = stored.Summary.Metadata.ServerHost
	s.snap.Details.ServerPort = stored.Summary.Metadata.ServerPort
	s.snap.Details.Protocol = stored.Summary.Metadata.ProtocolLabel()
	s.snap.Details.MTU = profile.EffectiveMTULabel(cfg)
	s.mu.Unlock()

	s.addLog(model.LogInfo, fmt.Sprintf("Connecting to %s using %s.", stored.Summary.Metadata.DisplayServer(), stored.Summary.Name))
	go s.runLoop(ctx, profileID, stored, creds, runtimeConfig, fallback)
	return nil
}

func (s *Service) runLoop(ctx context.Context, profileID string, stored model.StoredProfile, creds model.Credentials, runtimeConfig string, fallback []string) {
	policy := ovpn.DefaultRetryPolicy()
	for {
		if !s.isDesired() {
			s.setState(model.StateDisconnected, "")
			return
		}
		session, err := s.launch(ctx, stored, creds, runtimeConfig, fallback)
		if err != nil {
			s.addLog(model.LogError, err.Error())
			s.maybeCheckPortal()
			if fatalConnectError(err.Error()) {
				s.fail(model.StateError, err.Error())
				return
			}
			s.attempts++
			if policy.ShouldGiveUp(s.attempts) {
				s.fail(model.StateError, "Could not establish the VPN after several attempts. Traffic is not protected.")
				return
			}
			s.setState(model.StateReconnecting, err.Error())
			if !s.wait(ctx, policy.Delay(s.attempts, nil)) {
				s.setState(model.StateDisconnected, "")
				return
			}
			continue
		}
		s.setSession(session)
		exitErr, fatal := s.consume(ctx, session)
		s.setSession(nil)
		if !s.isDesired() {
			s.setState(model.StateDisconnected, "")
			return
		}
		if fatal {
			state := model.StateError
			if strings.Contains(strings.ToLower(exitErr), "auth") {
				state = model.StateAuthFailed
			}
			s.fail(state, exitErr)
			return
		}
		s.attempts++
		if policy.ShouldGiveUp(s.attempts) {
			s.fail(model.StateError, "The VPN connection was lost and could not be restored after several attempts. Traffic is not protected.")
			return
		}
		s.maybeCheckPortal()
		msg := "Connection lost; retrying."
		if exitErr != "" {
			msg = "Connection lost; retrying: " + exitErr
		}
		s.setState(model.StateReconnecting, msg)
		if !s.wait(ctx, policy.Delay(s.attempts, nil)) {
			s.setState(model.StateDisconnected, "")
			return
		}
	}
}

func (s *Service) launch(ctx context.Context, stored model.StoredProfile, creds model.Credentials, runtimeConfig string, fallback []string) (*ovpn.Session, error) {
	s.setState(model.StateConnecting, "")
	cfg := s.vault.Settings()
	spec := ovpn.Spec{
		Helper:            s.Helper(),
		Config:            runtimeConfig,
		Credentials:       creds,
		Settings:          cfg,
		FallbackDNS:       fallback,
		DataDir:           s.dataDir,
		StaticChallenge:   stored.Summary.Metadata.StaticChallenge != "",
		PerAppMode:        string(cfg.PerAppMode),
		PerAppExecutables: append([]string(nil), cfg.PerAppExecutables...),
	}
	return ovpn.Start(ctx, spec)
}

func (s *Service) consume(ctx context.Context, session *ovpn.Session) (string, bool) {
	lastErr := ""
	fatal := false
	for ev := range session.Events() {
		switch ev.Kind {
		case ovpn.EventState:
			switch ev.State {
			case model.StateConnected:
				s.mu.Lock()
				prev := s.snap.State
				s.snap.State = model.StateConnected
				s.snap.ConnectedAt = time.Now()
				s.snap.Error = ""
				s.mu.Unlock()
				s.attempts = 0
				if prev != model.StateConnected {
					s.addLog(model.LogInfo, "Tunnel is up; traffic is protected.")
					s.notifier.Notify("OpenVPN Desktop", "Connected — traffic is protected.")
					if s.vault.Settings().AutoCheckIP {
						go s.CheckPublicIP()
					}
				}
			case model.StateAuthFailed:
				fatal = true
				lastErr = "Authentication failed. Check the username, password and any static-challenge response."
				s.setState(model.StateAuthFailed, lastErr)
			default:
				s.setState(ev.State, ev.Error)
			}
		case ovpn.EventLog:
			s.addLog(ev.Log.Level, ev.Log.Message)
			lower := strings.ToLower(ev.Log.Message)
			if strings.Contains(lower, "auth_failed") {
				fatal = true
				lastErr = "Authentication failed. Check the username, password and any static-challenge response."
			}
			if strings.Contains(lower, "require tunnel dns") ||
				strings.Contains(lower, "dns-up") ||
				strings.Contains(lower, "no usable tunnel dns") ||
				strings.Contains(lower, "dns requests cannot leak") {
				fatal = true
				lastErr = "The connection was stopped by the fail-closed DNS policy. Add a fallback DNS server in Settings or use a profile that pushes a resolver."
			}
			if strings.Contains(lower, "certificate verify failed") || strings.Contains(lower, "tls error") {
				fatal = true
				lastErr = "The server certificate could not be verified. The connection was stopped."
			}
		case ovpn.EventStats:
			s.mu.Lock()
			s.snap.Stats = ev.Stats
			s.mu.Unlock()
		case ovpn.EventDetails:
			s.mu.Lock()
			if ev.Details.ServerIP != "" {
				s.snap.Details.ServerIP = ev.Details.ServerIP
			}
			if ev.Details.ServerPort != "" {
				s.snap.Details.ServerPort = ev.Details.ServerPort
			}
			if ev.Details.VPNIP4 != "" {
				s.snap.Details.VPNIP4 = ev.Details.VPNIP4
			}
			if ev.Details.VPNIP6 != "" {
				s.snap.Details.VPNIP6 = ev.Details.VPNIP6
			}
			if ev.Details.TunName != "" {
				s.snap.Details.TunName = ev.Details.TunName
			}
			if ev.Details.DNSServers != "" {
				s.snap.Details.DNSServers = ev.Details.DNSServers
				s.snap.DNSFallbackUsed = ev.Details.DNSFallback
			}
			s.mu.Unlock()
		case ovpn.EventExited:
			if ev.Error != "" {
				lastErr = ev.Error
			}
		}
	}
	s.mu.Lock()
	wasConnected := s.snap.State == model.StateConnected
	s.mu.Unlock()
	if wasConnected {
		s.notifier.Notify("OpenVPN Desktop", "Disconnected.")
	}
	return lastErr, fatal
}

func (s *Service) isDesired() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.desired && !s.closed
}

func (s *Service) wait(ctx context.Context, d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-t.C:
		return s.isDesired()
	}
}

// Disconnect stops the session and keeps the app running.
func (s *Service) Disconnect() {
	s.mu.Lock()
	s.snap.PausedForPortal = false
	if !s.desired && s.session == nil {
		s.mu.Unlock()
		return
	}
	s.desired = false
	if s.cancel != nil {
		s.cancel()
		s.cancel = nil
	}
	session := s.session
	s.snap.State = model.StateDisconnecting
	s.mu.Unlock()
	s.addLog(model.LogInfo, "Disconnecting…")
	if session != nil {
		session.Stop()
	} else {
		s.setState(model.StateDisconnected, "")
	}
}

// Reconnect stops the current session and starts a fresh one with the stored
// credentials.
func (s *Service) Reconnect() error {
	snap := s.Snapshot()
	if snap.ProfileID == "" {
		return errors.New("no profile selected")
	}
	id := snap.ProfileID
	creds := s.vault.Credentials(id)
	s.Disconnect()
	time.Sleep(300 * time.Millisecond)
	return s.Connect(id, creds)
}

// CheckPublicIP performs the optional public-IP check. It never runs
// automatically at startup; the settings screen and the home screen's button
// invoke it.
func (s *Service) CheckPublicIP() {
	url := s.vault.Settings().PublicIPURL
	if url == "" {
		url = settings.Default().PublicIPURL
	}
	snap := s.Snapshot()
	s.mu.Lock()
	s.publicIP = model.PublicIPState{Loading: true, ThroughTunnel: snap.State == model.StateConnected}
	s.mu.Unlock()
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
		if err != nil {
			s.finishPublicIP("", err)
			return
		}
		req.Header.Set("User-Agent", "OpenVPN-Desktop/1.0")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			s.finishPublicIP("", err)
			return
		}
		defer resp.Body.Close()
		body, err := io.ReadAll(io.LimitReader(resp.Body, 256))
		if err != nil {
			s.finishPublicIP("", err)
			return
		}
		if resp.StatusCode != http.StatusOK {
			s.finishPublicIP("", fmt.Errorf("public-IP service returned HTTP %d", resp.StatusCode))
			return
		}
		text := strings.TrimSpace(string(body))
		addr, err := netip.ParseAddr(text)
		if err != nil || !addr.IsValid() {
			s.finishPublicIP("", errors.New("the public-IP service returned an unexpected response"))
			return
		}
		s.finishPublicIP(addr.String(), nil)
	}()
}

func (s *Service) finishPublicIP(ip string, err error) {
	s.mu.Lock()
	state := model.PublicIPState{CheckedAt: time.Now(), ThroughTunnel: s.publicIP.ThroughTunnel}
	if err != nil {
		state.Error = err.Error()
	} else {
		state.IP = ip
	}
	s.publicIP = state
	s.mu.Unlock()
}

func (s *Service) addLog(level model.LogLevel, message string) {
	if message == "" {
		return
	}
	entry := model.LogEntry{Time: time.Now(), Level: level, Message: message}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.logs = append(s.logs, entry)
	if len(s.logs) > maxLogLines {
		s.logs = s.logs[len(s.logs)-maxLogLines:]
	}
}

func (s *Service) setState(state model.VPNState, errText string) {
	s.mu.Lock()
	if errText != "" {
		s.snap.Error = errText
	}
	if state != "" {
		s.snap.State = state
	}
	if state == model.StateDisconnected {
		s.snap.ConnectedAt = time.Time{}
	}
	s.mu.Unlock()
}

func (s *Service) setSession(session *ovpn.Session) {
	s.mu.Lock()
	s.session = session
	s.mu.Unlock()
}

func (s *Service) fail(state model.VPNState, message string) {
	s.mu.Lock()
	s.desired = false
	s.snap.State = state
	s.snap.Error = message
	s.snap.ConnectedAt = time.Time{}
	s.mu.Unlock()
	s.addLog(model.LogError, message)
	s.notifier.Notify("OpenVPN Desktop", "Connection failed: "+message)
}

// Close stops any session and locks the vault.
func (s *Service) Close() {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	s.closed = true
	s.desired = false
	if s.cancel != nil {
		s.cancel()
	}
	session := s.session
	s.mu.Unlock()
	if session != nil {
		session.Stop()
	}
	s.vault.Lock()
}

func fatalConnectError(msg string) bool {
	lower := strings.ToLower(msg)
	for _, marker := range []string{
		"no ca certificate", "no ca", "peer-fingerprint", "aead-only", "non-aead",
		"not installed", "privileged helper", "must run as root", "openvpn not found",
	} {
		if strings.Contains(lower, marker) {
			return true
		}
	}
	return false
}

// DataDir returns the directory used for sessions (also the vault's parent).
func (s *Service) DataDir() string { return s.dataDir }

// HelperHint explains how to enable the privileged helper.
func HelperHint(helper ovpn.HelperInfo) string {
	if helper.Mode == ovpn.PrivilegeService && helper.Available {
		return "Privileged helper service: " + helper.ServiceAddr
	}
	if helper.Available {
		return "Privileged helper: " + helper.Path + " (" + string(helper.Mode) + ")"
	}
	hint := "The privileged helper is not ready: " + helper.Detail
	if helper.InstallAvailable {
		switch runtime.GOOS {
		case "windows":
			return hint + "\nClick Install privileged helper below. Windows will ask for administrator approval; " +
				"install OpenVPN 2.7+ with the TAP-Windows6 driver first."
		case "darwin":
			return hint + "\nClick Install privileged helper below. macOS will ask for administrator approval; " +
				"install OpenVPN 2.7+ (Homebrew or official) first."
		default:
			return hint + "\nClick Install privileged helper below. polkit will ask for administrator approval."
		}
	}
	switch runtime.GOOS {
	case "windows":
		hint += "\nInstall the packaged helper, or run the Windows service installer from an elevated PowerShell. " +
			"Install OpenVPN 2.7+ with the TAP-Windows6 driver first."
	case "darwin":
		hint += "\nRun: sudo ./scripts/install-macos-service.sh. A root-owned OpenVPN 2.7+ binary is required."
	default:
		hint += "\nRun desktop/scripts/install-helper.sh once. Without it the app cannot create a TUN device."
	}
	return hint
}

// HelperSetupCommand is the platform command shown/copied in Settings.
func HelperSetupCommand(helper ovpn.HelperInfo) string {
	if helper.Available {
		return ""
	}
	switch runtime.GOOS {
	case "windows":
		return "powershell -ExecutionPolicy Bypass -File desktop\\scripts\\install-windows-service.ps1"
	case "darwin":
		return "sudo desktop/scripts/install-macos-service.sh"
	default:
		return "cd desktop && ./scripts/install-helper.sh"
	}
}

// Ensure data directory exists with private permissions.
func EnsureDataDir(dir string) error {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	return os.Chmod(dir, 0o700)
}
