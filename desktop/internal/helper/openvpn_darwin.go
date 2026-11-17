//go:build darwin

package helper

import (
	"fmt"
	"os"
	"syscall"
)

// findOpenVPN prefers the root-owned copy made by the installer, so a
// Homebrew binary owned by the login user can never be replaced and then run
// as root by the daemon. The remaining candidates must also be root-owned;
// this is a hard requirement, not a mode-bit heuristic.
func findOpenVPN() (string, error) {
	candidates := []string{}
	if p := os.Getenv("OPENVPN_DESKTOP_OPENVPN"); p != "" {
		candidates = append(candidates, p)
	}
	candidates = append(candidates,
		"/Library/Application Support/OpenVPN Desktop/bin/openvpn",
		"/usr/local/sbin/openvpn",
		"/opt/homebrew/sbin/openvpn",
		"/usr/local/bin/openvpn",
		"/opt/homebrew/bin/openvpn",
	)
	for _, p := range candidates {
		st, err := os.Stat(p)
		if err != nil || !st.Mode().IsRegular() {
			continue
		}
		if st.Mode().Perm()&0o022 != 0 {
			return "", fmt.Errorf("%s is writable by group/other users; refusing to run it as root", p)
		}
		if sys, ok := st.Sys().(*syscall.Stat_t); ok && sys.Uid != 0 {
			return "", fmt.Errorf("%s is owned by uid %d, not root; run the installer so it makes a root-owned copy", p, sys.Uid)
		}
		return p, nil
	}
	return "", fmt.Errorf("openvpn was not found; install OpenVPN 2.7+ (Homebrew or official) and run the installer")
}
