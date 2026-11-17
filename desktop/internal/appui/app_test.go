package appui

import (
	"encoding/base64"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"github.com/egoist/mygo/ui"

	"github.com/smhasnanmonir/openvpn/desktop/internal/ovpn"
	"github.com/smhasnanmonir/openvpn/desktop/internal/settings"
	"github.com/smhasnanmonir/openvpn/desktop/internal/vault"
)

func certBlock(payload string) string {
	return "-----BEGIN CERTIFICATE-----\n" + base64.StdEncoding.EncodeToString([]byte(payload)) +
		"\n-----END CERTIFICATE-----"
}

func writeProfile(t *testing.T, dir string) string {
	t.Helper()
	body := "client\ndev tun\nproto udp\nremote vpn.example.com 1194\nauth-user-pass\n<ca>\n" +
		certBlock("ui-test-ca") + "\n</ca>\n"
	path := filepath.Join(dir, "UI Test.ovpn")
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestUnlockViewRenders(t *testing.T) {
	dir := t.TempDir()
	a := New(filepath.Join(dir, "vault.ovpnx"), dir, ovpn.HelperInfo{})
	tt := ui.NewTester(a.View, 1000, 700)
	if !tt.HasText("OpenVPN Desktop") {
		t.Fatalf("title missing; texts: %v", tt.Texts())
	}
	if !tt.HasText("Create vault") {
		t.Fatalf("create-vault button missing; texts: %v", tt.Texts())
	}
}

func TestCreateVaultThroughViewAndShowTabs(t *testing.T) {
	dir := t.TempDir()
	a := New(filepath.Join(dir, "vault.ovpnx"), dir, ovpn.HelperInfo{})
	tt := ui.NewTester(a.View, 1100, 780)
	if err := tt.Click("Master password"); err != nil {
		t.Fatal(err)
	}
	tt.Type("test-password-123")
	if err := tt.Click("Confirm master password"); err != nil {
		t.Fatal(err)
	}
	tt.Type("test-password-123")
	if err := tt.Click("Create vault"); err != nil {
		t.Fatal(err)
	}
	if a.svc == nil {
		t.Fatalf("vault was not created; error=%q texts=%v", a.unlockError, tt.Texts())
	}
	if !tt.HasText("Profiles") || !tt.HasText("No profiles yet.") {
		t.Fatalf("profiles tab not shown; texts: %v", tt.Texts())
	}

	path := writeProfile(t, dir)
	out := a.svc.ImportFiles([]string{path})
	if len(out) != 1 || !out[0].OK() {
		t.Fatalf("import = %+v", out)
	}
	tt.Frame()
	if !tt.HasText("UI Test") {
		t.Fatalf("profile not rendered; texts: %v", tt.Texts())
	}

	if err := tt.Click("Settings"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if !tt.HasText("Require tunnel DNS") {
		t.Fatalf("settings not rendered; texts: %v", tt.Texts())
	}
	if runtime.GOOS == "linux" && !tt.HasText("Kill switch (nftables)") {
		t.Fatalf("kill-switch setting missing; texts: %v", tt.Texts())
	}

	if err := tt.Click("Home"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if !tt.HasText("Connection") {
		t.Fatalf("home not rendered; texts: %v", tt.Texts())
	}
}

func TestUnlockWrongPassword(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "vault.ovpnx")
	if _, err := vault.Create(path, "the-right-password"); err != nil {
		t.Fatal(err)
	}
	a := New(path, dir, ovpn.HelperInfo{})
	a.password = "the-wrong-password"
	a.submitUnlock()
	if a.svc != nil {
		t.Fatal("a wrong password unlocked the vault")
	}
	if !strings.Contains(a.unlockError, "wrong master password") {
		t.Fatalf("unlock error = %q", a.unlockError)
	}
}

func TestRuntimeWarningsRendered(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "vault.ovpnx")
	v, err := vault.Create(path, "test-password-123")
	if err != nil {
		t.Fatal(err)
	}
	a := New(path, dir, ovpn.HelperInfo{})
	a.attach(v)
	out := a.svc.ImportFiles([]string{writeProfile(t, dir)})
	if len(out) != 1 || !out[0].OK() {
		t.Fatalf("import = %+v", out)
	}
	if err := a.svc.UpdateSettings(func(s *settings.Settings) error {
		s.SplitTunnel = settings.SplitExclude
		s.SplitTunnelCIDRs = "10.0.0.0/8"
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	tt := ui.NewTester(a.View, 1100, 780)
	if !tt.HasText("Security notices") {
		t.Fatalf("warnings card missing; texts: %v", tt.Texts())
	}
	if !tt.HasText("The listed networks bypass the VPN. Their traffic is not protected and can expose your IP address.") {
		t.Fatalf("split-tunnel warning missing; texts: %v", tt.Texts())
	}
}
