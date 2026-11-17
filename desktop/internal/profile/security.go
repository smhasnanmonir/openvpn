package profile

import (
	"fmt"
	"strings"

	"github.com/smhasnanmonir/openvpn/desktop/internal/settings"
)

// AEADCiphers are the authenticated data ciphers this client accepts in
// AEAD-only mode.
var AEADCiphers = []string{
	"AES-256-GCM",
	"AES-192-GCM",
	"AES-128-GCM",
	"CHACHA20-POLY1305",
}

// LegacyFinding is a non-AEAD cipher offered by a profile directive.
type LegacyFinding struct {
	Directive string
	Value     string
}

var cipherDirectives = map[string]bool{
	"cipher":                true,
	"data-ciphers":          true,
	"data-ciphers-fallback": true,
}

// LegacyCipherFindings returns every non-AEAD entry in a real cipher,
// data-ciphers or data-ciphers-fallback directive.
func LegacyCipherFindings(config string) []LegacyFinding {
	var out []LegacyFinding
	for _, line := range strings.Split(config, "\n") {
		name, value, ok := directive(line)
		if !ok || !cipherDirectives[name] || value == "" {
			continue
		}
		for _, entry := range strings.Split(value, ":") {
			cipher := strings.TrimSpace(entry)
			if cipher == "" {
				continue
			}
			if !containsFold(AEADCiphers, cipher) {
				out = append(out, LegacyFinding{Directive: name, Value: cipher})
			}
		}
	}
	return out
}

// ValidateAEADOnly returns an actionable error when the profile pins a
// non-AEAD cipher, which AEAD-only mode refuses.
func ValidateAEADOnly(config string) error {
	findings := LegacyCipherFindings(config)
	if len(findings) == 0 {
		return nil
	}
	f := findings[0]
	return fmt.Errorf("this profile pins or allows the non-AEAD data cipher %s (%s). "+
		"AEAD-only mode refuses non-AEAD ciphers. Use a profile that offers AES-GCM or "+
		"ChaCha20-Poly1305, or switch the cipher policy to the compatibility (profile) option "+
		"in Settings to connect with this profile", f.Value, f.Directive)
}

const aeadFallbackCipher = "cipher AES-256-GCM"
const aeadDataCiphers = "data-ciphers AES-256-GCM:AES-192-GCM:AES-128-GCM:CHACHA20-POLY1305"

// WithAEADOnlyPushPolicy forces AEAD data ciphers and rejects a server-pushed
// non-AEAD cipher. It is idempotent.
func WithAEADOnlyPushPolicy(config string) string {
	withoutFilters := removePullFilters(config, func(name string) bool { return name == "cipher" })
	withoutCiphers := removeDirectives(withoutFilters, "cipher", "data-ciphers", "data-ciphers-fallback")
	additions := []string{
		`pull-filter accept "cipher AES-256-GCM"`,
		`pull-filter accept "cipher AES-192-GCM"`,
		`pull-filter accept "cipher AES-128-GCM"`,
		`pull-filter accept "cipher CHACHA20-POLY1305"`,
		`pull-filter reject "cipher"`,
		aeadDataCiphers,
		aeadFallbackCipher,
	}
	return appendLines(withoutCiphers, additions...)
}

// WithStrictCompressionPolicy removes local compression directives and refuses
// every server-pushed compressor except the "comp-lzo no" stub, which only
// means "compression disabled". It is idempotent.
func WithStrictCompressionPolicy(config string) string {
	withoutLocal := removeDirectives(config, "compress", "comp-lzo", "allow-compression")
	withoutFilters := removePullFilters(withoutLocal, func(name string) bool {
		return name == "compress" || name == "comp-lzo" || name == "allow-compression"
	})
	return appendLines(withoutFilters,
		"allow-compression no",
		`pull-filter accept "comp-lzo no"`,
		`pull-filter reject "comp-lzo"`,
		`pull-filter reject "compress"`,
	)
}

// WithCompatibilityCompressionPolicy enables OpenVPN's asymmetric compression
// mode (the server may compress what it sends; the client never compresses
// uploads) and leaves the profile's own comp-lzo/compress directives in place.
func WithCompatibilityCompressionPolicy(config string) string {
	without := removeDirectives(config, "allow-compression")
	return appendLines(without, "allow-compression asym")
}

// CompressionDescription is shown in Settings.
func CompressionDescription(p settings.CompressionPolicy) string {
	if p == settings.CompressionStrict {
		return "Strict: refuse every server-pushed compressor (including compression stubs). " +
			"A server that requires compression fails with an explicit error instead of " +
			"silently compressing traffic."
	}
	return "Compatibility: allow the server to compress traffic it sends to this computer " +
		"(LZO-ASYM, as some providers require). Upload compression is refused and symmetric " +
		"compression is never enabled."
}

func CipherDescription(p settings.CipherPolicy) string {
	if p == settings.CipherAEADOnly {
		return "AEAD only: require AES-GCM or ChaCha20-Poly1305 for the data channel and refuse " +
			"CBC ciphers. Profiles that pin a CBC cipher must use the profile-default policy."
	}
	return "Profile default: use the ciphers the profile and server negotiate."
}

func removePullFilters(config string, predicate func(name string) bool) string {
	lines, trailing := splitConfig(config)
	out := make([]string, 0, len(lines))
	for _, line := range lines {
		if name, ok := pullFilterMatchDirective(line); ok && predicate(name) {
			continue
		}
		out = append(out, line)
	}
	return joinConfig(out, trailing)
}

func pullFilterMatchDirective(line string) (string, bool) {
	name, value, ok := directive(line)
	if !ok || name != "pull-filter" {
		return "", false
	}
	parts := fields(value)
	if len(parts) < 2 {
		return "", false
	}
	match := strings.TrimSpace(strings.TrimPrefix(value, parts[0]))
	if len(match) >= 2 && strings.HasPrefix(match, `"`) && strings.HasSuffix(match, `"`) {
		match = strings.Trim(match, `"`)
	}
	if match == "" {
		return "", false
	}
	return strings.ToLower(fields(match)[0]), true
}

// ParseCipherPolicy converts a persisted string to a policy.
func ParseCipherPolicy(s string) settings.CipherPolicy {
	if settings.CipherPolicy(s) == settings.CipherAEADOnly {
		return settings.CipherAEADOnly
	}
	return settings.CipherProfile
}
