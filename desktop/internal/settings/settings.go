// Package settings defines the persisted user preferences. The values live in
// the encrypted vault, so there is no plaintext settings file.
package settings

import (
	"fmt"
	"net"
	"net/url"
	"strings"
)

// MtuMode is the TUN MTU override.
type MtuMode string

const (
	MtuAuto MtuMode = "auto"
	Mtu1500 MtuMode = "1500"
	Mtu1400 MtuMode = "1400"
	Mtu1280 MtuMode = "1280"
)

// MTU returns the override in bytes and whether one applies.
func (m MtuMode) MTU() (int, bool) {
	switch m {
	case Mtu1500:
		return 1500, true
	case Mtu1400:
		return 1400, true
	case Mtu1280:
		return 1280, true
	default:
		return 0, false
	}
}

func (m MtuMode) Label() string {
	switch m {
	case Mtu1400:
		return "1400"
	case Mtu1280:
		return "1280"
	case Mtu1500:
		return "1500"
	default:
		return "Automatic"
	}
}

// CompressionPolicy selects provider-compatible or strict compression.
type CompressionPolicy string

const (
	CompressionCompatibility CompressionPolicy = "compatibility"
	CompressionStrict        CompressionPolicy = "strict"
)

func (p CompressionPolicy) Label() string {
	if p == CompressionStrict {
		return "Strict"
	}
	return "Compatibility"
}

// CipherPolicy selects profile-defined or AEAD-only data ciphers.
type CipherPolicy string

const (
	CipherProfile  CipherPolicy = "profile"
	CipherAEADOnly CipherPolicy = "aead-only"
)

func (p CipherPolicy) Label() string {
	if p == CipherAEADOnly {
		return "AEAD only"
	}
	return "Profile default"
}

// SplitTunnelMode is the route-based desktop equivalent of Android's per-app
// split tunneling. Off routes everything through the tunnel.
type SplitTunnelMode string

const (
	SplitOff     SplitTunnelMode = "off"
	SplitInclude SplitTunnelMode = "include"
	SplitExclude SplitTunnelMode = "exclude"
)

func (m SplitTunnelMode) Label() string {
	switch m {
	case SplitInclude:
		return "Only these networks"
	case SplitExclude:
		return "All except these networks"
	default:
		return "All traffic"
	}
}

// PerAppMode selects Linux per-app split tunneling. It is separate from the
// route-based SplitTunnel mode; both may be active.
type PerAppMode string

const (
	PerAppOff     PerAppMode = "off"
	PerAppInclude PerAppMode = "include"
	PerAppExclude PerAppMode = "exclude"
)

func (m PerAppMode) Label() string {
	switch m {
	case PerAppInclude:
		return "Only selected apps use the VPN"
	case PerAppExclude:
		return "Selected apps bypass the VPN"
	default:
		return "Off"
	}
}

// ThemeMode follows the OS or forces an appearance.
type ThemeMode string

const (
	ThemeSystem ThemeMode = "system"
	ThemeLight  ThemeMode = "light"
	ThemeDark   ThemeMode = "dark"
)

// Settings is the full preference set. Defaults are the safe options: DNS is
// fail-closed, the public-IP check is opt-in only through the default URL, and
// the kill switch is off until the user enables it.
type Settings struct {
	RequireTunnelDNS bool              `json:"requireTunnelDns"`
	FallbackDNS      string            `json:"fallbackDns"`
	MtuMode          MtuMode           `json:"mtuMode"`
	Compression      CompressionPolicy `json:"compression"`
	Cipher           CipherPolicy      `json:"cipher"`
	AutoCheckIP      bool              `json:"autoCheckIp"`
	PublicIPURL      string            `json:"publicIpUrl"`
	Theme            ThemeMode         `json:"theme"`
	KillSwitch       bool              `json:"killSwitch"`
	SplitTunnel      SplitTunnelMode   `json:"splitTunnel"`
	SplitTunnelCIDRs string            `json:"splitTunnelCidrs"`
	LanBypass        bool              `json:"lanBypass"`
	MinimizeToTray   bool              `json:"minimizeToTray"`
	AutoConnect      bool              `json:"autoConnect"`
	AutoConnectID    string            `json:"autoConnectProfile,omitempty"`
	// PortalProbeURL is the plain-HTTP connectivity check used to detect a
	// captive portal. It is the only extra request besides the public-IP
	// check, and only runs when the user asks or AutoPortalDetect is on.
	PortalProbeURL   string `json:"portalProbeUrl"`
	AutoPortalDetect bool   `json:"autoPortalDetect"`
	// PerAppMode and PerAppExecutables implement Linux per-app split
	// tunneling with cgroup v2 + nftables/policy routing. Executables are
	// absolute /proc/PID/exe paths.
	PerAppMode        PerAppMode `json:"perAppMode"`
	PerAppExecutables []string   `json:"perAppExecutables,omitempty"`
}

// Default returns the settings a new vault starts with.
func Default() Settings {
	return Settings{
		RequireTunnelDNS: true,
		MtuMode:          MtuAuto,
		Compression:      CompressionCompatibility,
		Cipher:           CipherProfile,
		AutoCheckIP:      false,
		PublicIPURL:      "https://api.ipify.org",
		Theme:            ThemeSystem,
		KillSwitch:       false,
		SplitTunnel:      SplitOff,
		LanBypass:        false,
		MinimizeToTray:   false,
		AutoConnect:      false,
		PortalProbeURL:   "http://connectivitycheck.gstatic.com/generate_204",
		AutoPortalDetect: true,
		PerAppMode:       PerAppOff,
	}
}

// Normalize fills in defaults for zero values read from an older vault.
func (s Settings) Normalize() Settings {
	d := Default()
	if s.MtuMode == "" {
		s.MtuMode = d.MtuMode
	}
	if s.Compression == "" {
		s.Compression = d.Compression
	}
	if s.Cipher == "" {
		s.Cipher = d.Cipher
	}
	if s.PublicIPURL == "" {
		s.PublicIPURL = d.PublicIPURL
	}
	if s.Theme == "" {
		s.Theme = d.Theme
	}
	if s.SplitTunnel == "" {
		s.SplitTunnel = d.SplitTunnel
	}
	if s.PortalProbeURL == "" {
		s.PortalProbeURL = d.PortalProbeURL
	}
	if s.PerAppMode == "" {
		s.PerAppMode = d.PerAppMode
	}
	return s
}

// ParseCIDRList parses a comma/space separated list of CIDRs or bare IPs.
// Bare IPs become /32 (IPv4) or /128 (IPv6).
func ParseCIDRList(raw string) ([]string, error) {
	fields := strings.FieldsFunc(raw, func(r rune) bool {
		return r == ',' || r == ';' || r == '\n' || r == '\t' || r == ' '
	})
	out := make([]string, 0, len(fields))
	seen := map[string]bool{}
	for _, f := range fields {
		f = strings.TrimSpace(f)
		if f == "" {
			continue
		}
		var network *net.IPNet
		if strings.Contains(f, "/") {
			ip, n, err := net.ParseCIDR(f)
			if err != nil || ip == nil {
				return nil, fmt.Errorf("invalid network %q: use CIDR notation such as 10.0.0.0/8", f)
			}
			network = n
		} else {
			ip := net.ParseIP(f)
			if ip == nil {
				return nil, fmt.Errorf("invalid address %q", f)
			}
			bits := 32
			if ip.To4() == nil {
				bits = 128
			}
			network = &net.IPNet{IP: ip, Mask: net.CIDRMask(bits, bits)}
		}
		c := network.String()
		if !seen[c] {
			seen[c] = true
			out = append(out, c)
		}
	}
	return out, nil
}

// Validate checks values that would otherwise silently weaken protection.
func (s Settings) Validate() error {
	if _, err := ParseCIDRList(s.FallbackDNS); err != nil {
		return fmt.Errorf("fallback DNS: %w", err)
	}
	if s.SplitTunnel != SplitOff {
		if _, err := ParseCIDRList(s.SplitTunnelCIDRs); err != nil {
			return fmt.Errorf("split tunnel networks: %w", err)
		}
	}
	switch s.MtuMode {
	case MtuAuto, Mtu1500, Mtu1400, Mtu1280:
	default:
		return fmt.Errorf("unknown MTU mode %q", s.MtuMode)
	}
	switch s.Compression {
	case CompressionCompatibility, CompressionStrict:
	default:
		return fmt.Errorf("unknown compression policy %q", s.Compression)
	}
	switch s.Cipher {
	case CipherProfile, CipherAEADOnly:
	default:
		return fmt.Errorf("unknown cipher policy %q", s.Cipher)
	}
	switch s.Theme {
	case ThemeSystem, ThemeLight, ThemeDark:
	default:
		return fmt.Errorf("unknown theme %q", s.Theme)
	}
	if s.PortalProbeURL != "" {
		u, err := url.Parse(s.PortalProbeURL)
		if err != nil || u.Host == "" || (u.Scheme != "http" && u.Scheme != "https") {
			return fmt.Errorf("captive-portal probe URL must be an absolute http or https URL")
		}
	}
	switch s.PerAppMode {
	case PerAppOff, PerAppInclude, PerAppExclude:
	default:
		return fmt.Errorf("unknown per-app split mode %q", s.PerAppMode)
	}
	if s.PerAppMode != PerAppOff {
		if len(s.PerAppExecutables) == 0 {
			return fmt.Errorf("per-app split tunneling needs at least one executable")
		}
		seen := map[string]bool{}
		for _, exe := range s.PerAppExecutables {
			if !strings.HasPrefix(exe, "/") || strings.ContainsAny(exe, "\r\n\x00") {
				return fmt.Errorf("per-app executable must be an absolute path: %q", exe)
			}
			if seen[exe] {
				return fmt.Errorf("duplicate per-app executable %q", exe)
			}
			seen[exe] = true
		}
	}
	return nil
}
