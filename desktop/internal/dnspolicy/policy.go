// Package dnspolicy ports the Android client's fail-closed DNS policy. It never
// performs a lookup: only literals are accepted, so importing or connecting
// cannot itself leak a hostname.
package dnspolicy

import (
	"net/netip"
	"strings"
)

// Decision is the result of Decide.
type Decision struct {
	// Servers are the resolvers the tunnel may use.
	Servers []string
	// UsedFallback is true when the server pushed none and the user's fallback
	// list was used.
	UsedFallback bool
	// Filtered are resolvers dropped because the tunnel does not route their
	// address family.
	Filtered []string
	// Refused is true when the connection must stop to avoid a DNS leak;
	// Reason is user-facing.
	Refused bool
	Reason  string
}

const refusalMessage = "The VPN server did not provide a usable DNS resolver and the \"Require tunnel DNS\" " +
	"setting is on. Add a fallback DNS server in Settings, or use a profile whose server pushes " +
	"DNS, then reconnect. The connection was stopped so DNS requests cannot leak to the " +
	"underlying network."

const familyRefusalMessage = "The VPN server or the fallback DNS setting only offered resolvers for an address family " +
	"this tunnel does not route. Installing one of those would unblock that family and leak traffic. " +
	"Add a resolver for an address family the tunnel provides, or reconnect with a profile that " +
	"routes it. The connection was stopped so DNS requests cannot leak to the underlying network."

// NormalizeServer returns the canonical form of a usable unicast IPv4/IPv6
// literal, or false. Private/CGNAT ranges are allowed; loopback, unspecified,
// multicast and link-local are rejected.
func NormalizeServer(raw string) (string, bool) {
	text := strings.TrimSpace(raw)
	if text == "" {
		return "", false
	}
	addr, err := netip.ParseAddr(text)
	if err != nil {
		return "", false
	}
	if addr.Is4In6() {
		addr = addr.Unmap()
	}
	if !Routable(addr) {
		return "", false
	}
	return addr.String(), true
}

// Routable reports whether an address can be reached as a DNS server.
func Routable(addr netip.Addr) bool {
	if !addr.IsValid() || addr.IsUnspecified() || addr.IsLoopback() ||
		addr.IsMulticast() || addr.IsLinkLocalUnicast() || addr.IsLinkLocalMulticast() {
		return false
	}
	if addr.Is4() {
		b := addr.As4()
		if b[0] == 0 || b[0] >= 240 {
			return false
		}
	}
	return true
}

// ParseFallback parses a comma/whitespace separated resolver list, dropping
// invalid entries and de-duplicating in the user's order.
func ParseFallback(raw string) []string {
	if strings.TrimSpace(raw) == "" {
		return nil
	}
	parts := strings.FieldsFunc(raw, func(r rune) bool {
		return r == ',' || r == ';' || r == ' ' || r == '\t' || r == '\n'
	})
	out := make([]string, 0, len(parts))
	seen := map[string]bool{}
	for _, p := range parts {
		if s, ok := NormalizeServer(p); ok && !seen[s] {
			seen[s] = true
			out = append(out, s)
		}
	}
	return out
}

// Decide selects the pushed resolvers, then the user's fallback, and refuses
// when requireTunnelDNS is on and neither yields a resolver for a family the
// tunnel routes.
func Decide(pushed []string, requireTunnelDNS bool, fallback []string, tunnelIPv4, tunnelIPv6 bool) Decision {
	pushedAll := normalizeAll(pushed)
	pushedRouted := filterFamily(pushedAll, tunnelIPv4, tunnelIPv6)
	pushedFiltered := difference(pushedAll, pushedRouted)
	if len(pushedRouted) > 0 {
		return Decision{Servers: pushedRouted, Filtered: pushedFiltered}
	}

	fallbackAll := normalizeAll(fallback)
	fallbackRouted := filterFamily(fallbackAll, tunnelIPv4, tunnelIPv6)
	if len(fallbackRouted) > 0 {
		return Decision{
			Servers:      fallbackRouted,
			UsedFallback: true,
			Filtered:     append(pushedFiltered, difference(fallbackAll, fallbackRouted)...),
		}
	}

	if !requireTunnelDNS {
		return Decision{}
	}
	if len(pushedAll)+len(fallbackAll) > 0 {
		return Decision{Refused: true, Reason: familyRefusalMessage, Filtered: append(pushedFiltered, fallbackAll...)}
	}
	return Decision{Refused: true, Reason: refusalMessage}
}

func normalizeAll(list []string) []string {
	out := make([]string, 0, len(list))
	seen := map[string]bool{}
	for _, item := range list {
		if s, ok := NormalizeServer(item); ok && !seen[s] {
			seen[s] = true
			out = append(out, s)
		}
	}
	return out
}

func filterFamily(servers []string, v4, v6 bool) []string {
	out := make([]string, 0, len(servers))
	for _, s := range servers {
		addr, err := netip.ParseAddr(s)
		if err != nil {
			continue
		}
		if addr.Is4() && v4 {
			out = append(out, s)
		} else if addr.Is6() && v6 {
			out = append(out, s)
		}
	}
	return out
}

func difference(all, routed []string) []string {
	keep := make(map[string]bool, len(routed))
	for _, s := range routed {
		keep[s] = true
	}
	var out []string
	for _, s := range all {
		if !keep[s] {
			out = append(out, s)
		}
	}
	return out
}

// ParseForeignOptions extracts DNS servers and search domains from OpenVPN's
// foreign_option_N environment values.
func ParseForeignOptions(values []string) (dns []string, domains []string) {
	for _, v := range values {
		parts := strings.Fields(strings.TrimSpace(v))
		if len(parts) < 3 || parts[0] != "dhcp-option" {
			continue
		}
		switch strings.ToUpper(parts[1]) {
		case "DNS", "DNS6":
			dns = append(dns, parts[2])
		case "DOMAIN", "DOMAIN-SEARCH":
			domains = append(domains, parts[2])
		}
	}
	return dns, domains
}
