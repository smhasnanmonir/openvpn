package appui

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"github.com/smhasnanmonir/openvpn/desktop/internal/ovpn"
	"github.com/smhasnanmonir/openvpn/desktop/internal/perapp"
	"github.com/smhasnanmonir/openvpn/desktop/internal/settings"
	"github.com/smhasnanmonir/openvpn/desktop/internal/vault"
)

func TestPerAppSelectionSaves(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("per-app split tunneling is Linux-only")
	}
	dir := t.TempDir()
	path := filepath.Join(dir, "vault.ovpnx")
	v, err := vault.Create(path, "test-password-123")
	if err != nil {
		t.Fatal(err)
	}
	a := New(path, dir, ovpn.HelperInfo{})
	a.attach(v)
	t.Cleanup(a.svc.Close)

	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	exe = perapp.Canonical(exe)
	a.perAppMode = "Only selected apps use the VPN"
	a.refreshPerApps()
	found := false
	for i := range a.perAppEntries {
		if a.perAppEntries[i].Path == exe {
			a.perAppEntries[i].Selected = true
			found = true
		}
	}
	if !found {
		t.Fatalf("the test executable %q was not in the scanned list", exe)
	}
	a.saveSettings()
	if a.settingsError != "" {
		t.Fatal(a.settingsError)
	}
	got := a.svc.Settings()
	if got.PerAppMode != settings.PerAppInclude {
		t.Fatalf("mode = %q", got.PerAppMode)
	}
	selected := false
	for _, p := range got.PerAppExecutables {
		if p == exe {
			selected = true
		}
	}
	if !selected {
		t.Fatalf("selected executable %q not saved: %v", exe, got.PerAppExecutables)
	}
}

func TestPerAppAddPathValidation(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "vault.ovpnx")
	v, err := vault.Create(path, "test-password-123")
	if err != nil {
		t.Fatal(err)
	}
	a := New(path, dir, ovpn.HelperInfo{})
	a.attach(v)
	t.Cleanup(a.svc.Close)

	a.perAppManual = "relative/path"
	a.addPerAppPath()
	if a.perAppError == "" {
		t.Fatal("a relative path was accepted")
	}
	a.perAppManual = "/does/not/exist"
	a.addPerAppPath()
	if a.perAppError == "" {
		t.Fatal("a missing executable was accepted")
	}
}
