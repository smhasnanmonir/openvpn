package serviceproto

import (
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestConfigRoundTrip(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "service.json")
	token, err := NewToken()
	if err != nil {
		t.Fatal(err)
	}
	c := Config{Addr: ListenAddr, Token: token}
	if err := Save(path, c); err != nil {
		t.Fatal(err)
	}
	got, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if got != c {
		t.Fatalf("got %+v, want %+v", got, c)
	}
	if runtime.GOOS != "windows" {
		st, _ := os.Stat(path)
		if st.Mode().Perm() != 0o600 {
			t.Fatalf("mode = %o", st.Mode().Perm())
		}
	}
}

func TestConfigValidation(t *testing.T) {
	token, _ := NewToken()
	good := Config{Addr: ListenAddr, Token: token}
	if err := good.Validate(); err != nil {
		t.Fatal(err)
	}
	for _, bad := range []Config{
		{Addr: ListenAddr, Token: ""},
		{Addr: ListenAddr, Token: "short"},
		{Addr: "0.0.0.0:51940", Token: token},
		{Addr: "not-an-address", Token: token},
	} {
		if err := bad.Validate(); err == nil {
			t.Fatalf("accepted %+v", bad)
		}
	}
}

func TestLoadRejectsInsecureMode(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Unix permission check")
	}
	dir := t.TempDir()
	path := filepath.Join(dir, "service.json")
	token, _ := NewToken()
	data, _ := json.Marshal(Config{Addr: ListenAddr, Token: token})
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(path); err == nil || !strings.Contains(err.Error(), "readable") {
		t.Fatalf("expected a permission error, got %v", err)
	}
}

func TestUserPathContainsService(t *testing.T) {
	p, err := UserPath()
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(p, "openvpn-desktop") || !strings.HasSuffix(p, "service.json") {
		t.Fatalf("path = %q", p)
	}
}
