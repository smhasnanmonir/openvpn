package vault

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/smhasnanmonir/openvpn/desktop/internal/model"
	"github.com/smhasnanmonir/openvpn/desktop/internal/settings"
)

func testProfile(id string) model.StoredProfile {
	return model.StoredProfile{
		Summary: model.ProfileSummary{
			ID:        id,
			Name:      "Test VPN",
			Emoji:     "🔒",
			CreatedAt: time.Now(),
			UpdatedAt: time.Now(),
		},
		Config: "client\ndev tun\nremote vpn.example.com 1194\n<ca>\nX\n</ca>\n",
	}
}

func TestVaultRoundTrip(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "vault.ovpnx")
	v, err := Create(path, "correct horse battery")
	if err != nil {
		t.Fatal(err)
	}
	st, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if st.Mode().Perm() != 0o600 {
		t.Fatalf("vault mode = %o, want 600", st.Mode().Perm())
	}
	if err := v.AddProfile(testProfile("p1")); err != nil {
		t.Fatal(err)
	}
	if err := v.SetCredentials("p1", model.Credentials{Username: "alice", Password: "s3cret"}); err != nil {
		t.Fatal(err)
	}
	if err := v.UpdateSettings(func(s *settings.Settings) error {
		s.RequireTunnelDNS = true
		s.FallbackDNS = "1.1.1.1, 9.9.9.9"
		return nil
	}); err != nil {
		t.Fatal(err)
	}

	opened, err := Open(path, "correct horse battery")
	if err != nil {
		t.Fatal(err)
	}
	profiles, err := opened.Profiles()
	if err != nil || len(profiles) != 1 || profiles[0].ID != "p1" {
		t.Fatalf("profiles = %+v, err = %v", profiles, err)
	}
	creds := opened.Credentials("p1")
	if creds.Username != "alice" || creds.Password != "s3cret" {
		t.Fatalf("credentials = %+v", creds)
	}
	if got := opened.Settings().FallbackDNS; got != "1.1.1.1, 9.9.9.9" {
		t.Fatalf("fallback DNS = %q", got)
	}
	if opened.Selected() != "p1" {
		t.Fatalf("selected = %q", opened.Selected())
	}
}

func TestVaultWrongPasswordAndLock(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "vault.ovpnx")
	if _, err := Create(path, "a-good-password"); err != nil {
		t.Fatal(err)
	}
	if _, err := Open(path, "the-wrong-password"); !errors.Is(err, ErrWrongPassword) {
		t.Fatalf("expected ErrWrongPassword, got %v", err)
	}
	if _, err := Open(filepath.Join(dir, "missing"), "x"); !errors.Is(err, ErrNotInitialized) {
		t.Fatalf("expected ErrNotInitialized, got %v", err)
	}
	if _, err := Create(path, "another-good-password"); !errors.Is(err, ErrExists) {
		t.Fatalf("expected ErrExists, got %v", err)
	}
}

func TestVaultChangePassword(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "vault.ovpnx")
	v, err := Create(path, "first-password")
	if err != nil {
		t.Fatal(err)
	}
	if err := v.ChangePassword("first-password", "second-password"); err != nil {
		t.Fatal(err)
	}
	if _, err := Open(path, "first-password"); !errors.Is(err, ErrWrongPassword) {
		t.Fatalf("old password still works: %v", err)
	}
	if _, err := Open(path, "second-password"); err != nil {
		t.Fatalf("new password does not work: %v", err)
	}
}

func TestVaultLockAndDelete(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "vault.ovpnx")
	v, err := Create(path, "a-good-password")
	if err != nil {
		t.Fatal(err)
	}
	if err := v.AddProfile(testProfile("p1")); err != nil {
		t.Fatal(err)
	}
	if err := v.SetCredentials("p1", model.Credentials{Username: "a"}); err != nil {
		t.Fatal(err)
	}
	if err := v.DeleteProfile("p1"); err != nil {
		t.Fatal(err)
	}
	if _, ok := v.Profile("p1"); ok {
		t.Fatal("profile still exists")
	}
	if !v.Credentials("p1").IsEmpty() {
		t.Fatal("credentials were not deleted with the profile")
	}
	v.Lock()
	if _, err := v.Profiles(); !errors.Is(err, ErrLocked) {
		t.Fatalf("expected ErrLocked, got %v", err)
	}
}

func TestVaultRejectsShortPassword(t *testing.T) {
	dir := t.TempDir()
	if _, err := Create(filepath.Join(dir, "vault.ovpnx"), "short"); err == nil {
		t.Fatal("expected a short-password error")
	}
}
