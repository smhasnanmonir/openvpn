//go:build unix

package ovpn

import (
	"os/exec"
	"syscall"
)

// configureProcessGroup puts the helper (and its OpenVPN child) in its own
// process group so Stop can terminate the whole tree, not just pkexec/sudo.
func configureProcessGroup(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
}

func killProcessGroup(cmd *exec.Cmd) {
	if cmd == nil || cmd.Process == nil {
		return
	}
	// Negative pid signals the process group. Fall back to the process itself
	// if it already left the group.
	_ = syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
	_ = cmd.Process.Kill()
}
