package autostart

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestInstallAndRemove(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", dir)
	exe := filepath.Join(dir, "OpenVPN Desktop")
	if err := os.WriteFile(exe, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	path, err := Install(exe)
	if err != nil {
		t.Fatal(err)
	}
	if path != filepath.Join(dir, "autostart", "openvpn-desktop.desktop") {
		t.Fatalf("path = %q", path)
	}
	if !Installed() {
		t.Fatal("entry was not installed")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	text := string(data)
	if !strings.Contains(text, `Exec="`+exe+`" --autoconnect`) {
		t.Fatalf("Exec line missing or unquoted:\n%s", text)
	}
	if !strings.Contains(text, "X-GNOME-Autostart-enabled=true") {
		t.Fatalf("missing autostart marker:\n%s", text)
	}
	if err := Remove(); err != nil {
		t.Fatal(err)
	}
	if Installed() {
		t.Fatal("entry was not removed")
	}
}

func TestDesktopEntryRejectsRelativePath(t *testing.T) {
	if _, err := DesktopEntry("openvpn-desktop"); err == nil {
		t.Fatal("expected a relative-path error")
	}
	if _, err := DesktopEntry("/tmp/bad\npath"); err == nil {
		t.Fatal("expected a newline error")
	}
}

func TestExecQuoting(t *testing.T) {
	got := quoteExec(`/opt/My "VPN"/bin`)
	want := `"/opt/My \"VPN\"/bin"`
	if got != want {
		t.Fatalf("quoteExec = %q, want %q", got, want)
	}
}
