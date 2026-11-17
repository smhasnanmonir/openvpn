//go:build linux

package ovpn

import (
	"os"
	"syscall"
)

// helperRootOwned reports whether path is a root-owned regular file that is
// not writable by group/other. An unprivileged GUI must never ask pkexec or
// sudo to run a user-writable helper.
func helperRootOwned(path string) bool {
	st, err := os.Stat(path)
	if err != nil || !st.Mode().IsRegular() || st.Mode().Perm()&0o022 != 0 {
		return false
	}
	sys, ok := st.Sys().(*syscall.Stat_t)
	return ok && sys.Uid == 0
}
