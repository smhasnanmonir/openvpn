// Package helper implements the privileged side of the desktop client. It is
// built as a separate binary and is meant to be root-owned (setuid, pkexec or
// passwordless sudo). The GUI passes it only a profile path, credential paths,
// a management endpoint and policy flags; the helper re-sanitizes the profile,
// removes every dangerous directive, and builds the OpenVPN argv itself so a
// tampered config cannot run a script as root.
package helper

import (
	"bufio"
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
	"syscall"
	"time"

	"github.com/smhasnanmonir/openvpn/desktop/internal/dnspolicy"
	"github.com/smhasnanmonir/openvpn/desktop/internal/profile"
)

var StateDir = stateDir()

// Main dispatches a helper subcommand and returns a process exit code.
func Main(args []string, env []string) int {
	if len(args) == 0 {
		usage()
		return 2
	}
	envm := envMap(env)
	switch args[0] {
	case "run":
		return runCommand(args[1:], envm)
	case "dns-up":
		if err := DNSUp(envm); err != nil {
			fmt.Fprintln(os.Stderr, "openvpn-desktop dns-up:", err)
			return 1
		}
		return 0
	case "dns-down":
		if err := DNSDown(envm); err != nil {
			fmt.Fprintln(os.Stderr, "openvpn-desktop dns-down:", err)
		}
		return 0
	case "kill-switch-up":
		if len(args) < 2 {
			fmt.Fprintln(os.Stderr, "usage: kill-switch-up <server-ip>[,<server-ip>...]")
			return 2
		}
		var ips []string
		for _, s := range strings.Split(args[1], ",") {
			if s = strings.TrimSpace(s); s != "" {
				ips = append(ips, s)
			}
		}
		if err := InstallKillSwitch(ips, false); err != nil {
			fmt.Fprintln(os.Stderr, "openvpn-desktop kill-switch-up:", err)
			return 1
		}
		return 0
	case "kill-switch-down", "cleanup":
		_ = RemoveKillSwitch()
		_ = restoreResolvBackup()
		cleanupStaleSplits()
		removeStaleSessions()
		return 0
	case "serve":
		return serveCommand(args[1:])
	case "doctor":
		printDoctor()
		return 0
	default:
		usage()
		return 2
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, `usage: openvpn-desktop-helper <command>

commands:
  run [options]            sanitize a profile and run openvpn as root
  dns-up                   apply the DNS policy from OpenVPN's script environment
  dns-down                 restore DNS
  kill-switch-up <ips>     install the nftables kill switch
  kill-switch-down         remove the kill switch
  cleanup                  remove the kill switch, restore resolv.conf, prune stale sessions
  doctor                   print the helper's environment and trusted tools`)
}

func printDoctor() {
	fmt.Printf("openvpn-desktop helper\n")
	fmt.Printf("uid=%d euid=%d\n", os.Getuid(), os.Geteuid())
	fmt.Printf("state dir: %s\n", StateDir)
	if p, err := findOpenVPN(); err == nil {
		fmt.Printf("%-11s %s\n", "openvpn:", p)
	} else {
		fmt.Printf("%-11s (not found: %v)\n", "openvpn:", err)
	}
	for _, name := range []string{"nft", "resolvectl", "resolvconf", "ip", "networksetup"} {
		if p, err := findTrusted(name); err == nil {
			fmt.Printf("%-11s %s\n", name+":", p)
		} else {
			fmt.Printf("%-11s (not found)\n", name+":")
		}
	}
}

type runOptions struct {
	config      string
	credentials string
	keyPassword string
	mgmtAddr    string
	mgmtPass    string
	requireDNS  bool
	killSwitch  bool
	fullDNS     bool
	allowLAN    bool
	fallbackDNS string
	perAppMode  string
	perAppExec  []string
	verb        int
}

func parseRun(args []string) (runOptions, error) {
	opt := runOptions{verb: 3, fullDNS: true}
	for i := 0; i < len(args); i++ {
		arg := args[i]
		var key, val string
		if strings.HasPrefix(arg, "--") {
			if eq := strings.IndexByte(arg, '='); eq >= 0 {
				key, val = arg[:eq], arg[eq+1:]
			} else {
				key = arg
				if i+1 < len(args) && !strings.HasPrefix(args[i+1], "--") {
					val = args[i+1]
					i++
				}
			}
		} else {
			return opt, fmt.Errorf("unexpected argument %q", arg)
		}
		switch key {
		case "--config":
			opt.config = val
		case "--credentials":
			opt.credentials = val
		case "--key-password-file":
			opt.keyPassword = val
		case "--management":
			opt.mgmtAddr = val
		case "--management-password-file":
			opt.mgmtPass = val
		case "--require-dns":
			opt.requireDNS = val == "1" || strings.EqualFold(val, "true")
		case "--kill-switch":
			opt.killSwitch = val == "1" || strings.EqualFold(val, "true")
		case "--full-dns":
			opt.fullDNS = val == "1" || strings.EqualFold(val, "true")
		case "--allow-lan":
			opt.allowLAN = val == "1" || strings.EqualFold(val, "true")
		case "--fallback-dns":
			opt.fallbackDNS = val
		case "--per-app-mode":
			opt.perAppMode = strings.ToLower(strings.TrimSpace(val))
		case "--per-app-exec":
			if strings.TrimSpace(val) == "" {
				return opt, errors.New("--per-app-exec needs an absolute executable path")
			}
			opt.perAppExec = append(opt.perAppExec, val)
		case "--verb":
			n, err := strconv.Atoi(val)
			if err != nil || n < 0 || n > 11 {
				return opt, fmt.Errorf("invalid --verb %q", val)
			}
			opt.verb = n
		default:
			return opt, fmt.Errorf("unknown option %q", key)
		}
	}
	if opt.config == "" {
		return opt, errors.New("--config is required")
	}
	if opt.mgmtAddr == "" || opt.mgmtPass == "" {
		return opt, errors.New("--management and --management-password-file are required")
	}
	host, port, err := net.SplitHostPort(opt.mgmtAddr)
	if err != nil {
		return opt, fmt.Errorf("invalid management address: %w", err)
	}
	if host != "127.0.0.1" && host != "::1" && host != "localhost" {
		return opt, errors.New("the management interface must bind to loopback")
	}
	if n, err := strconv.Atoi(port); err != nil || n < 1 || n > 65535 {
		return opt, errors.New("invalid management port")
	}
	for _, item := range strings.Split(opt.fallbackDNS, ",") {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		if _, ok := dnspolicy.NormalizeServer(item); !ok {
			return opt, fmt.Errorf("invalid fallback DNS server %q", item)
		}
	}
	switch opt.perAppMode {
	case "", "off":
		opt.perAppMode = "off"
	case "include", "exclude":
	default:
		return opt, fmt.Errorf("unknown --per-app-mode %q (off, include or exclude)", opt.perAppMode)
	}
	if opt.perAppMode != "off" && len(opt.perAppExec) == 0 {
		return opt, errors.New("--per-app-mode needs at least one --per-app-exec")
	}
	for _, exe := range opt.perAppExec {
		if !filepath.IsAbs(exe) || strings.ContainsAny(exe, "\r\n\x00") {
			return opt, fmt.Errorf("--per-app-exec must be an absolute path: %q", exe)
		}
	}
	return opt, nil
}

func runCommand(args []string, env map[string]string) int {
	opt, err := parseRun(args)
	if err != nil {
		fmt.Fprintln(os.Stderr, "openvpn-desktop helper:", err)
		return 2
	}
	if !isPrivileged() {
		fmt.Fprintln(os.Stderr, "openvpn-desktop helper: this command must run with administrator/root privileges")
		return 2
	}
	if err := ensureStateDir(); err != nil {
		fmt.Fprintln(os.Stderr, "openvpn-desktop helper:", err)
		return 1
	}
	removeStaleSessions()

	raw, err := readBoundedRegular(opt.config, 4<<20)
	if err != nil {
		fmt.Fprintln(os.Stderr, "openvpn-desktop helper: read config:", err)
		return 1
	}
	clean, removed := profile.SanitizeConfig(string(raw))
	if err := profile.ValidateNoExternalFiles(clean); err != nil {
		fmt.Fprintln(os.Stderr, "openvpn-desktop helper:", err)
		return 1
	}
	clean = stripManagedSetenv(clean)
	clean = profile.WithServerCertificateUsage(clean)
	trust := profile.ValidateTrust(clean)
	if !trust.Allowed {
		fmt.Fprintln(os.Stderr, "openvpn-desktop helper:", trust.Message)
		return 1
	}
	clean = appendSetenv(clean,
		"OPENVPN_DESKTOP_REQUIRE_DNS", boolFlag(opt.requireDNS),
		"OPENVPN_DESKTOP_FALLBACK_DNS", opt.fallbackDNS,
		"OPENVPN_DESKTOP_FULL_DNS", boolFlag(opt.fullDNS),
		"OPENVPN_DESKTOP_KILL_SWITCH", boolFlag(opt.killSwitch),
		"OPENVPN_DESKTOP_ALLOW_LAN", boolFlag(opt.allowLAN),
	)
	if len(removed) > 0 {
		fmt.Fprintf(os.Stderr, "[openvpn-desktop] removed profile directives: %s\n", strings.Join(removed, ", "))
	}

	workDir, err := os.MkdirTemp(StateDir, "run-")
	if err != nil {
		fmt.Fprintln(os.Stderr, "openvpn-desktop helper:", err)
		return 1
	}
	_ = secureDir(workDir)
	defer os.RemoveAll(workDir)

	var serverIPs []string
	if opt.killSwitch {
		pinned, ips, err := pinRemoteHosts(clean)
		if err != nil {
			fmt.Fprintln(os.Stderr, "openvpn-desktop helper: kill switch:", err)
			return 1
		}
		clean = pinned
		serverIPs = ips
	}

	configPath := filepath.Join(workDir, "openvpn.conf")
	if err := writeFile(configPath, []byte(clean), 0o600); err != nil {
		fmt.Fprintln(os.Stderr, "openvpn-desktop helper:", err)
		return 1
	}
	credsPath := ""
	if opt.credentials != "" {
		data, err := readBoundedRegular(opt.credentials, 64<<10)
		if err != nil {
			fmt.Fprintln(os.Stderr, "openvpn-desktop helper: credentials:", err)
			return 1
		}
		credsPath = filepath.Join(workDir, "credentials")
		if err := writeFile(credsPath, data, 0o600); err != nil {
			fmt.Fprintln(os.Stderr, "openvpn-desktop helper:", err)
			return 1
		}
	}
	keyPassPath := ""
	if opt.keyPassword != "" {
		data, err := readBoundedRegular(opt.keyPassword, 64<<10)
		if err != nil {
			fmt.Fprintln(os.Stderr, "openvpn-desktop helper: key password:", err)
			return 1
		}
		keyPassPath = filepath.Join(workDir, "key.pass")
		if err := writeFile(keyPassPath, data, 0o600); err != nil {
			fmt.Fprintln(os.Stderr, "openvpn-desktop helper:", err)
			return 1
		}
	}
	mgmtPassPath := filepath.Join(workDir, "management.pass")
	mgmtPass, err := readBoundedRegular(opt.mgmtPass, 4096)
	if err != nil {
		fmt.Fprintln(os.Stderr, "openvpn-desktop helper: management password:", err)
		return 1
	}
	if err := writeFile(mgmtPassPath, mgmtPass, 0o600); err != nil {
		fmt.Fprintln(os.Stderr, "openvpn-desktop helper:", err)
		return 1
	}

	if opt.killSwitch && !supportsKillSwitch() {
		fmt.Fprintln(os.Stderr, "openvpn-desktop helper: the kill switch is implemented for Linux only")
		return 1
	}
	if opt.killSwitch {
		if err := InstallKillSwitch(serverIPs, opt.perAppMode != "off"); err != nil {
			fmt.Fprintln(os.Stderr, "openvpn-desktop helper: could not install kill switch:", err)
			return 1
		}
		defer RemoveKillSwitch()
	}

	openvpn, err := findOpenVPN()
	if err != nil {
		fmt.Fprintln(os.Stderr, "openvpn-desktop helper:", err)
		return 1
	}
	self, err := os.Executable()
	if err != nil || self == "" {
		self = "openvpn-desktop-helper"
	}
	self = filepath.Clean(self)
	mgmtHost, mgmtPort, _ := net.SplitHostPort(opt.mgmtAddr)

	argsOpenVPN := []string{
		"--config", configPath,
		"--management", mgmtHost, mgmtPort, mgmtPassPath,
		"--auth-nocache",
		"--persist-key",
		"--verb", strconv.Itoa(opt.verb),
	}
	// OpenVPN 2.7 otherwise runs its own DNS script, which would bypass the
	// fail-closed policy. Windows uses --block-outside-dns instead.
	argsOpenVPN = append(argsOpenVPN, dnsUpdownArgs()...)
	argsOpenVPN = append(argsOpenVPN, platformRunArgs(self)...)
	if credsPath != "" {
		argsOpenVPN = append(argsOpenVPN, "--auth-user-pass", credsPath)
	}
	if keyPassPath != "" {
		argsOpenVPN = append(argsOpenVPN, "--askpass", keyPassPath)
	}

	cmd := exec.Command(openvpn, argsOpenVPN...)
	cmd.Dir = workDir
	cmd.Env = minimalEnv(opt)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	cmd.Stdin = os.Stdin
	split, err := startSplit(splitSpec{
		Mode:        opt.perAppMode,
		Executables: opt.perAppExec,
	})
	if err != nil {
		fmt.Fprintln(os.Stderr, "openvpn-desktop helper: per-app split tunneling:", err)
		return 1
	}
	if split != nil {
		defer split.Stop()
	}

	if err := cmd.Start(); err != nil {
		fmt.Fprintln(os.Stderr, "openvpn-desktop helper: start openvpn:", err)
		return 1
	}
	if split != nil {
		if setter, ok := split.(vpnProcessSetter); ok {
			if err := setter.SetVPNProcess(cmd.Process.Pid); err != nil {
				fmt.Fprintln(os.Stderr, "openvpn-desktop helper: per-app split VPN process:", err)
				_ = cmd.Process.Kill()
				_, _ = cmd.Process.Wait()
				return 1
			}
		}
	}

	sigCh := make(chan os.Signal, 8)
	signal.Notify(sigCh, syscall.SIGTERM, syscall.SIGINT, syscall.SIGHUP)
	defer signal.Stop(sigCh)
	done := make(chan struct{})
	go func() {
		for {
			select {
			case sig := <-sigCh:
				_ = cmd.Process.Signal(sig)
			case <-done:
				return
			}
		}
	}()

	waitErr := cmd.Wait()
	close(done)
	_ = RemoveKillSwitch()
	_ = restoreResolvBackup()
	if waitErr != nil {
		if exit, ok := waitErr.(*exec.ExitError); ok {
			return exit.ExitCode()
		}
		fmt.Fprintln(os.Stderr, "openvpn-desktop helper:", waitErr)
		return 1
	}
	return 0
}

func appendSetenv(config string, kv ...string) string {
	body := strings.TrimRight(config, "\r\n")
	for i := 0; i+1 < len(kv); i += 2 {
		if kv[i] == "" || kv[i+1] == "" {
			continue
		}
		body += "\nsetenv " + kv[i] + " " + kv[i+1]
	}
	return body + "\n"
}

func stripManagedSetenv(config string) string {
	names := []string{
		"OPENVPN_DESKTOP_REQUIRE_DNS",
		"OPENVPN_DESKTOP_FALLBACK_DNS",
		"OPENVPN_DESKTOP_FULL_DNS",
		"OPENVPN_DESKTOP_KILL_SWITCH",
		"OPENVPN_DESKTOP_ALLOW_LAN",
	}
	lines := strings.Split(config, "\n")
	out := make([]string, 0, len(lines))
	for _, line := range lines {
		t := strings.TrimSpace(line)
		lower := strings.ToLower(t)
		skip := false
		for _, name := range names {
			if strings.HasPrefix(lower, "setenv "+strings.ToLower(name)+" ") ||
				strings.EqualFold(t, "setenv "+name) {
				skip = true
				break
			}
		}
		if !skip {
			out = append(out, line)
		}
	}
	return strings.Join(out, "\n")
}

// pinRemoteHosts resolves every hostname in a remote directive to IP addresses
// and rewrites the config to use them. A kill switch blocks DNS, so OpenVPN
// must not need to resolve a hostname after the firewall is installed. It
// returns the list of IPs to allow through the firewall.
func pinRemoteHosts(config string) (string, []string, error) {
	lines := strings.Split(config, "\n")
	out := make([]string, 0, len(lines))
	seenIP := map[string]bool{}
	var ips []string
	for _, line := range lines {
		fields := strings.Fields(strings.TrimSpace(line))
		if len(fields) < 2 || !strings.EqualFold(fields[0], "remote") {
			out = append(out, line)
			continue
		}
		host := fields[1]
		if net.ParseIP(host) != nil {
			if !seenIP[host] {
				seenIP[host] = true
				ips = append(ips, host)
			}
			out = append(out, line)
			continue
		}
		addrs, err := net.LookupIP(host)
		if err != nil || len(addrs) == 0 {
			return "", nil, fmt.Errorf("could not resolve %q before installing the kill switch: %v", host, err)
		}
		for _, addr := range addrs {
			ip := addr.String()
			if addr.To4() != nil {
				ip = addr.To4().String()
			}
			if !seenIP[ip] {
				seenIP[ip] = true
				ips = append(ips, ip)
			}
			rest := ""
			if len(fields) > 2 {
				rest = " " + strings.Join(fields[2:], " ")
			}
			out = append(out, "remote "+ip+rest)
		}
	}
	if len(ips) == 0 {
		return "", nil, errors.New("no remote server address could be resolved")
	}
	return strings.Join(out, "\n"), ips, nil
}

func ensureStateDir() error {
	if err := os.MkdirAll(StateDir, 0o700); err != nil {
		return err
	}
	return secureDir(StateDir)
}

func removeStaleSessions() {
	entries, err := os.ReadDir(StateDir)
	if err != nil {
		return
	}
	cutoff := time.Now().Add(-6 * time.Hour)
	for _, e := range entries {
		if !strings.HasPrefix(e.Name(), "run-") {
			continue
		}
		info, err := e.Info()
		if err == nil && info.ModTime().Before(cutoff) {
			_ = os.RemoveAll(filepath.Join(StateDir, e.Name()))
		}
	}
}

func readBoundedRegular(path string, max int64) ([]byte, error) {
	st, err := os.Lstat(path)
	if err != nil {
		return nil, err
	}
	if st.Mode()&os.ModeSymlink != 0 {
		return nil, fmt.Errorf("%s is a symlink; refusing to follow a link as root", path)
	}
	if !st.Mode().IsRegular() {
		return nil, fmt.Errorf("%s is not a regular file", path)
	}
	if st.Size() > max {
		return nil, fmt.Errorf("%s is larger than %d bytes", path, max)
	}
	if unsafeMode(st) {
		return nil, fmt.Errorf("%s is writable by other users (mode %o); refusing to run it as root", path, st.Mode().Perm())
	}
	return os.ReadFile(path)
}

func writeFile(path string, data []byte, mode os.FileMode) error {
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, mode)
	if err != nil {
		return err
	}
	if _, err := f.Write(data); err != nil {
		f.Close()
		return err
	}
	if err := f.Sync(); err != nil {
		f.Close()
		return err
	}
	return f.Close()
}

func envMap(env []string) map[string]string {
	m := make(map[string]string, len(env))
	for _, kv := range env {
		if i := strings.IndexByte(kv, '='); i > 0 {
			m[kv[:i]] = kv[i+1:]
		}
	}
	return m
}

func boolFlag(v bool) string {
	if v {
		return "1"
	}
	return "0"
}

// Drain is used by tests to read a pipe fully.
func Drain(r io.Reader) string {
	b, _ := io.ReadAll(bufio.NewReader(r))
	return string(b)
}
