package ovpn

import (
	"os"
	"path/filepath"
	"testing"
)

func TestModeFromMarker(t *testing.T) {
	dir := t.TempDir()
	helper := filepath.Join(dir, "openvpn-desktop-helper")
	if err := os.WriteFile(helper, []byte("x"), 0o755); err != nil {
		t.Fatal(err)
	}
	cases := map[string]PrivilegeMode{
		"polkit\n": PrivilegePkexec,
		"pkexec":   PrivilegePkexec,
		"sudo\n":   PrivilegeSudo,
		"setuid\n": PrivilegeDirect,
		"direct":   PrivilegeDirect,
	}
	for content, want := range cases {
		if err := os.WriteFile(filepath.Join(dir, "privilege-mode"), []byte(content), 0o444); err != nil {
			t.Fatal(err)
		}
		got, ok := modeFromMarker(helper)
		if !ok || got != want {
			t.Fatalf("modeFromMarker(%q) = %q,%v; want %q", content, got, ok, want)
		}
	}
	if err := os.WriteFile(filepath.Join(dir, "privilege-mode"), []byte("nonsense"), 0o444); err != nil {
		t.Fatal(err)
	}
	if _, ok := modeFromMarker(helper); ok {
		t.Fatal("an unknown marker must be ignored")
	}
	if _, ok := modeFromMarker(filepath.Join(dir, "missing")); ok {
		t.Fatal("a missing marker must be ignored")
	}
}
