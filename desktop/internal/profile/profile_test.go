package profile

import (
	"encoding/base64"
	"strings"
	"testing"

	"github.com/smhasnanmonir/openvpn/desktop/internal/settings"
)

func pemBlock(label, payload string) string {
	return "-----BEGIN " + label + "-----\n" +
		base64.StdEncoding.EncodeToString([]byte(payload)) + "\n" +
		"-----END " + label + "-----"
}

func caBlock(payload string) string {
	return "<ca>\n" + pemBlock("CERTIFICATE", payload) + "\n</ca>"
}

func baseProfile() string {
	return `client
dev tun
proto udp
remote vpn.example.com 1194
auth-user-pass
` + caBlock("ca-one") + `
`
}

func TestWithServerCertificateUsage(t *testing.T) {
	got := WithServerCertificateUsage(baseProfile())
	if !strings.Contains(got, "remote-cert-tls server") {
		t.Fatalf("expected remote-cert-tls server appended, got:\n%s", got)
	}
	// Idempotent.
	if again := WithServerCertificateUsage(got); again != got {
		t.Fatal("transform is not idempotent")
	}
	explicit := baseProfile() + "remote-cert-tls client\n"
	if out := WithServerCertificateUsage(explicit); out != explicit {
		t.Fatal("an explicit remote-cert directive must not be changed")
	}
	pinned := baseProfile() + "<peer-fingerprint>\nAA:BB\n</peer-fingerprint>\n"
	if out := WithServerCertificateUsage(pinned); out != pinned {
		t.Fatal("a peer-fingerprint profile must not get a second policy")
	}
}

func TestTrustPolicy(t *testing.T) {
	if d := ValidateTrust(baseProfile()); !d.Allowed {
		t.Fatalf("CA profile rejected: %s", d.Message)
	}
	noCA := "client\ndev tun\nremote vpn.example.com 1194\nauth-user-pass\n"
	if d := ValidateTrust(noCA); d.Allowed || !strings.Contains(d.Message, "no CA") {
		t.Fatalf("expected rejection without CA, got %+v", d)
	}
	fpOnly := "client\ndev tun\nremote vpn.example.com 1194\nauth-user-pass\n<peer-fingerprint>\nAA:BB\n</peer-fingerprint>\n"
	if d := ValidateTrust(fpOnly); d.Allowed || !strings.Contains(d.Message, "peer-fingerprint") {
		t.Fatalf("expected fingerprint-only rejection, got %+v", d)
	}
	tlsRemote := "client\ndev tun\nremote vpn.example.com 1194\nauth-user-pass\ntls-remote vpn.example.com\n"
	if d := ValidateTrust(tlsRemote); !d.Allowed {
		t.Fatalf("tls-remote profile rejected: %s", d.Message)
	}
	if !HasIgnoredPeerFingerprint(baseProfile() + "<peer-fingerprint>\nAA\n</peer-fingerprint>\n") {
		t.Fatal("expected peer-fingerprint to be detected")
	}
}

func TestCAFingerprintStableAndSensitive(t *testing.T) {
	a := baseProfile()
	b := baseProfile()
	if CAFingerprint(a) == "" {
		t.Fatal("expected a CA fingerprint")
	}
	if CAFingerprint(a) != CAFingerprint(b) {
		t.Fatal("same CA must produce the same fingerprint")
	}
	other := strings.ReplaceAll(baseProfile(), base64.StdEncoding.EncodeToString([]byte("ca-one")),
		base64.StdEncoding.EncodeToString([]byte("ca-two")))
	if CAFingerprint(other) == CAFingerprint(a) {
		t.Fatal("a different CA must change the fingerprint")
	}
	noCA := "client\ndev tun\nremote x 1194\n"
	if CAFingerprint(noCA) != "" {
		t.Fatal("no inline CA must produce no fingerprint")
	}
}

func TestLegacyCipherFindings(t *testing.T) {
	cfg := "cipher AES-256-CBC\ndata-ciphers AES-256-GCM:BF-CBC\n# cipher AES-128-CBC\n"
	findings := LegacyCipherFindings(cfg)
	if len(findings) != 2 {
		t.Fatalf("expected 2 findings, got %+v", findings)
	}
	if LegacyCipherFindings("data-ciphers AES-256-GCM:CHACHA20-POLY1305\n") != nil {
		t.Fatal("AEAD-only profile must have no findings")
	}
}

func TestAEADOnlyPolicy(t *testing.T) {
	cfg := "client\ndev tun\nremote x 1194\nauth-user-pass\ndata-ciphers AES-256-CBC:AES-256-GCM\n" + caBlock("ca") + "\n"
	if err := ValidateAEADOnly(cfg); err == nil || !strings.Contains(err.Error(), "AES-256-CBC") {
		t.Fatalf("expected AEAD-only validation error, got %v", err)
	}
	clean := WithAEADOnlyPushPolicy("client\ndev tun\nremote x 1194\n" + caBlock("ca") + "\n")
	if !strings.Contains(clean, `pull-filter reject "cipher"`) {
		t.Fatalf("missing cipher reject filter:\n%s", clean)
	}
	if !strings.Contains(clean, "data-ciphers AES-256-GCM") {
		t.Fatalf("missing AEAD data-ciphers:\n%s", clean)
	}
	if again := WithAEADOnlyPushPolicy(clean); again != clean {
		t.Fatal("AEAD transform is not idempotent")
	}
}

func TestStrictCompressionPolicy(t *testing.T) {
	cfg := "client\ncompress lz4\ncomp-lzo yes\npull-filter accept \"compress lz4\"\n" + caBlock("ca") + "\n"
	out := WithStrictCompressionPolicy(cfg)
	if strings.Contains(out, "\ncompress lz4") || strings.Contains(out, "\ncomp-lzo yes") {
		t.Fatalf("local compression directives were not removed:\n%s", out)
	}
	if !strings.Contains(out, "allow-compression no") || !strings.Contains(out, `pull-filter reject "compress"`) {
		t.Fatalf("strict policy lines missing:\n%s", out)
	}
	if again := WithStrictCompressionPolicy(out); again != out {
		t.Fatal("strict compression transform is not idempotent")
	}
}

func TestMTUOverrideIsIdempotent(t *testing.T) {
	cfg := "client\ndev tun\n"
	out := WithTunMtuOverride(cfg, 1400)
	if !strings.Contains(out, "tun-mtu 1400") || !strings.Contains(out, "pull-filter ignore tun-mtu") {
		t.Fatalf("MTU override missing:\n%s", out)
	}
	if again := WithTunMtuOverride(out, 1400); again != out {
		t.Fatal("MTU transform is not idempotent")
	}
}

func TestSanitizeConfigRemovesDangerousDirectives(t *testing.T) {
	cfg := `client
dev tun
remote vpn.example.com 1194
up /tmp/evil.sh
down /tmp/evil-down.sh
script-security 3
plugin /tmp/evil.so
config /tmp/evil.conf
management 0.0.0.0 7505
pull-filter accept "cipher AES-256-CBC"
setenv opt block-outside-dns
setenv CLIENT_CERT 0
` + caBlock("ca") + "\n"
	out, removed := SanitizeConfig(cfg)
	for _, banned := range []string{"up /tmp", "down /tmp", "plugin", "config /tmp", "management", "pull-filter", "setenv opt"} {
		if strings.Contains(out, banned) {
			t.Fatalf("dangerous directive %q survived:\n%s", banned, out)
		}
	}
	if !strings.Contains(out, "setenv CLIENT_CERT 0") {
		t.Fatal("a harmless setenv was removed")
	}
	if len(removed) == 0 {
		t.Fatal("expected removed directive names")
	}
}

func TestInlineExternalFiles(t *testing.T) {
	cfg := "client\ndev tun\nremote x 1194\nca ca.crt\ncert client.crt\nkey client.key\nauth-user-pass creds.txt\n"
	files := map[string][]byte{
		"ca.crt":     []byte(pemBlock("CERTIFICATE", "ca")),
		"client.crt": []byte(pemBlock("CERTIFICATE", "cert")),
		"client.key": []byte(pemBlock("PRIVATE KEY", "key")),
	}
	out, warnings, err := InlineExternalFiles(cfg, files)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out, "<ca>") || !strings.Contains(out, "<cert>") || !strings.Contains(out, "<key>") {
		t.Fatalf("inline blocks missing:\n%s", out)
	}
	if strings.Contains(out, "auth-user-pass") {
		t.Fatal("auth-user-pass file reference should be removed")
	}
	if len(warnings) == 0 {
		t.Fatal("expected inlining warnings")
	}
	if _, _, err := InlineExternalFiles(cfg, map[string][]byte{}); err == nil {
		t.Fatal("missing companion file must fail")
	}
}

func TestValidateNoExternalFiles(t *testing.T) {
	if err := ValidateNoExternalFiles("client\nca /etc/ssl/ca.crt\n"); err == nil {
		t.Fatal("expected an external file error")
	}
	if err := ValidateNoExternalFiles("client\n" + caBlock("ca") + "\n"); err != nil {
		t.Fatalf("inline CA rejected: %v", err)
	}
}

func TestParseMetadata(t *testing.T) {
	cfg := `client
dev tun
proto tcp-client
remote vpn.example.com 443 tcp
auth-user-pass
static-challenge "Enter token" 1
` + caBlock("ca") + "\n<cert>\n" + pemBlock("CERTIFICATE", "cert") + "\n</cert>\n<key>\n" + pemBlock("PRIVATE KEY", "key") + "\n</key>\n"
	res, err := Parse(cfg, "Provider.ovpn")
	if err != nil {
		t.Fatal(err)
	}
	md := res.Metadata
	if md.ServerHost != "vpn.example.com" || md.ServerPort != "443" {
		t.Fatalf("unexpected server %+v", md)
	}
	if md.Protocol != "tcp" {
		t.Fatalf("protocol = %q", md.Protocol)
	}
	if !md.NeedsPassword || md.StaticChallenge != "Enter token" {
		t.Fatalf("unexpected auth metadata %+v", md)
	}
	if !md.HasCA || !md.HasClientCert || !md.HasClientKey {
		t.Fatalf("inline material not detected %+v", md)
	}
	if md.Autologin {
		t.Fatal("a user/password profile is not autologin")
	}
	if _, err := Parse("client\ndev tap\nremote x 1194\n", "tap.ovpn"); err == nil {
		t.Fatal("TAP must be rejected")
	}
	if _, err := Parse("client\ndev tun\nconfig /tmp/x\n", "x.ovpn"); err == nil {
		t.Fatal("config includes must be rejected")
	}
	if _, err := Parse("client\ndev tun\nplugin /tmp/x.so\nremote x 1194\n", "x.ovpn"); err == nil {
		t.Fatal("plugins must be rejected")
	}
	if _, err := Parse("client\ndev tun\n", "x.ovpn"); err == nil {
		t.Fatal("a profile without remote must be rejected")
	}
	if _, err := Parse("tls-server\nport 1194\ndev tun\n", "server.ovpn"); err == nil {
		t.Fatal("a server profile must be rejected")
	}
	if _, err := Parse("mode server\ntls-server\ndev tun\n", "server.ovpn"); err == nil {
		t.Fatal("a mode server profile must be rejected")
	}
}

func TestBuildAddsClientAndDevDefaults(t *testing.T) {
	cfg := "remote vpn.example.com 1194\nauth-user-pass\n" + caBlock("ca") + "\n"
	out, err := BuildRuntimeConfig(cfg, BuildOptions{Settings: settings.Default()})
	if err != nil {
		t.Fatal(err)
	}
	if !hasDirective(out, "client") || !hasDirective(out, "dev") {
		t.Fatalf("client/dev defaults missing:\n%s", out)
	}
	server := "server 10.8.0.0 255.255.255.0\ndev tun\n" + caBlock("ca") + "\n"
	if _, err := BuildRuntimeConfig(server, BuildOptions{Settings: settings.Default()}); err == nil {
		t.Fatal("a server profile must not build")
	}
}

func TestBuildRuntimeConfig(t *testing.T) {
	cfg := baseProfile() + "up /tmp/evil.sh\n"
	s := settings.Default()
	s.MtuMode = settings.Mtu1400
	s.Cipher = settings.CipherAEADOnly
	out, err := BuildRuntimeConfig(cfg, BuildOptions{Settings: s, FallbackDNS: []string{"1.1.1.1"}})
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{
		"tun-mtu 1400", `pull-filter reject "cipher"`, "data-ciphers AES-256-GCM",
		"setenv OPENVPN_DESKTOP_REQUIRE_DNS 1", "setenv OPENVPN_DESKTOP_FALLBACK_DNS 1.1.1.1",
		"auth-nocache", "connect-retry-max 6", "remote-cert-tls server",
	} {
		if !strings.Contains(out, want) {
			t.Fatalf("expected %q in runtime config:\n%s", want, out)
		}
	}
	if strings.Contains(out, "up /tmp/evil.sh") {
		t.Fatal("profile script survived")
	}

	noCA := "client\ndev tun\nremote x 1194\nauth-user-pass\n"
	if _, err := BuildRuntimeConfig(noCA, BuildOptions{Settings: settings.Default()}); err == nil {
		t.Fatal("a profile without a trust anchor must not build")
	}

	cbc := baseProfile() + "data-ciphers AES-256-CBC\n"
	s2 := settings.Default()
	s2.Cipher = settings.CipherAEADOnly
	if _, err := BuildRuntimeConfig(cbc, BuildOptions{Settings: s2}); err == nil {
		t.Fatal("AEAD-only must refuse a CBC profile")
	}
}

func TestSplitTunnelRoutes(t *testing.T) {
	s := settings.Default()
	s.SplitTunnel = settings.SplitExclude
	s.SplitTunnelCIDRs = "10.0.0.0/8, 192.168.1.0/24"
	out, err := BuildRuntimeConfig(baseProfile(), BuildOptions{Settings: s})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out, "route 10.0.0.0/8 net_gateway") || !strings.Contains(out, "route 192.168.1.0/24 net_gateway") {
		t.Fatalf("exclude routes missing:\n%s", out)
	}

	s.SplitTunnel = settings.SplitInclude
	s.SplitTunnelCIDRs = "203.0.113.0/24"
	out, err = BuildRuntimeConfig(baseProfile(), BuildOptions{Settings: s})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out, "route 203.0.113.0/24") || !strings.Contains(out, `pull-filter ignore "redirect-gateway"`) {
		t.Fatalf("include routes missing:\n%s", out)
	}
}

func TestPerAppWarnings(t *testing.T) {
	s := settings.Default()
	s.Compression = settings.CompressionStrict
	s.PerAppMode = settings.PerAppExclude
	s.PerAppExecutables = []string{"/usr/bin/firefox"}
	warnings := RuntimeWarnings(s)
	found := false
	for _, w := range warnings {
		if strings.Contains(w, "selected applications bypass the VPN") {
			found = true
		}
	}
	if !found {
		t.Fatalf("per-app warning missing: %v", warnings)
	}
}

func TestRuntimeWarnings(t *testing.T) {
	s := settings.Default()
	if len(RuntimeWarnings(s)) == 0 {
		t.Fatal("the default compatibility compression should be called out")
	}
	s = settings.Default()
	s.Compression = settings.CompressionStrict
	s.SplitTunnel = settings.SplitOff
	s.RequireTunnelDNS = true
	s.KillSwitch = false
	if len(RuntimeWarnings(s)) != 0 {
		t.Fatalf("expected no warnings, got %v", RuntimeWarnings(s))
	}
}
