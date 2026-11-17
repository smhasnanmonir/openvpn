package appui

import (
	"path/filepath"
	"testing"

	"github.com/egoist/mygo/ui"

	"github.com/smhasnanmonir/openvpn/desktop/internal/autostart"
	"github.com/smhasnanmonir/openvpn/desktop/internal/model"
	"github.com/smhasnanmonir/openvpn/desktop/internal/ovpn"
	"github.com/smhasnanmonir/openvpn/desktop/internal/settings"
	"github.com/smhasnanmonir/openvpn/desktop/internal/vault"
)

func TestShouldHideOnCloseRequiresTrayAndSetting(t *testing.T) {
	dir := t.TempDir()
	v, err := vault.Create(filepath.Join(dir, "vault.ovpnx"), "test-password-123")
	if err != nil {
		t.Fatal(err)
	}
	a := New(filepath.Join(dir, "vault.ovpnx"), dir, ovpn.HelperInfo{})
	a.attach(v)
	t.Cleanup(a.svc.Close)

	if a.ShouldHideOnClose() {
		t.Fatal("no tray and default settings must close")
	}
	a.SetTrayAvailable(true)
	if a.ShouldHideOnClose() {
		t.Fatal("minimize-to-tray is off by default")
	}
	if err := a.svc.UpdateSettings(func(s *settings.Settings) error {
		s.MinimizeToTray = true
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	if !a.ShouldHideOnClose() {
		t.Fatal("tray + minimize-to-tray should hide")
	}
	a.ForceQuit()
	if a.ShouldHideOnClose() {
		t.Fatal("a real quit must not be prevented")
	}
}

func TestInstallAutostartFromUI(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", dir)
	v, err := vault.Create(filepath.Join(dir, "vault.ovpnx"), "test-password-123")
	if err != nil {
		t.Fatal(err)
	}
	a := New(filepath.Join(dir, "vault.ovpnx"), dir, ovpn.HelperInfo{})
	a.attach(v)
	t.Cleanup(a.svc.Close)

	a.installAutostart()
	if a.autostartError != "" {
		t.Fatal(a.autostartError)
	}
	if !a.autostartInstalled || !autostart.Installed() {
		t.Fatal("autostart entry was not installed")
	}
	a.removeAutostart()
	if a.autostartError != "" || a.autostartInstalled || autostart.Installed() {
		t.Fatalf("autostart entry was not removed: error=%q installed=%v", a.autostartError, a.autostartInstalled)
	}
}

func TestPendingAutoConnectStartsSession(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "vault.ovpnx")
	v, err := vault.Create(path, "test-password-123")
	if err != nil {
		t.Fatal(err)
	}
	a := New(path, dir, ovpn.HelperInfo{})
	a.attach(v)
	t.Cleanup(a.svc.Close)
	out := a.svc.ImportFiles([]string{writeProfile(t, dir)})
	if len(out) != 1 || !out[0].OK() {
		t.Fatalf("import = %+v", out)
	}
	if err := a.svc.SetCredentials(out[0].ProfileID, model.Credentials{Username: "u", Password: "p"}); err != nil {
		t.Fatal(err)
	}
	a.pendingAutoConnect = true
	_ = ui.NewTester(a.View, 1100, 780)
	snap := a.svc.Snapshot()
	if snap.ProfileID != out[0].ProfileID {
		t.Fatalf("auto-connect did not select the profile: %+v", snap)
	}
	if snap.State == model.StateDisconnected {
		t.Fatalf("auto-connect did not start a session: %+v", snap)
	}
}
