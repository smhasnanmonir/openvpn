package serviceinstall

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/smhasnanmonir/openvpn/desktop/internal/serviceproto"
)

func TestNormalizeAndCopy(t *testing.T) {
	dir := t.TempDir()
	src := filepath.Join(dir, "helper")
	if err := os.WriteFile(src, []byte("binary"), 0o755); err != nil {
		t.Fatal(err)
	}
	user := filepath.Join(dir, "user", "service.json")
	opts, err := normalize(Options{HelperPath: src, UserConfig: user})
	if err != nil {
		t.Fatal(err)
	}
	if opts.HelperPath != src || opts.UserConfig != user || opts.ListenAddr != serviceproto.ListenAddr {
		t.Fatalf("opts = %+v", opts)
	}
	dst := filepath.Join(dir, "installed", "helper")
	if err := copyFile(src, dst, 0o700); err != nil {
		t.Fatal(err)
	}
	st, err := os.Stat(dst)
	if err != nil {
		t.Fatal(err)
	}
	if st.Mode().Perm() != 0o700 {
		t.Fatalf("mode = %o", st.Mode().Perm())
	}
	data, _ := os.ReadFile(dst)
	if string(data) != "binary" {
		t.Fatalf("data = %q", data)
	}
}

func TestEnsureConfigReusesSystemToken(t *testing.T) {
	dir := t.TempDir()
	system := filepath.Join(dir, "system", "service.json")
	user := filepath.Join(dir, "user", "service.json")
	t.Setenv("OPENVPN_DESKTOP_SYSTEM_CONFIG", system)
	opts := Options{UserConfig: user, ListenAddr: serviceproto.ListenAddr}
	first, err := ensureConfig(opts)
	if err != nil {
		t.Fatal(err)
	}
	second, err := ensureConfig(opts)
	if err != nil {
		t.Fatal(err)
	}
	if first.Token != second.Token || first.Addr != second.Addr {
		t.Fatalf("token changed: %+v -> %+v", first, second)
	}
	got, err := serviceproto.Load(user)
	if err != nil {
		t.Fatal(err)
	}
	if got != first {
		t.Fatalf("user config = %+v, want %+v", got, first)
	}
}

func TestEnsureConfigRepairsInvalidSystemFile(t *testing.T) {
	dir := t.TempDir()
	system := filepath.Join(dir, "system", "service.json")
	user := filepath.Join(dir, "user", "service.json")
	t.Setenv("OPENVPN_DESKTOP_SYSTEM_CONFIG", system)
	if err := os.MkdirAll(filepath.Dir(system), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(system, []byte("{bad"), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := ensureConfig(Options{UserConfig: user, ListenAddr: serviceproto.ListenAddr})
	if err != nil {
		t.Fatal(err)
	}
	if err := cfg.Validate(); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(system); err != nil {
		t.Fatal(err)
	}
}

func TestVersionFile(t *testing.T) {
	dir := t.TempDir()
	if err := writeVersion(dir, "1.2.3"); err != nil {
		t.Fatal(err)
	}
	if got := readVersion(filepath.Join(dir, "helper.version")); got != "1.2.3" {
		t.Fatalf("version = %q", got)
	}
}
