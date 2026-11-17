// Package security contains the log redaction used everywhere a line can cross
// from the OpenVPN process or a profile into the UI.
package security

import "strings"

// Redacted is what a sensitive line is replaced with.
const Redacted = "[redacted: sensitive log line]"

// markers must stay in sync with the Android client's LogSanitizer and the
// native sanitizer. Lower-case comparisons are used.
var markers = []string{
	"password",
	"passphrase",
	"private key",
	"begin certificate",
	"begin rsa private",
	"begin private key",
	"auth-token",
	"auth_token",
	"session_id",
	"session token",
	"auth-user-pass",
	"proxy-user-pass",
	"proxy-password",
	"tls-crypt",
	"tls-auth",
	"static challenge",
	"challenge response",
}

// Sanitize redacts lines that look like they contain credentials or key
// material and truncates very long lines. It deliberately over-redacts: losing
// one diagnostic line is better than writing a password to disk.
func Sanitize(line string) string {
	lower := strings.ToLower(line)
	for _, m := range markers {
		if strings.Contains(lower, m) {
			return Redacted
		}
	}
	if len(line) > 2048 {
		return line[:2048] + "...[truncated]"
	}
	return line
}

// ContainsSecret reports whether Sanitize would redact the line.
func ContainsSecret(line string) bool {
	lower := strings.ToLower(line)
	for _, m := range markers {
		if strings.Contains(lower, m) {
			return true
		}
	}
	return false
}
