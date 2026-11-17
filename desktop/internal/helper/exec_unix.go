//go:build unix

package helper

import (
	"os"
	"syscall"
)

// ownershipOK requires a root-owned executable. The helper only runs the
// distribution's OpenVPN/nft/resolvectl binaries.
func ownershipOK(st os.FileInfo) bool {
	sys, ok := st.Sys().(*syscall.Stat_t)
	return ok && sys.Uid == 0
}
