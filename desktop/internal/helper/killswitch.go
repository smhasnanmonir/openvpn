package helper

import (
	"net"
	"strings"
)

const ksTable = "openvpn_desktop_ks"

// KillSwitchRules returns the nftables script for the strict output kill
// switch. Loopback, the tunnel, established flows, DHCP, IPv6 neighbour
// discovery and the VPN server's own IPs are allowed; everything else is
// dropped, so a DNS or traffic leak while disconnected cannot leave the
// machine.
func KillSwitchRules(serverIPs []string, allowMark bool) string {
	var b strings.Builder
	b.WriteString("table inet " + ksTable + " {\n")
	b.WriteString("  chain output {\n")
	b.WriteString("    type filter hook output priority 0; policy drop;\n")
	b.WriteString("    oif \"lo\" accept\n")
	b.WriteString("    oifname \"tun*\" accept\n")
	b.WriteString("    oifname \"tap*\" accept\n")
	if allowMark {
		// Per-app split tunneling intentionally lets selected apps use the
		// physical path. Only CAP_NET_ADMIN processes can set SO_MARK/0x1.
		b.WriteString("    meta mark 0x1 accept\n")
	}
	b.WriteString("    ct state established,related accept\n")
	// Keep DHCP and IPv6 neighbour discovery working so enabling the kill
	// switch does not immediately expire the local address lease.
	b.WriteString("    udp sport 68 udp dport 67 accept\n")
	b.WriteString("    udp sport 546 udp dport 547 accept\n")
	b.WriteString("    icmpv6 type { nd-router-solicit, nd-router-advert, nd-neighbor-solicit, nd-neighbor-advert, destination-unreachable, packet-too-big, time-exceeded, parameter-problem } accept\n")
	var v4, v6 []string
	for _, ip := range serverIPs {
		parsed := net.ParseIP(strings.TrimSpace(ip))
		if parsed == nil {
			continue
		}
		if parsed.To4() != nil {
			v4 = append(v4, parsed.To4().String())
		} else {
			v6 = append(v6, parsed.String())
		}
	}
	if len(v4) > 0 {
		b.WriteString("    ip daddr { " + strings.Join(v4, ", ") + " } accept\n")
	}
	if len(v6) > 0 {
		b.WriteString("    ip6 daddr { " + strings.Join(v6, ", ") + " } accept\n")
	}
	b.WriteString("  }\n")
	b.WriteString("}\n")
	return b.String()
}

// InstallKillSwitch atomically replaces the kill-switch table. allowMark lets
// per-app bypass traffic through when that feature is active.
func InstallKillSwitch(serverIPs []string, allowMark bool) error {
	nft, err := findTrusted("nft")
	if err != nil {
		return err
	}
	_ = RemoveKillSwitch()
	return runTrustedStdin(KillSwitchRules(serverIPs, allowMark), nft, "-f", "-")
}

// RemoveKillSwitch deletes the table if it exists.
func RemoveKillSwitch() error {
	nft, err := findTrusted("nft")
	if err != nil {
		return err
	}
	return runTrusted(nft, "delete", "table", "inet", ksTable)
}

// KillSwitchActive reports whether the table exists (used by the GUI's
// startup recovery and diagnostics).
func KillSwitchActive() bool {
	nft, err := findTrusted("nft")
	if err != nil {
		return false
	}
	return runTrusted(nft, "list", "table", "inet", ksTable) == nil
}
