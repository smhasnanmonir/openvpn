package profile

import (
	"fmt"
	"strconv"
	"strings"

	"github.com/smhasnanmonir/openvpn/desktop/internal/model"
)

// ParseResult is the non-secret information extracted from a profile plus the
// cleanup the importer must perform.
type ParseResult struct {
	Metadata     model.ProfileMetadata
	ExternalRefs []string
	Warnings     []string
	Dev          string
}

var inlineTags = map[string]bool{
	"ca":               true,
	"cert":             true,
	"key":              true,
	"tls-auth":         true,
	"tls-crypt":        true,
	"tls-crypt-v2":     true,
	"peer-fingerprint": true,
}

var refDirectives = map[string]bool{
	"ca":           true,
	"cert":         true,
	"key":          true,
	"tls-auth":     true,
	"tls-crypt":    true,
	"tls-crypt-v2": true,
	"askpass":      true,
}

// Parse extracts metadata and external file references. It rejects profile
// includes, plugins, and TAP devices because the desktop client can only run a
// TUN client with a fully self-contained configuration.
func Parse(config, fileName string) (ParseResult, error) {
	var res ParseResult
	md := &res.Metadata
	md.ProfileName = strings.TrimSuffix(fileName, ".ovpn")
	inline := map[string]bool{}
	block := ""
	authUserPass := false
	askpass := false
	proto := "udp"
	hasRemote := false

	for _, raw := range strings.Split(config, "\n") {
		t := strings.TrimSpace(raw)
		lt := strings.ToLower(t)
		if block != "" {
			if lt == "</"+block+">" {
				block = ""
			}
			continue
		}
		if strings.HasPrefix(lt, "<") && strings.HasSuffix(lt, ">") && !strings.HasPrefix(lt, "</") {
			name := strings.TrimSuffix(strings.TrimPrefix(lt, "<"), ">")
			if inlineTags[name] {
				inline[name] = true
				block = name
				continue
			}
		}
		name, value, ok := directive(raw)
		if !ok {
			continue
		}
		switch name {
		case "config":
			return res, fmt.Errorf("this profile includes another file (config %s); import a self-contained .ovpn profile", value)
		case "plugin":
			return res, fmt.Errorf("this profile uses an OpenVPN plugin, which the desktop client does not run")
		case "server", "tls-server":
			return res, fmt.Errorf("this is an OpenVPN server profile, not a client profile")
		case "mode":
			if strings.EqualFold(strings.TrimSpace(value), "server") {
				return res, fmt.Errorf("this is an OpenVPN server profile (mode server), not a client profile")
			}
		case "dev":
			res.Dev = value
			if strings.HasPrefix(strings.ToLower(value), "tap") {
				return res, fmt.Errorf("this profile uses a TAP device, but the desktop client only supports TUN profiles")
			}
			if strings.EqualFold(value, "null") {
				return res, fmt.Errorf("this profile uses dev null, not a real tunnel")
			}
		case "remote":
			parts := fields(value)
			if len(parts) < 1 {
				continue
			}
			if !hasRemote {
				md.ServerHost = parts[0]
				md.ServerPort = "1194"
				if len(parts) >= 2 {
					if _, err := strconv.Atoi(parts[1]); err == nil {
						md.ServerPort = parts[1]
					}
				}
				if len(parts) >= 3 {
					proto = strings.ToLower(parts[2])
				}
				hasRemote = true
			}
		case "proto":
			proto = strings.ToLower(value)
		case "auth-user-pass":
			authUserPass = true
			md.NeedsPassword = true
		case "auth-user-pass-optional":
			md.NeedsPassword = true
		case "static-challenge":
			authUserPass = true
			md.NeedsPassword = true
			md.StaticChallenge = firstQuoted(value)
			if md.StaticChallenge == "" {
				md.StaticChallenge = "Static challenge"
			}
		case "askpass":
			askpass = true
			md.PrivateKeyNeed = true
		case "client-cert-not-required":
			md.ClientCertOff = true
		case "setenv":
			if strings.EqualFold(strings.TrimSpace(value), "CLIENT_CERT 0") {
				md.ClientCertOff = true
			}
		case "tls-client":
			// presence is enough; OpenVPN needs one of client/tls-client.
		}
		if refDirectives[name] && value != "" {
			res.ExternalRefs = append(res.ExternalRefs, value)
		}
		if name == "up" || name == "down" || name == "route-up" || name == "route-pre-down" ||
			name == "iproute" || name == "script-security" || name == "tls-verify" ||
			name == "auth-user-pass-verify" || name == "client-connect" ||
			name == "client-disconnect" || name == "learn-address" {
			res.Warnings = append(res.Warnings,
				fmt.Sprintf("removed the profile's %q script directive; the desktop client manages DNS and routes itself", name))
		}
		if name == "pull-filter" {
			res.Warnings = append(res.Warnings, "removed a profile pull-filter so the client's security policy cannot be overridden")
		}
	}

	if !hasRemote {
		return res, fmt.Errorf("this profile has no remote server address")
	}
	md.ServerHost = strings.TrimSpace(md.ServerHost)
	md.Protocol = proto
	if md.ServerPort == "" {
		md.ServerPort = "1194"
	}
	md.HasCA = inline["ca"] || hasDirective(config, "ca")
	md.HasClientCert = inline["cert"] || hasDirective(config, "cert")
	md.HasClientKey = inline["key"] || hasDirective(config, "key")
	md.HasTLSKey = inline["tls-auth"] || inline["tls-crypt"] || inline["tls-crypt-v2"] ||
		hasDirective(config, "tls-auth", "tls-crypt", "tls-crypt-v2")
	md.ExternalPKI = !md.HasClientCert && !md.HasClientKey && !authUserPass
	md.Autologin = md.HasClientCert && md.HasClientKey && !authUserPass && md.StaticChallenge == ""
	_ = askpass
	return res, nil
}

func firstQuoted(s string) string {
	s = strings.TrimSpace(s)
	if strings.HasPrefix(s, `"`) {
		if end := strings.Index(s[1:], `"`); end >= 0 {
			return s[1 : 1+end]
		}
	}
	parts := fields(s)
	if len(parts) > 0 {
		return strings.Trim(parts[0], `"`)
	}
	return ""
}
