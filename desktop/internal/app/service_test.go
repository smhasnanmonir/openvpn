package app

import (
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/smhasnanmonir/openvpn/desktop/internal/model"
	"github.com/smhasnanmonir/openvpn/desktop/internal/ovpn"
	"github.com/smhasnanmonir/openvpn/desktop/internal/settings"
	"github.com/smhasnanmonir/openvpn/desktop/internal/vault"
)

func certBlock(payload string) string {
	return "-----BEGIN CERTIFICATE-----\n" + base64.StdEncoding.EncodeToString([]byte(payload)) +
		"\n-----END CERTIFICATE-----"
}

func profileBody(withCA bool, extra string) string {
	body := "client\ndev tun\nproto udp\nremote vpn.example.com 1194\nauth-user-pass\n" + extra
	if withCA {
		body += "<ca>\n" + certBlock("test-ca") + "\n</ca>\n"
	}
	return body
}

func newTestService(t *testing.T) (*Service, string) {
	t.Helper()
	dir := t.TempDir()
	v, err := vault.Create(filepath.Join(dir, "vault.ovpnx"), "test-password-123")
	if err != nil {
		t.Fatal(err)
	}
	svc := New(v, dir, ovpn.HelperInfo{Available: false, Detail: "test helper"}, nil)
	t.Cleanup(svc.Close)
	return svc, dir
}

func writeFile(t *testing.T, dir, name, content string) string {
	t.Helper()
	path := filepath.Join(dir, name)
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestImportProfile(t *testing.T) {
	svc, dir := newTestService(t)
	path := writeFile(t, dir, "Provider.ovpn", profileBody(true, "cipher AES-256-CBC\n"))
	out := svc.ImportFiles([]string{path})
	if len(out) != 1 || !out[0].OK() || out[0].AlreadyImported {
		t.Fatalf("import outcome = %+v", out)
	}
	profiles := svc.Profiles()
	if len(profiles) != 1 || profiles[0].Name != "Provider" {
		t.Fatalf("profiles = %+v", profiles)
	}
	if !strings.Contains(profiles[0].Warning, "non-AEAD") {
		t.Fatalf("expected a legacy-cipher warning, got %q", profiles[0].Warning)
	}
	if got := svc.Snapshot().ProfileID; got != out[0].ProfileID {
		t.Fatalf("selected profile = %q, want %q", got, out[0].ProfileID)
	}
	stored, ok := svc.Profile(out[0].ProfileID)
	if !ok || !strings.Contains(stored.Config, "remote-cert-tls server") {
		t.Fatalf("stored profile was not hardened: %+v", stored)
	}
	if strings.Contains(stored.Config, "auth-user-pass") {
		t.Fatal("auth-user-pass should be supplied by the client, not stored in the profile")
	}

	again := svc.ImportFiles([]string{path})
	if len(again) != 1 || !again[0].AlreadyImported {
		t.Fatalf("re-import = %+v", again)
	}
	if len(svc.Profiles()) != 1 {
		t.Fatal("re-import duplicated the profile")
	}
}

func TestImportRejectsUntrustedProfile(t *testing.T) {
	svc, dir := newTestService(t)
	path := writeFile(t, dir, "untrusted.ovpn", profileBody(false, ""))
	out := svc.ImportFiles([]string{path})
	if len(out) != 1 || out[0].OK() || !strings.Contains(out[0].Error, "no CA") {
		t.Fatalf("untrusted import = %+v", out)
	}
}

func TestImportInlinesCompanionFiles(t *testing.T) {
	svc, dir := newTestService(t)
	profilePath := writeFile(t, dir, "inline.ovpn",
		"client\ndev tun\nremote vpn.example.com 1194\nauth-user-pass\nca ca.crt\n")
	caPath := writeFile(t, dir, "ca.crt", certBlock("companion-ca")+"\n")
	out := svc.ImportFiles([]string{profilePath, caPath})
	if len(out) != 1 || !out[0].OK() {
		t.Fatalf("import = %+v", out)
	}
	stored, _ := svc.Profile(out[0].ProfileID)
	if !strings.Contains(stored.Config, "<ca>") || strings.Contains(stored.Config, "ca ca.crt") {
		t.Fatalf("companion was not inlined:\n%s", stored.Config)
	}

	missing := writeFile(t, dir, "missing.ovpn",
		"client\ndev tun\nremote vpn.example.com 1194\nauth-user-pass\nca absent.crt\n")
	out = svc.ImportFiles([]string{missing})
	if len(out) != 1 || out[0].OK() || !strings.Contains(out[0].Error, "not selected") {
		t.Fatalf("missing companion import = %+v", out)
	}
}

func TestDeleteProfile(t *testing.T) {
	svc, dir := newTestService(t)
	out := svc.ImportFiles([]string{writeFile(t, dir, "p.ovpn", profileBody(true, ""))})
	if len(out) != 1 || !out[0].OK() {
		t.Fatalf("import = %+v", out)
	}
	if err := svc.SetCredentials(out[0].ProfileID, model.Credentials{Username: "u", Password: "p"}); err != nil {
		t.Fatal(err)
	}
	if err := svc.DeleteProfile(out[0].ProfileID); err != nil {
		t.Fatal(err)
	}
	if len(svc.Profiles()) != 0 {
		t.Fatal("profile was not deleted")
	}
	if !svc.Credentials(out[0].ProfileID).IsEmpty() {
		t.Fatal("credentials were not deleted")
	}
	if svc.Snapshot().ProfileID != "" {
		t.Fatal("snapshot still points at the deleted profile")
	}
}

func TestConnectPolicyErrorsAreSynchronous(t *testing.T) {
	svc, dir := newTestService(t)
	out := svc.ImportFiles([]string{writeFile(t, dir, "p.ovpn", profileBody(true, ""))})
	if len(out) != 1 || !out[0].OK() {
		t.Fatalf("import = %+v", out)
	}
	if err := svc.Connect(out[0].ProfileID, model.Credentials{}); err == nil ||
		!strings.Contains(err.Error(), "username and password") {
		t.Fatalf("expected a credentials error, got %v", err)
	}

	cbc := svc.ImportFiles([]string{writeFile(t, dir, "cbc.ovpn", profileBody(true, "data-ciphers AES-256-CBC\n"))})
	if len(cbc) != 1 || !cbc[0].OK() {
		t.Fatalf("CBC import = %+v", cbc)
	}
	if err := svc.UpdateSettings(func(s *settings.Settings) error {
		s.Cipher = settings.CipherAEADOnly
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	if err := svc.Connect(cbc[0].ProfileID, model.Credentials{Username: "u", Password: "p"}); err == nil ||
		!strings.Contains(err.Error(), "non-AEAD") {
		t.Fatalf("expected an AEAD-only policy error, got %v", err)
	}
}

func TestSettingsValidation(t *testing.T) {
	svc, _ := newTestService(t)
	if err := svc.UpdateSettings(func(s *settings.Settings) error {
		s.SplitTunnel = settings.SplitExclude
		s.SplitTunnelCIDRs = "not-a-cidr"
		return nil
	}); err == nil {
		t.Fatal("expected invalid CIDR to be rejected")
	}
	if err := svc.UpdateSettings(func(s *settings.Settings) error {
		s.SplitTunnel = settings.SplitExclude
		s.SplitTunnelCIDRs = "10.0.0.0/8, 192.168.1.5"
		return nil
	}); err != nil {
		t.Fatalf("valid CIDRs rejected: %v", err)
	}
}

func TestPortalProbe(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()
	svc, _ := newTestService(t)
	if err := svc.UpdateSettings(func(s *settings.Settings) error {
		s.PortalProbeURL = srv.URL
		s.AutoPortalDetect = false
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	svc.CheckPortal()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if svc.Portal().Status == model.PortalInternet {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("portal state = %+v", svc.Portal())
}

func TestPortalDetected(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/signin", http.StatusFound)
	}))
	defer srv.Close()
	svc, _ := newTestService(t)
	if err := svc.UpdateSettings(func(s *settings.Settings) error {
		s.PortalProbeURL = srv.URL
		s.AutoPortalDetect = false
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	svc.CheckPortal()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		st := svc.Portal()
		if st.Status == model.PortalDetected {
			if st.URL != srv.URL+"/signin" {
				t.Fatalf("portal URL = %q", st.URL)
			}
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("portal state = %+v", svc.Portal())
}

func TestPortalPauseRequiresSessionAndDisconnectClears(t *testing.T) {
	svc, _ := newTestService(t)
	if err := svc.PauseForPortal(); err == nil {
		t.Fatal("pausing without a session should fail")
	}
	svc.mu.Lock()
	svc.snap.PausedForPortal = true
	svc.mu.Unlock()
	svc.Disconnect()
	if svc.Snapshot().PausedForPortal {
		t.Fatal("Disconnect did not clear the paused flag")
	}
}

func TestResumeFromPortalRequiresProfile(t *testing.T) {
	svc, _ := newTestService(t)
	if err := svc.ResumeFromPortal(); err == nil {
		t.Fatal("resuming without a profile should fail")
	}
}

func TestFatalConnectErrorClassification(t *testing.T) {
	for _, msg := range []string{"the privileged helper is not installed", "no CA certificate", "AEAD-only"} {
		if !fatalConnectError(msg) {
			t.Fatalf("%q should be fatal", msg)
		}
	}
	if fatalConnectError("connection timed out") {
		t.Fatal("a timeout should be retried")
	}
}
