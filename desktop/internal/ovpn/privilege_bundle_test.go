//go:build linux

package ovpn

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestFindHelperReportsBundledResources(t *testing.T) {
	dir := t.TempDir()
	helper := filepath.Join(dir, "openvpn-desktop-helper")
	if err := os.WriteFile(helper, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	info := FindHelper(dir, dir)
	if info.SelfDir != dir || info.ResourcesDir != dir {
		t.Fatalf("paths = %+v", info)
	}
	if info.BundledPath != helper {
		t.Fatalf("bundled = %q, want %q", info.BundledPath, helper)
	}
}

func TestInstallHelperNeedsPkexec(t *testing.T) {
	dir := t.TempDir()
	helper := filepath.Join(dir, "openvpn-desktop-helper")
	if err := os.WriteFile(helper, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", dir) // no pkexec
	info := FindHelper(dir, dir)
	err := InstallHelper(info)
	if err == nil || !strings.Contains(strings.ToLower(err.Error()), "installer") && !strings.Contains(strings.ToLower(err.Error()), "root") {
		t.Fatalf("expected an install refusal, got %v", err)
	}
}

func TestInstallHelperUsesPkexecForRootOwnedBundle(t *testing.T) {
	if os.Geteuid() != 0 {
		t.Skip("needs a root-owned bundled helper")
	}
	dir := t.TempDir()
	helper := filepath.Join(dir, "openvpn-desktop-helper")
	if err := os.WriteFile(helper, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	logPath := filepath.Join(dir, "pkexec.log")
	pkexec := filepath.Join(dir, "pkexec")
	script := "#!/bin/sh\nprintf '%s\\n' \"$*\" > " + logPath + "\n"
	if err := os.WriteFile(pkexec, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", dir)
	info := FindHelper(dir, dir)
	if err := InstallHelper(info); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(logPath)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(data), "install") {
		t.Fatalf("pkexec args = %q", data)
	}
}

func TestFindHelperRefusesUserOwnedHelper(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("the test file is root-owned when running as root")
	}
	dir := t.TempDir()
	helper := filepath.Join(dir, "openvpn-desktop-helper")
	if err := os.WriteFile(helper, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	info := FindHelper(dir, dir)
	if info.Available {
		t.Fatalf("accepted a user-owned helper: %+v", info)
	}
	if !strings.Contains(strings.ToLower(info.Detail), "user-writable") {
		t.Fatalf("detail = %q", info.Detail)
	}
}

func TestHelperVersionMismatch(t *testing.T) {
	dir := t.TempDir()
	helper := filepath.Join(dir, "openvpn-desktop-helper")
	if err := os.WriteFile(helper, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "helper.version"), []byte("0.9.0\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	SetAppVersion("1.0.0")
	t.Cleanup(func() { SetAppVersion("") })
	info := FindHelper(dir, dir)
	if info.InstalledVersion != "0.9.0" {
		t.Fatalf("installed version = %q", info.InstalledVersion)
	}
	if !info.VersionMismatch {
		t.Fatalf("version mismatch was not detected: %+v", info)
	}
}
