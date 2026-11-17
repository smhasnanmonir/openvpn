package main

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestResolveDataDirHonorsEnv(t *testing.T) {
	t.Setenv("OPENVPN_DESKTOP_DATA_DIR", "/tmp/openvpn-desktop-test")
	if got := resolveDataDir(); got != "/tmp/openvpn-desktop-test" {
		t.Fatalf("resolveDataDir() = %q", got)
	}
}

func TestDirForExecutableResolvesSymlink(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("symlink privileges")
	}
	real := filepath.Join(t.TempDir(), "opt", "app")
	if err := os.MkdirAll(filepath.Dir(real), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(real, []byte("binary"), 0o755); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(t.TempDir(), "bin", "app")
	if err := os.MkdirAll(filepath.Dir(link), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(real, link); err != nil {
		t.Skipf("symlinks are unavailable: %v", err)
	}
	if got := dirForExecutable(link); got != filepath.Dir(real) {
		t.Fatalf("dir = %q, want %q", got, filepath.Dir(real))
	}
}
