package profile

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/pem"
	"sort"
	"strings"
)

// TrustMaterial is what a profile gives the client to authenticate the server.
// peer-fingerprint is deliberately excluded: OpenVPN 2.x can enforce it, but
// this client's generated configuration is validated by the same policy as the
// Android/mbedTLS build for parity, so a fingerprint-only profile is rejected
// and a CA or legacy tls-remote subject pin is required.
type TrustMaterial struct {
	HasCA              bool
	HasPeerFingerprint bool
	HasTLSRemote       bool
}

// HasServerTrust is true when the client can actually verify the server.
func (m TrustMaterial) HasServerTrust() bool { return m.HasCA || m.HasTLSRemote }

// TrustDecision is the result of ValidateTrust.
type TrustDecision struct {
	Allowed bool
	Message string
}

const noServerTrustMessage = "This profile has no CA certificate and no explicit server-name pin, so the app cannot " +
	"verify that it is really connecting to the intended VPN server. Ask your VPN provider for a " +
	"profile that includes a <ca> certificate, or add one to the .ovpn file, then import it again."

const peerFingerprintNotEnforcedMessage = "This profile relies only on a peer-fingerprint pin. This client requires a CA certificate " +
	"or an explicit tls-remote server-name policy for parity with the Android client's verified " +
	"trust model, so a fingerprint-only profile is refused. Ask your VPN provider for a profile " +
	"that includes a <ca> certificate, or add one to the .ovpn file, then import it again."

// ScanTrust inspects real (non-comment) lines and inline blocks.
func ScanTrust(config string) TrustMaterial {
	var m TrustMaterial
	block := ""
	for _, raw := range strings.Split(config, "\n") {
		t := strings.TrimSpace(raw)
		lt := strings.ToLower(t)
		if block != "" {
			if lt == "</"+block+">" {
				block = ""
			}
			continue
		}
		if lt == "<ca>" {
			m.HasCA = true
			block = "ca"
			continue
		}
		if lt == "<peer-fingerprint>" {
			m.HasPeerFingerprint = true
			block = "peer-fingerprint"
			continue
		}
		name, value, ok := directive(raw)
		if !ok {
			continue
		}
		switch name {
		case "ca":
			if value != "" {
				m.HasCA = true
			}
		case "peer-fingerprint":
			if value != "" {
				m.HasPeerFingerprint = true
			}
		case "tls-remote":
			if value != "" {
				m.HasTLSRemote = true
			}
		}
	}
	return m
}

// ValidateTrust fails closed for profiles without a trust anchor this client
// can enforce.
func ValidateTrust(config string) TrustDecision {
	m := ScanTrust(config)
	switch {
	case m.HasServerTrust():
		return TrustDecision{Allowed: true}
	case m.HasPeerFingerprint:
		return TrustDecision{Allowed: false, Message: peerFingerprintNotEnforcedMessage}
	default:
		return TrustDecision{Allowed: false, Message: noServerTrustMessage}
	}
}

// HasIgnoredPeerFingerprint reports a peer-fingerprint pin that is not used as
// the trust anchor (the profile also has a CA, or the caller warns the user).
func HasIgnoredPeerFingerprint(config string) bool {
	return ScanTrust(config).HasPeerFingerprint
}

// CAFingerprint returns a stable SHA-256 over the inline CA certificates, or
// "" when the profile has no extractable inline CA material. It is used to warn
// when a same-named profile is re-imported with a different CA.
func CAFingerprint(config string) string {
	caText, ok := InlineBlock(config, "ca")
	if !ok {
		return ""
	}
	var ders [][]byte
	rest := []byte(caText)
	for {
		block, remaining := pem.Decode(rest)
		if block == nil {
			break
		}
		if block.Type == "CERTIFICATE" && len(block.Bytes) > 0 {
			ders = append(ders, block.Bytes)
		}
		rest = remaining
	}
	if len(ders) == 0 {
		// Not parseable as PEM: hash a normalized text form so a text edit is
		// still detected.
		var b strings.Builder
		for _, line := range strings.Split(caText, "\n") {
			l := strings.TrimSpace(line)
			if l == "" || strings.HasPrefix(l, "#") || strings.HasPrefix(l, ";") {
				continue
			}
			b.WriteString(l)
			b.WriteByte('\n')
		}
		if b.Len() == 0 {
			return ""
		}
		sum := sha256.Sum256([]byte(b.String()))
		return hex.EncodeToString(sum[:])
	}
	sort.Slice(ders, func(i, j int) bool {
		return hex.EncodeToString(ders[i]) < hex.EncodeToString(ders[j])
	})
	h := sha256.New()
	for _, d := range ders {
		h.Write(d)
	}
	return hex.EncodeToString(h.Sum(nil))
}
