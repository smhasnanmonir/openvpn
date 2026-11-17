//go:build windows

package serviceinstall

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/smhasnanmonir/openvpn/desktop/internal/serviceproto"
)

func TestWindowsTokenACLs(t *testing.T) {
	dir := t.TempDir()
	helper := filepath.Join(dir, "helper.exe")
	if err := os.WriteFile(helper, []byte("binary"), 0o755); err != nil {
		t.Fatal(err)
	}
	system := filepath.Join(dir, "system", "service.json")
	user := filepath.Join(dir, "user", "service.json")
	t.Setenv("OPENVPN_DESKTOP_SYSTEM_CONFIG", system)
	opts, err := normalize(Options{HelperPath: helper, UserConfig: user})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := ensureConfig(opts); err != nil {
		t.Fatal(err)
	}
	if err := protectConfigFiles(system, user, ""); err != nil {
		t.Fatal(err)
	}
	for _, p := range []string{system, user} {
		if _, err := serviceproto.Load(p); err != nil {
			t.Fatalf("%s: %v", p, err)
		}
	}
}

func TestInstalledHelperPathInProgramFiles(t *testing.T) {
	p, err := installedHelperPath()
	if err != nil {
		t.Fatal(err)
	}
	if filepath.Base(p) != "openvpn-desktop-helper.exe" {
		t.Fatalf("path = %q", p)
	}
}
