package profile

import (
	"fmt"
	"strconv"
)

const ignorePushedTunMtu = "pull-filter ignore tun-mtu"

// WithTunMtuOverride appends `pull-filter ignore tun-mtu` and `tun-mtu N` so a
// smaller MTU survives a server push. It is idempotent.
func WithTunMtuOverride(config string, mtu int) string {
	if mtu <= 0 {
		return config
	}
	lines, trailing := splitConfig(config)
	existing := map[string]bool{}
	for _, line := range lines {
		existing[normalizeDirectiveLine(line)] = true
	}
	mtuLine := "tun-mtu " + strconv.Itoa(mtu)
	if !existing[ignorePushedTunMtu] {
		lines = append(lines, ignorePushedTunMtu)
	}
	if !existing[mtuLine] {
		lines = append(lines, mtuLine)
	}
	return joinConfig(lines, trailing)
}

func normalizeDirectiveLine(line string) string {
	name, value, ok := directive(line)
	if !ok {
		return ""
	}
	if value == "" {
		return name
	}
	return name + " " + value
}

// DescribeMTU returns the Settings explanation for an MTU mode.
func DescribeMTU(override int, isAuto bool) string {
	if isAuto {
		return "Automatic: use the MTU pushed by the VPN server (normally 1500 bytes)."
	}
	return fmt.Sprintf("Override the server-pushed MTU with %d bytes. Use a lower value when "+
		"large downloads stall on networks with a smaller path MTU.", override)
}
