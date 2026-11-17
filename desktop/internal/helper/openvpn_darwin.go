//go:build darwin

package helper

import (
	"fmt"
	"os"
)

// findOpenVPN accepts the usual Homebrew and /usr/local locations. Unlike
// Linux, Homebrew binaries are user-owned; the helper requires that the file is
// not group/world writable, and documents the residual trust in the service
// security notes. A root-owned binary is preferred.
func findOpenVPN() (string, error) {
	candidates := []string{}
	if p := os.Getenv("OPENVPN_DESKTOP_OPENVPN"); p != "" {
		candidates = append(candidates, p)
	}
	candidates = append(candidates,
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
		return p, nil
	}
	return "", fmt.Errorf("openvpn was not found; install OpenVPN 2.7+ (Homebrew or the official package)")
}
