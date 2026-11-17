package security

import (
	"strings"
	"testing"
)

func TestSanitizeRedactsSecrets(t *testing.T) {
	for _, line := range []string{
		"PASSWORD: hunter2",
		"auth-user-pass /home/user/creds.txt",
		"-----BEGIN PRIVATE KEY-----",
		"TLS Auth Error: auth-token mismatch",
		"Inline profile: proxy-password secret",
		"static challenge response: 123456",
	} {
		if got := Sanitize(line); got != Redacted {
			t.Fatalf("Sanitize(%q) = %q, want redacted", line, got)
		}
		if !ContainsSecret(line) {
			t.Fatalf("ContainsSecret(%q) = false", line)
		}
	}
	if got := Sanitize("OpenVPN 2.7.0 started"); got != "OpenVPN 2.7.0 started" {
		t.Fatalf("harmless line changed: %q", got)
	}
	long := strings.Repeat("x", 3000)
	if got := Sanitize(long); !strings.HasSuffix(got, "...[truncated]") || len(got) > 2100 {
		t.Fatalf("long line not truncated: len=%d", len(got))
	}
}
