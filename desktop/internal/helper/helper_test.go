package helper

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestKillSwitchRules(t *testing.T) {
	rules := KillSwitchRules([]string{"203.0.113.5", "2606:4700:4700::1111", "not-an-ip"}, false)
	for _, want := range []string{
		"table inet openvpn_desktop_ks",
		"policy drop",
		`oif "lo" accept`,
		`oifname "tun*" accept`,
		"udp sport 68 udp dport 67 accept",
		"ip daddr { 203.0.113.5 } accept",
		"ip6 daddr { 2606:4700:4700::1111 } accept",
	} {
		if !strings.Contains(rules, want) {
			t.Fatalf("kill-switch rules missing %q:\n%s", want, rules)
		}
	}
	if strings.Contains(rules, "not-an-ip") {
		t.Fatal("non-IP was included")
	}
}

func TestKillSwitchAllowsPerAppMark(t *testing.T) {
	rules := KillSwitchRules([]string{"203.0.113.5"}, true)
	if !strings.Contains(rules, "meta mark 0x1 accept") {
		t.Fatalf("per-app mark is not allowed:\n%s", rules)
	}
	if strings.Contains(KillSwitchRules(nil, false), "meta mark 0x1 accept") {
		t.Fatal("the mark allow rule must only be present for per-app split")
	}
}

func TestParseRunPerApp(t *testing.T) {
	args := []string{
		"--config", "/tmp/profile.ovpn",
		"--management", "127.0.0.1:7505",
		"--management-password-file", "/tmp/pass",
		"--per-app-mode", "exclude",
		"--per-app-exec", filepath.Join(t.TempDir(), "firefox"),
		"--per-app-exec", filepath.Join(t.TempDir(), "app"),
	}
	opt, err := parseRun(args)
	if err != nil {
		t.Fatal(err)
	}
	if opt.perAppMode != "exclude" || len(opt.perAppExec) != 2 {
		t.Fatalf("options = %+v", opt)
	}
	bad := [][]string{
		{"--config", "/tmp/x", "--management", "127.0.0.1:7505", "--management-password-file", "/tmp/p", "--per-app-mode", "include"},
		{"--config", "/tmp/x", "--management", "127.0.0.1:7505", "--management-password-file", "/tmp/p", "--per-app-mode", "sideways", "--per-app-exec", "/usr/bin/x"},
		{"--config", "/tmp/x", "--management", "127.0.0.1:7505", "--management-password-file", "/tmp/p", "--per-app-mode", "exclude", "--per-app-exec", "relative"},
	}
	for _, a := range bad {
		if _, err := parseRun(a); err == nil {
			t.Fatalf("expected an error for %v", a)
		}
	}
}

func TestParseRunValidation(t *testing.T) {
	valid := []string{
		"--config", "/tmp/profile.ovpn",
		"--management", "127.0.0.1:7505",
		"--management-password-file", "/tmp/pass",
		"--require-dns", "1",
		"--fallback-dns", "1.1.1.1,9.9.9.9",
		"--verb", "4",
	}
	opt, err := parseRun(valid)
	if err != nil {
		t.Fatal(err)
	}
	if !opt.requireDNS || opt.mgmtAddr != "127.0.0.1:7505" || opt.verb != 4 {
		t.Fatalf("unexpected options %+v", opt)
	}

	bad := [][]string{
		{"--config", "/tmp/x", "--management", "0.0.0.0:7505", "--management-password-file", "/tmp/p"},
		{"--config", "/tmp/x", "--management", "127.0.0.1:0", "--management-password-file", "/tmp/p"},
		{"--config", "/tmp/x", "--management", "127.0.0.1:7505", "--management-password-file", "/tmp/p", "--fallback-dns", "not-an-ip"},
		{"--config", "/tmp/x", "--management", "127.0.0.1:7505", "--management-password-file", "/tmp/p", "--bogus", "1"},
		{"--management", "127.0.0.1:7505"},
	}
	for _, args := range bad {
		if _, err := parseRun(args); err == nil {
			t.Fatalf("expected an error for %v", args)
		}
	}
}

func TestPinRemoteHostsWithIPs(t *testing.T) {
	cfg := "client\nremote 203.0.113.5 1194 udp\nremote 2606:4700:4700::1111 443 tcp\n"
	out, ips, err := pinRemoteHosts(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if out != cfg {
		t.Fatalf("IP-only config changed:\n%s", out)
	}
	if len(ips) != 2 {
		t.Fatalf("ips = %v", ips)
	}
}

func TestAppendSetenvAndStripManaged(t *testing.T) {
	cfg := "client\nsetenv OPENVPN_DESKTOP_REQUIRE_DNS 0\nsetenv FOO bar\n"
	stripped := stripManagedSetenv(cfg)
	if strings.Contains(stripped, "OPENVPN_DESKTOP_REQUIRE_DNS") {
		t.Fatal("managed setenv was not stripped")
	}
	if !strings.Contains(stripped, "setenv FOO bar") {
		t.Fatal("unrelated setenv was removed")
	}
	out := appendSetenv(stripped, "OPENVPN_DESKTOP_REQUIRE_DNS", "1", "OPENVPN_DESKTOP_FALLBACK_DNS", "1.1.1.1")
	if !strings.Contains(out, "setenv OPENVPN_DESKTOP_REQUIRE_DNS 1") ||
		!strings.Contains(out, "setenv OPENVPN_DESKTOP_FALLBACK_DNS 1.1.1.1") {
		t.Fatalf("setenv lines missing:\n%s", out)
	}
}

func TestFindTrustedRejectsMissing(t *testing.T) {
	if _, err := findTrusted("this-binary-does-not-exist-hopefully"); err == nil {
		t.Fatal("expected a missing-binary error")
	}
}

func TestReadBoundedRegularRejectsSymlinkAndWritable(t *testing.T) {
	dir := t.TempDir()
	real := filepath.Join(dir, "real.ovpn")
	if err := os.WriteFile(real, []byte("client\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := readBoundedRegular(real, 1<<20); err != nil {
		t.Fatalf("a normal 0600 file was rejected: %v", err)
	}
	link := filepath.Join(dir, "link.ovpn")
	if err := os.Symlink(real, link); err != nil {
		t.Skipf("symlinks are unavailable: %v", err)
	}
	if _, err := readBoundedRegular(link, 1<<20); err == nil {
		t.Fatal("a symlink must be rejected")
	}
	writable := filepath.Join(dir, "writable.ovpn")
	if err := os.WriteFile(writable, []byte("client\n"), 0o666); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(writable, 0o666); err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS != "windows" {
		if _, err := readBoundedRegular(writable, 1<<20); err == nil {
			t.Fatal("a group/world-writable file must be rejected")
		}
	}
}
