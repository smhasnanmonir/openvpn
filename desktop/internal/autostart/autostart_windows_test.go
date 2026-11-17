//go:build windows

package autostart

import (
	"path/filepath"
	"strings"
	"testing"
)

func TestWindowsRunValue(t *testing.T) {
	exe := filepath.Join(t.TempDir(), "OpenVPN Desktop.exe")
	path, err := Install(exe)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(path, "CurrentVersion\\Run") {
		t.Fatalf("path = %q", path)
	}
	if !Installed() {
		t.Fatal("Run value was not installed")
	}
	if err := Remove(); err != nil {
		t.Fatal(err)
	}
	if Installed() {
		t.Fatal("Run value was not removed")
	}
}

func TestWindowsRejectsRelativePath(t *testing.T) {
	if _, err := Install("openvpn-desktop.exe"); err == nil {
		t.Fatal("expected a relative-path error")
	}
}
