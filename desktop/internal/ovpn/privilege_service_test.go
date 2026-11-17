package ovpn

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"github.com/smhasnanmonir/openvpn/desktop/internal/serviceproto"
)

func TestFindServiceHelper(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "service.json")
	t.Setenv("OPENVPN_DESKTOP_USER_CONFIG", path)
	token, err := serviceproto.NewToken()
	if err != nil {
		t.Fatal(err)
	}
	if err := serviceproto.Save(path, serviceproto.Config{Addr: "127.0.0.1:51940", Token: token}); err != nil {
		t.Fatal(err)
	}
	info := findServiceHelper(".")
	if !info.Available || info.Mode != PrivilegeService || info.ServiceAddr != "127.0.0.1:51940" || info.ServiceToken != token {
		t.Fatalf("info = %+v", info)
	}
	if _, err := info.Command([]string{"run"}); err == nil {
		t.Fatal("a service helper must not build a local command")
	}
}

func TestFindServiceHelperMissing(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("OPENVPN_DESKTOP_USER_CONFIG", filepath.Join(dir, "service.json"))
	info := findServiceHelper(".")
	if info.Available {
		t.Fatalf("a missing service config was accepted: %+v", info)
	}
	if info.Detail == "" {
		t.Fatal("missing detail")
	}
}

func TestFindHelperUsesServiceEndpointEnv(t *testing.T) {
	t.Setenv("OPENVPN_DESKTOP_SERVICE_ADDR", "127.0.0.1:59999")
	t.Setenv("OPENVPN_DESKTOP_SERVICE_TOKEN", "0123456789abcdef0123456789abcdef")
	info := FindHelper(".")
	if !info.Available || info.Mode != PrivilegeService || info.ServiceAddr != "127.0.0.1:59999" {
		t.Fatalf("info = %+v", info)
	}
}

func TestServiceUserPathUnderConfig(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("XDG_CONFIG_HOME is Linux-specific; Windows and macOS use their own config directories")
	}
	dir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", dir)
	p, err := serviceproto.UserPath()
	if err != nil {
		t.Fatal(err)
	}
	if filepath.Dir(p) != filepath.Join(dir, "openvpn-desktop") {
		t.Fatalf("path = %q", p)
	}
	if _, err := os.Stat(p); !os.IsNotExist(err) {
		t.Fatalf("unexpected file %q", p)
	}
}
