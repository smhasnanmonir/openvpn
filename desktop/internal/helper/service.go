package helper

import (
	"bufio"
	"context"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/smhasnanmonir/openvpn/desktop/internal/security"
	"github.com/smhasnanmonir/openvpn/desktop/internal/serviceproto"
)

// ServeContext runs the privileged helper service on the loopback endpoint in
// the root-owned config file. It is used by the Windows service wrapper, the
// macOS launchd daemon, and `openvpn-desktop-helper serve`.
func ServeContext(ctx context.Context, configPath string) error {
	cfg, err := serviceproto.Load(configPath)
	if err != nil {
		return err
	}
	host, _, err := net.SplitHostPort(cfg.Addr)
	if err != nil {
		return err
	}
	if host != "127.0.0.1" && host != "localhost" && host != "::1" {
		return errors.New("the helper service must listen on loopback")
	}
	ln, err := net.Listen("tcp", cfg.Addr)
	if err != nil {
		return err
	}
	defer ln.Close()
	go func() {
		<-ctx.Done()
		_ = ln.Close()
	}()
	for {
		conn, err := ln.Accept()
		if err != nil {
			select {
			case <-ctx.Done():
				return nil
			default:
				return err
			}
		}
		go handleServiceConn(ctx, conn, cfg.Token)
	}
}

func handleServiceConn(ctx context.Context, conn net.Conn, token string) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(15 * time.Second))
	dec := json.NewDecoder(io.LimitReader(conn, serviceproto.MaxJobBytes))
	var job serviceproto.Job
	if err := dec.Decode(&job); err != nil {
		_ = sendService(conn, serviceproto.Message{Type: "error", Error: "invalid service job"})
		return
	}
	if subtle.ConstantTimeCompare([]byte(job.Token), []byte(token)) != 1 {
		_ = sendService(conn, serviceproto.Message{Type: "error", Error: "unauthorized"})
		return
	}
	_ = conn.SetDeadline(time.Time{})
	runServiceJob(ctx, conn, dec, job)
}

func runServiceJob(ctx context.Context, conn net.Conn, dec *json.Decoder, job serviceproto.Job) {
	host, _, err := net.SplitHostPort(job.ManagementAddr)
	if err != nil || (host != "127.0.0.1" && host != "localhost" && host != "::1") {
		_ = sendService(conn, serviceproto.Message{Type: "exit", ExitCode: 2, Error: "management must bind to loopback"})
		return
	}
	if strings.TrimSpace(job.Config) == "" {
		_ = sendService(conn, serviceproto.Message{Type: "exit", ExitCode: 2, Error: "empty configuration"})
		return
	}
	tempDir, err := secureTempDir()
	if err != nil {
		_ = sendService(conn, serviceproto.Message{Type: "exit", ExitCode: 1, Error: err.Error()})
		return
	}
	defer os.RemoveAll(tempDir)

	configPath := filepath.Join(tempDir, "profile.ovpn")
	if err := writeFile(configPath, []byte(job.Config), 0o600); err != nil {
		_ = sendService(conn, serviceproto.Message{Type: "exit", ExitCode: 1, Error: err.Error()})
		return
	}
	mgmtPass := filepath.Join(tempDir, "management.pass")
	if err := writeFile(mgmtPass, []byte(job.ManagementPassword+"\n"), 0o600); err != nil {
		_ = sendService(conn, serviceproto.Message{Type: "exit", ExitCode: 1, Error: err.Error()})
		return
	}
	args := []string{
		"run",
		"--config", configPath,
		"--management", job.ManagementAddr,
		"--management-password-file", mgmtPass,
		"--require-dns", boolFlag(job.RequireDNS),
		"--kill-switch", boolFlag(job.KillSwitch),
		"--verb", strconv.Itoa(max(job.Verb, 3)),
	}
	if len(job.FallbackDNS) > 0 {
		args = append(args, "--fallback-dns", strings.Join(job.FallbackDNS, ","))
	}
	if job.Username != "" || job.Password != "" || job.Response != "" {
		credsPath := filepath.Join(tempDir, "credentials")
		if err := writeFile(credsPath, []byte(serviceCredentials(job)), 0o600); err != nil {
			_ = sendService(conn, serviceproto.Message{Type: "exit", ExitCode: 1, Error: err.Error()})
			return
		}
		args = append(args, "--credentials", credsPath)
	}
	if job.PrivateKeyPassword != "" {
		keyPath := filepath.Join(tempDir, "key.pass")
		if err := writeFile(keyPath, []byte(job.PrivateKeyPassword+"\n"), 0o600); err != nil {
			_ = sendService(conn, serviceproto.Message{Type: "exit", ExitCode: 1, Error: err.Error()})
			return
		}
		args = append(args, "--key-password-file", keyPath)
	}
	if job.PerAppMode != "" && job.PerAppMode != "off" {
		args = append(args, "--per-app-mode", job.PerAppMode)
		for _, exe := range job.PerAppExecutables {
			args = append(args, "--per-app-exec", exe)
		}
	}

	self, err := os.Executable()
	if err != nil {
		_ = sendService(conn, serviceproto.Message{Type: "exit", ExitCode: 1, Error: err.Error()})
		return
	}
	cmd := exec.Command(self, args...)
	configureServiceChild(cmd)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		_ = sendService(conn, serviceproto.Message{Type: "exit", ExitCode: 1, Error: err.Error()})
		return
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		_ = sendService(conn, serviceproto.Message{Type: "exit", ExitCode: 1, Error: err.Error()})
		return
	}
	if err := cmd.Start(); err != nil {
		_ = sendService(conn, serviceproto.Message{Type: "exit", ExitCode: 1, Error: err.Error()})
		return
	}

	var sendMu sync.Mutex
	sendLog := func(line string) {
		if line == "" {
			return
		}
		sendMu.Lock()
		defer sendMu.Unlock()
		_ = sendService(conn, serviceproto.Message{Type: "log", Line: security.Sanitize(line)})
	}
	var wg sync.WaitGroup
	wg.Add(2)
	go streamServiceOutput(stdout, sendLog, &wg)
	go streamServiceOutput(stderr, sendLog, &wg)

	stop := make(chan struct{})
	go func() {
		for {
			var m serviceproto.Message
			if err := dec.Decode(&m); err != nil {
				select {
				case <-stop:
				default:
					close(stop)
					_ = terminateServiceChild(cmd)
				}
				return
			}
			if m.Type == "stop" {
				select {
				case <-stop:
				default:
					close(stop)
					_ = terminateServiceChild(cmd)
				}
				return
			}
		}
	}()
	select {
	case <-ctx.Done():
		_ = terminateServiceChild(cmd)
	case <-stop:
	}

	waitErr := cmd.Wait()
	wg.Wait()
	exitCode := 0
	errText := ""
	if waitErr != nil {
		exitCode = 1
		errText = waitErr.Error()
		if exit, ok := waitErr.(*exec.ExitError); ok {
			exitCode = exit.ExitCode()
		}
	}
	_ = sendService(conn, serviceproto.Message{Type: "exit", ExitCode: exitCode, Error: errText})
}

func streamServiceOutput(r io.Reader, send func(string), wg *sync.WaitGroup) {
	defer wg.Done()
	scanner := bufio.NewScanner(r)
	scanner.Buffer(make([]byte, 64*1024), 1024*1024)
	for scanner.Scan() {
		send(scanner.Text())
	}
}

func sendService(conn net.Conn, m serviceproto.Message) error {
	data, err := json.Marshal(m)
	if err != nil {
		return err
	}
	data = append(data, '\n')
	_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	_, err = conn.Write(data)
	return err
}

func serviceCredentials(job serviceproto.Job) string {
	pass := job.Password
	if job.Response != "" {
		pass = "SCRV1:" + base64.StdEncoding.EncodeToString([]byte(job.Password)) +
			":" + base64.StdEncoding.EncodeToString([]byte(job.Response))
	}
	return job.Username + "\n" + pass + "\n"
}

func serveCommand(args []string) int {
	configPath := ""
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--config":
			if i+1 < len(args) {
				configPath = args[i+1]
				i++
			}
		}
	}
	if configPath == "" {
		p, err := serviceproto.SystemPath()
		if err != nil {
			fmt.Fprintln(os.Stderr, "openvpn-desktop helper serve:", err)
			return 2
		}
		configPath = p
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	sigCh := make(chan os.Signal, 4)
	signal.Notify(sigCh, syscall.SIGTERM, syscall.SIGINT, syscall.SIGHUP)
	defer signal.Stop(sigCh)
	go func() {
		<-sigCh
		cancel()
	}()
	if err := ServeContext(ctx, configPath); err != nil {
		fmt.Fprintln(os.Stderr, "openvpn-desktop helper serve:", err)
		return 1
	}
	return 0
}
