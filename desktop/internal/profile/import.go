package profile

import "strings"

// ClientCertDisabledDirective is the OpenVPN 2.x equivalent of Core's
// certificate-less user/password mode. For OpenVPN 2.x the profile simply has
// no client certificate; this marker is kept for parity with profiles imported
// by the Android client and is harmless.
const ClientCertDisabledDirective = "setenv CLIENT_CERT 0"

var serverCertificateDirectives = []string{
	"remote-cert-tls",
	"remote-cert-eku",
	"remote-cert-ku",
	"tls-remote",
	"verify-x509-name",
}

// WithServerCertificateUsage appends `remote-cert-tls server` when the profile
// uses a CA but does not already constrain the server certificate usage. A
// certificate signed by the configured CA would otherwise be accepted even if
// it is a client certificate, which is a server-impersonation risk.
func WithServerCertificateUsage(config string) string {
	if hasDirective(config, serverCertificateDirectives...) {
		return config
	}
	if hasDirective(config, "peer-fingerprint") || hasInlineBlock(config, "peer-fingerprint") {
		return config
	}
	if !hasDirective(config, "ca") && !hasInlineBlock(config, "ca") {
		return config
	}
	lines, trailing := splitConfig(config)
	lines = append(lines, "remote-cert-tls server")
	return joinConfig(lines, trailing)
}

// DeclaresUserPasswordAuth reports a real auth-user-pass directive or inline
// block.
func DeclaresUserPasswordAuth(config string) bool {
	if hasDirective(config, "auth-user-pass") {
		return true
	}
	return hasInlineBlock(config, "auth-user-pass")
}

// DeclaresExternalPki reports an explicit EXTERNAL_PKI request, which this
// client cannot satisfy.
func DeclaresExternalPki(config string) bool {
	for _, line := range strings.Split(config, "\n") {
		name, value, ok := directive(line)
		if !ok {
			continue
		}
		if name == "external_pki" {
			return true
		}
		if name == "setenv" && strings.HasPrefix(strings.ToLower(value), "opt external_pki") {
			return true
		}
	}
	return false
}

// WithoutClientCertificate appends the marker used by profiles that sign in
// with a username/password and have no client certificate.
func WithoutClientCertificate(config string) string {
	for _, line := range strings.Split(config, "\n") {
		name, value, ok := directive(line)
		if ok && name == "setenv" && strings.EqualFold(strings.TrimSpace(value), "CLIENT_CERT 0") {
			return config
		}
	}
	return appendLines(config, ClientCertDisabledDirective)
}

// NegotiableTLSCiphers removes tls-cipher pins that list only legacy TLS 1.2
// suites (no ECDHE suite). Such a pin makes a TLS 1.3-only provider stall until
// the connection timeout. Pins that still contain an ECDHE suite are kept.
func NegotiableTLSCiphers(config string) string {
	lines, trailing := splitConfig(config)
	out := make([]string, 0, len(lines))
	changed := false
	for _, line := range lines {
		name, value, ok := directive(line)
		if !ok || name != "tls-cipher" {
			out = append(out, line)
			continue
		}
		suites := strings.FieldsFunc(value, func(r rune) bool {
			return r == ':' || r == ' ' || r == '\t'
		})
		if len(suites) == 0 {
			out = append(out, line)
			continue
		}
		hasECDHE := false
		for _, s := range suites {
			if strings.Contains(strings.ToUpper(s), "ECDHE") {
				hasECDHE = true
				break
			}
		}
		if hasECDHE {
			out = append(out, line)
		} else {
			changed = true
		}
	}
	if !changed {
		return config
	}
	return joinConfig(out, trailing)
}

// hasInlineBlock reports whether <tag> ... </tag> is present (case-insensitive).
func hasInlineBlock(config, tag string) bool {
	open := "<" + strings.ToLower(tag) + ">"
	for _, line := range strings.Split(config, "\n") {
		if strings.ToLower(strings.TrimSpace(line)) == open {
			return true
		}
	}
	return false
}

// InlineBlock returns the body of the first <tag> block, without the tags.
func InlineBlock(config, tag string) (string, bool) {
	open := "<" + strings.ToLower(tag) + ">"
	close := "</" + strings.ToLower(tag) + ">"
	var body []string
	inside := false
	for _, line := range strings.Split(config, "\n") {
		t := strings.ToLower(strings.TrimSpace(line))
		if !inside {
			if t == open {
				inside = true
			}
			continue
		}
		if t == close {
			return strings.Join(body, "\n"), true
		}
		body = append(body, line)
	}
	return "", false
}
