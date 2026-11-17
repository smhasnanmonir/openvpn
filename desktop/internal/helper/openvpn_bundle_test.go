package helper

import (
	"os"
	"path/filepath"
	"testing"
)

func TestBundledOpenVPNSHA256(t *testing.T) {
	for _, arch := range []string{"amd64", "arm64"} {
		sum, ok := bundledOpenVPNSHA256(arch)
		if !ok {
			t.Fatalf("%s: no pinned installer", arch)
		}
		if len(sum) != 64 {
			t.Fatalf("%s: %q is not a sha256 hex digest", arch, sum)
		}
	}
	if _, ok := bundledOpenVPNSHA256("386"); ok {
		t.Fatal("an unsupported architecture reported a bundled installer")
	}
}

func TestBundledMSIPath(t *testing.T) {
	got := bundledMSIPath(filepath.Join("C:", "Programs", "OpenVPN Desktop"))
	want := filepath.Join("C:", "Programs", "OpenVPN Desktop", "openvpn-installer.msi")
	if got != want {
		t.Fatalf("bundledMSIPath = %q, want %q", got, want)
	}
}

func TestFileSHA256(t *testing.T) {
	path := filepath.Join(t.TempDir(), "input")
	// "abc" has a well-known digest, so this also pins the hex encoding.
	if err := os.WriteFile(path, []byte("abc"), 0o600); err != nil {
		t.Fatal(err)
	}
	got, err := fileSHA256(path)
	if err != nil {
		t.Fatal(err)
	}
	const want = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
	if got != want {
		t.Fatalf("fileSHA256 = %q, want %q", got, want)
	}
}

func TestCopyRegularFileRefusesExistingTarget(t *testing.T) {
	dir := t.TempDir()
	src := filepath.Join(dir, "src")
	if err := os.WriteFile(src, []byte("payload"), 0o600); err != nil {
		t.Fatal(err)
	}
	dst := filepath.Join(dir, "dst")
	if err := copyRegularFile(src, dst, 0o600); err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(dst)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != "payload" {
		t.Fatalf("copied %q", got)
	}
	// A staged copy must never overwrite an existing file: the destination is
	// the hash-verified installer.
	if err := copyRegularFile(src, dst, 0o600); err == nil {
		t.Fatal("copyRegularFile overwrote an existing file")
	}
}
