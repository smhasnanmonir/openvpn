package profile

import (
	"errors"
	"fmt"
	"strconv"
	"strings"

	"github.com/smhasnanmonir/openvpn/desktop/internal/settings"
)

// BuildOptions carries the per-connection policy decisions.
type BuildOptions struct {
	Settings    settings.Settings
	FallbackDNS []string
}

// BuildRuntimeConfig produces the hardened configuration handed to OpenVPN.
// It re-validates trust (so an old vault entry cannot bypass a newer policy),
// removes profile scripts and management options, and applies the user's MTU,
// compression, cipher, DNS and split-tunnel policies.
func BuildRuntimeConfig(stored string, opts BuildOptions) (string, error) {
	trust := ValidateTrust(stored)
	if !trust.Allowed {
		return "", errors.New(trust.Message)
	}

	clean, _ := SanitizeConfig(stored)
	if err := ValidateNoExternalFiles(clean); err != nil {
		return "", err
	}
	clean = WithServerCertificateUsage(clean)
	// A client profile must be a TLS client on a TUN device. Fill in the two
	// directives providers sometimes leave implicit instead of failing later
	// with an unhelpful OpenVPN error.
	if hasDirective(clean, "server", "tls-server") {
		return "", errors.New("this is an OpenVPN server profile, not a client profile")
	}
	if !hasDirective(clean, "client", "tls-client") {
		clean = appendLines(clean, "client")
	}
	if !hasDirective(clean, "dev") {
		clean = appendLines(clean, "dev tun")
	}

	if mtu, ok := opts.Settings.MtuMode.MTU(); ok {
		clean = WithTunMtuOverride(clean, mtu)
	}

	switch opts.Settings.Compression {
	case settings.CompressionStrict:
		clean = WithStrictCompressionPolicy(clean)
	default:
		clean = WithCompatibilityCompressionPolicy(clean)
	}

	switch opts.Settings.Cipher {
	case settings.CipherAEADOnly:
		if err := ValidateAEADOnly(clean); err != nil {
			return "", err
		}
		clean = WithAEADOnlyPushPolicy(clean)
	}

	var err error
	clean, err = applySplitTunnel(clean, opts.Settings)
	if err != nil {
		return "", err
	}

	// DNS policy is consumed by the privileged helper's route-up script.
	// OpenVPN exports these setenv values to scripts.
	clean = appendLines(clean,
		"setenv OPENVPN_DESKTOP_REQUIRE_DNS "+boolFlag(opts.Settings.RequireTunnelDNS),
		"setenv OPENVPN_DESKTOP_FULL_DNS "+boolFlag(opts.Settings.SplitTunnel == settings.SplitOff),
	)
	if len(opts.FallbackDNS) > 0 {
		clean = appendLines(clean, "setenv OPENVPN_DESKTOP_FALLBACK_DNS "+strings.Join(opts.FallbackDNS, ","))
	}

	// Security and reliability defaults. The helper re-applies the ones that
	// affect process behaviour so a tampered config cannot remove them.
	clean = appendLines(clean,
		"auth-nocache",
		"persist-key",
		"nobind",
		"verb 4",
	)
	if !hasDirective(clean, "keepalive", "ping-restart", "ping") {
		clean = appendLines(clean, "keepalive 10 120")
	}
	if !hasDirective(clean, "resolv-retry") {
		clean = appendLines(clean, "resolv-retry infinite")
	}
	clean = removeDirectives(clean, "connect-retry", "connect-retry-max", "connect-timeout")
	clean = appendLines(clean, "connect-retry 5 60", "connect-retry-max 6")

	return clean, nil
}

func applySplitTunnel(config string, s settings.Settings) (string, error) {
	if s.SplitTunnel == settings.SplitOff && !s.LanBypass {
		return config, nil
	}
	cidrs, err := settings.ParseCIDRList(s.SplitTunnelCIDRs)
	if err != nil {
		return "", err
	}
	if s.SplitTunnel != settings.SplitOff && len(cidrs) == 0 {
		return "", errors.New("split tunneling is enabled but no networks are listed")
	}

	switch s.SplitTunnel {
	case settings.SplitInclude:
		// Do not install a default route; only the listed networks go through
		// the tunnel. Server-pushed routes still apply (they are the provider's
		// own internal networks), which is the conservative choice.
		config = removeDirectives(config, "redirect-gateway", "redirect-gateway-ipv6")
		config = appendLines(config,
			`pull-filter ignore "redirect-gateway"`,
			`pull-filter ignore "redirect-gateway-ipv6"`,
		)
		for _, c := range cidrs {
			if isIPv6CIDR(c) {
				config = appendLines(config, "route-ipv6 "+c)
			} else {
				config = appendLines(config, "route "+c)
			}
		}
	case settings.SplitExclude:
		for _, c := range cidrs {
			if isIPv6CIDR(c) {
				config = appendLines(config, "route-ipv6 "+c+" net_gateway")
			} else {
				config = appendLines(config, "route "+c+" net_gateway")
			}
		}
	}

	if s.LanBypass {
		lan := []string{
			"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16",
			"fc00::/7", "fe80::/10",
		}
		for _, c := range lan {
			if isIPv6CIDR(c) {
				config = appendLines(config, "route-ipv6 "+c+" net_gateway")
			} else {
				config = appendLines(config, "route "+c+" net_gateway")
			}
		}
	}
	return config, nil
}

func isIPv6CIDR(c string) bool { return strings.Contains(c, ":") }

func boolFlag(v bool) string {
	if v {
		return "1"
	}
	return "0"
}

// RuntimeWarnings returns the leak warnings the UI must show for the current
// settings. An empty slice means no policy lets traffic outside the tunnel.
func RuntimeWarnings(s settings.Settings) []string {
	var out []string
	switch s.SplitTunnel {
	case settings.SplitInclude:
		out = append(out, "Only the listed networks use the VPN. Traffic to every other "+
			"destination bypasses the tunnel and can expose your IP address. DNS may also use the "+
			"underlying resolver unless the selected networks include it.")
	case settings.SplitExclude:
		out = append(out, "The listed networks bypass the VPN. Their traffic is not protected "+
			"and can expose your IP address.")
	}
	if s.LanBypass && s.SplitTunnel == settings.SplitOff {
		out = append(out, "Local network (LAN) bypass is on: private-range traffic does not use the tunnel.")
	}
	if s.KillSwitch {
		out = append(out, "Kill switch is on. While the app holds the firewall, traffic outside the "+
			"tunnel is blocked. If the app crashes, run the included recovery command to restore networking.")
	}
	if !s.RequireTunnelDNS {
		out = append(out, "\"Require tunnel DNS\" is off. If the server pushes no DNS resolver, "+
			"the system resolver is used and your DNS queries can leak.")
	}
	if s.Compression == settings.CompressionCompatibility {
		out = append(out, "Compression compatibility is on for provider support. Prefer Strict when "+
			"your provider does not require compression.")
	}
	switch s.PerAppMode {
	case settings.PerAppInclude:
		out = append(out, "Per-app split tunneling: only the selected applications use the VPN. "+
			"Every other application bypasses the tunnel and can expose your IP address. The system "+
			"resolver is kept on the VPN path so its DNS does not leak to the ISP; apps using their "+
			"own DoH/DoT follow the per-app rule. A newly started app is moved into the rule within "+
			"about a second.")
	case settings.PerAppExclude:
		out = append(out, "Per-app split tunneling: the selected applications bypass the VPN. "+
			"Their traffic is not protected and can expose your IP address. System-resolver DNS stays "+
			"on the VPN path; apps using their own DoH/DoT follow the per-app rule. A newly started app "+
			"is moved into the rule within about a second.")
	}
	return out
}

// EffectiveMTULabel is shown in connection details.
func EffectiveMTULabel(s settings.Settings) string {
	if mtu, ok := s.MtuMode.MTU(); ok {
		return strconv.Itoa(mtu)
	}
	return "server-pushed"
}

// FormatCIDRs renders the parsed list for the UI.
func FormatCIDRs(raw string) string {
	list, err := settings.ParseCIDRList(raw)
	if err != nil {
		return ""
	}
	return fmt.Sprint(strings.Join(list, ", "))
}
