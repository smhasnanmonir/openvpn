package profile

import (
	"sort"
	"strings"
)

// dangerousDirectives are profile options that could run code as the
// privileged helper, write files, or take over the management interface. They
// are removed before the profile is handed to OpenVPN, both at import and
// connect time (defense in depth: the helper repeats this).
var dangerousDirectives = map[string]bool{
	"up":                           true,
	"down":                         true,
	"up-restart":                   true,
	"up-delay":                     true,
	"route-up":                     true,
	"route-pre-down":               true,
	"down-pre":                     true,
	"script-security":              true,
	"plugin":                       true,
	"config":                       true,
	"client-connect":               true,
	"client-disconnect":            true,
	"learn-address":                true,
	"auth-user-pass-verify":        true,
	"tls-verify":                   true,
	"iproute":                      true,
	"iproute-pre-down":             true,
	"chroot":                       true,
	"user":                         true,
	"group":                        true,
	"cd":                           true,
	"daemon":                       true,
	"log":                          true,
	"log-append":                   true,
	"status":                       true,
	"writepid":                     true,
	"management":                   true,
	"management-client":            true,
	"management-hold":              true,
	"management-query-passwords":   true,
	"management-signal":            true,
	"management-forget-disconnect": true,
	"management-up-down":           true,
	"auth-user-pass":               true,
	"askpass":                      true,
	"mute":                         true,
	"verb":                         true,
	"auth-nocache":                 true,
	"persist-key":                  true,
	"connect-retry":                true,
	"connect-retry-max":            true,
	"connect-timeout":              true,
	"resolv-retry":                 true,
	"block-outside-dns":            true,
	"register-dns":                 true,
	"windows-driver":               true,
	"client-cert-not-required":     true,
	// Dynamic code loading: OpenSSL engines/providers and PKCS#11 modules are
	// shared objects the privileged helper would load as root.
	"engine":           true,
	"providers":        true,
	"provider":         true,
	"pkcs11-providers": true,
	"pkcs11-provider":  true,
	"cryptoapicert":    true,
	"tls-export-cert":  true,
	"wintun":           true,
	"tap-windows":      true,
}

// SanitizeConfig removes dangerous local directives and every pull-filter (the
// security policy adds its own). It returns the cleaned profile and the sorted
// names of removed directives.
func SanitizeConfig(config string) (string, []string) {
	lines, trailing := splitConfig(config)
	out := make([]string, 0, len(lines))
	removed := map[string]bool{}
	for _, line := range lines {
		name, value, ok := directive(line)
		if !ok {
			out = append(out, line)
			continue
		}
		if name == "setenv" {
			// `setenv opt <option>` forwards an unknown option; do not allow a
			// profile to smuggle one past this filter.
			if strings.HasPrefix(strings.ToLower(strings.TrimSpace(value)), "opt ") {
				removed["setenv opt"] = true
				continue
			}
			out = append(out, line)
			continue
		}
		if name == "pull-filter" {
			removed["pull-filter"] = true
			continue
		}
		if dangerousDirectives[name] {
			removed[name] = true
			continue
		}
		out = append(out, line)
	}
	names := make([]string, 0, len(removed))
	for name := range removed {
		names = append(names, name)
	}
	sort.Strings(names)
	return joinConfig(out, trailing), names
}
