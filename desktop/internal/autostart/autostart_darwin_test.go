//go:build darwin

package autostart

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestDarwinLaunchAgent(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	exe := filepath.Join(home, "OpenVPN Desktop")
	if err := os.WriteFile(exe, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	path, err := Install(exe)
	if err != nil {
		t.Fatal(err)
	}
	if path != filepath.Join(home, "Library", "LaunchAgents", label+".plist") {
		t.Fatalf("path = %q", path)
	}
	if !Installed() {
		t.Fatal("LaunchAgent was not installed")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	text := string(data)
	if !strings.Contains(text, "<string>"+exe+"</string>") || !strings.Contains(text, "--autoconnect") {
		t.Fatalf("bad plist:\n%s", text)
	}
	if err := Remove(); err != nil {
		t.Fatal(err)
	}
	if Installed() {
		t.Fatal("LaunchAgent was not removed")
	}
}

func TestDarwinRejectsRelativePath(t *testing.T) {
	if _, err := Install("openvpn-desktop"); err == nil {
		t.Fatal("expected a relative-path error")
	}
}
