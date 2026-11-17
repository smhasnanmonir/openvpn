//go:build linux

package ovpn

import (
	"fmt"
	"os"
	"os/exec"
	"strings"
	"syscall"
)

func installSupported(bundled string) bool {
	if _, err := exec.LookPath("pkexec"); err != nil {
		return false
	}
	st, err := os.Stat(bundled)
	if err != nil || !st.Mode().IsRegular() {
		return false
	}
	if sys, ok := st.Sys().(*syscall.Stat_t); ok {
		return sys.Uid == 0
	}
	return false
}

// InstallHelper runs the root-owned packaged helper through pkexec. The helper
// installs itself into /usr/local/libexec and writes the polkit action.
func InstallHelper(h HelperInfo) error {
	src := h.BundledPath
	if src == "" {
		src = h.Path
	}
	if src == "" {
		return fmt.Errorf("the bundled helper was not found")
	}
	if !installSupported(src) {
		return fmt.Errorf("run the packaged installer or scripts/install-helper.sh as root; the bundled helper is not root-owned")
	}
	cmd := exec.Command("pkexec", src, "install", "--helper", src)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("pkexec install failed: %w: %s", err, strings.TrimSpace(string(out)))
	}
	return nil
}
