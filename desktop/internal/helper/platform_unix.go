//go:build !windows

package helper

import (
	"os"
	"os/exec"
	"runtime"
	"syscall"
	"time"
)

func minimalEnv(opt runOptions) []string {
	return []string{
		"HOME=/root",
		"PATH=/usr/sbin:/usr/bin:/sbin:/bin",
		"LC_ALL=C",
		"OPENVPN_DESKTOP_REQUIRE_DNS=" + boolFlag(opt.requireDNS),
		"OPENVPN_DESKTOP_FALLBACK_DNS=" + opt.fallbackDNS,
		"OPENVPN_DESKTOP_FULL_DNS=" + boolFlag(opt.fullDNS),
		"OPENVPN_DESKTOP_KILL_SWITCH=" + boolFlag(opt.killSwitch),
		"OPENVPN_DESKTOP_ALLOW_LAN=" + boolFlag(opt.allowLAN),
	}
}

func isPrivileged() bool { return os.Geteuid() == 0 }

func unsafeMode(st os.FileInfo) bool { return st.Mode().Perm()&0o022 != 0 }

func supportsKillSwitch() bool { return runtime.GOOS == "linux" }

func dnsUpdownArgs() []string { return []string{"--dns-updown", "disable"} }

func platformRunArgs(self string) []string {
	return []string{
		"--script-security", "2",
		"--route-up", self + " dns-up",
		"--down", self + " dns-down",
		"--down-pre",
	}
}

func secureDir(dir string) error { return os.Chmod(dir, 0o700) }

func secureTempDir() (string, error) {
	dir, err := os.MkdirTemp("", "openvpn-desktop-svc-")
	if err != nil {
		return "", err
	}
	if err := secureDir(dir); err != nil {
		os.RemoveAll(dir)
		return "", err
	}
	return dir, nil
}

func configureServiceChild(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
}

func terminateServiceChild(cmd *exec.Cmd) error {
	if cmd == nil || cmd.Process == nil {
		return nil
	}
	_ = cmd.Process.Signal(syscall.SIGTERM)
	pid := cmd.Process.Pid
	go func() {
		time.Sleep(10 * time.Second)
		_ = syscall.Kill(-pid, syscall.SIGKILL)
		_ = cmd.Process.Kill()
	}()
	return nil
}
