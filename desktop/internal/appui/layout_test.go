package appui

import (
	"path/filepath"
	"runtime"
	"testing"

	"github.com/egoist/mygo/ui"

	"github.com/smhasnanmonir/openvpn/desktop/internal/ovpn"
	"github.com/smhasnanmonir/openvpn/desktop/internal/vault"
)

// TestContentIsInsideTheViewport catches the Flexbox trap where Fill() on a
// nested row/card consumes the whole window height and pushes the page content
// out of view.
func TestContentIsInsideTheViewport(t *testing.T) {
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

	const height = 780
	tt := ui.NewTester(a.View, 1100, height)

	assertVisible := func(label string) {
		t.Helper()
		rect, ok := tt.Find(label)
		if !ok {
			t.Fatalf("%q not found; texts: %v", label, tt.Texts())
		}
		if rect.W <= 0 || rect.H <= 0 {
			t.Fatalf("%q has zero size: %+v", label, rect)
		}
		if rect.Y < 0 || rect.Y+rect.H > float32(height) {
			t.Fatalf("%q is outside the viewport: %+v", label, rect)
		}
	}
	// The tab bar must stay near the top; a nested Fill() used to push it to
	// the bottom of the window.
	tab, ok := tt.Find("Home")
	if !ok || tab.Y > 200 {
		t.Fatalf("tab bar is not at the top: %+v (ok=%v)", tab, ok)
	}
	conn, ok := tt.Find("Connection")
	if !ok || conn.Y <= tab.Y {
		t.Fatalf("Home content is not below the tabs: tab=%+v connection=%+v", tab, conn)
	}
	assertVisible("Connection")
	assertVisible("Network / captive portal")
	assertVisible("Public IP address")
	assertVisible("Security notices")

	if err := tt.Click("Settings"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	assertVisible("Require tunnel DNS")
	if !tt.HasText("Captive portal") ||
		!tt.HasText("Start at login: not installed") ||
		!tt.HasText("Per-app split tunneling (Linux)") ||
		(runtime.GOOS == "linux" && !tt.HasText("Kill switch (nftables)")) ||
		(runtime.GOOS != "linux" && !tt.HasText("nftables kill switch is implemented for Linux only")) {
		t.Fatalf("new settings missing; texts: %v", tt.Texts())
	}

	if err := tt.Click("Logs"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	assertVisible("Filter logs")
}
