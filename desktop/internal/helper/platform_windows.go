//go:build windows

package helper

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"

	"golang.org/x/sys/windows"
)

func minimalEnv(runOptions) []string { return nil } // inherit the service's System environment

func isPrivileged() bool {
	token := windows.GetCurrentProcessToken()
	return token.IsElevated()
}

func findOpenVPN() (string, error) {
	if p := os.Getenv("OPENVPN_DESKTOP_OPENVPN"); p != "" {
		if st, err := os.Stat(p); err == nil && st.Mode().IsRegular() {
			return p, nil
		}
	}
	var candidates []string
	if pf := os.Getenv("ProgramFiles"); pf != "" {
		candidates = append(candidates, filepath.Join(pf, "OpenVPN", "bin", "openvpn.exe"))
	}
	if pf := os.Getenv("ProgramFiles(x86)"); pf != "" {
		candidates = append(candidates, filepath.Join(pf, "OpenVPN", "bin", "openvpn.exe"))
	}
	candidates = append(candidates, `C:\Program Files\OpenVPN\bin\openvpn.exe`)
	for _, p := range candidates {
		if st, err := os.Stat(p); err == nil && st.Mode().IsRegular() {
			return p, nil
		}
	}
	return "", fmt.Errorf("openvpn.exe was not found; install OpenVPN 2.7+ with the TAP-Windows6 driver")
}

func unsafeMode(os.FileInfo) bool { return false }

func supportsKillSwitch() bool { return false }

func dnsUpdownArgs() []string { return nil }

func platformRunArgs(string) []string {
	// Windows OpenVPN applies pushed DNS through the TAP driver; the built-in
	// block-outside-dns prevents non-tunnel DNS from leaking. Select TAP
	// explicitly so a Wintun DLL on PATH cannot silently change the DNS path.
	return []string{"--windows-driver", "tap", "--block-outside-dns", "--register-dns"}
}

func secureDir(dir string) error {
	sd, err := windows.SecurityDescriptorFromString("D:P(A;;FA;;;SY)(A;;FA;;;BA)")
	if err != nil {
		return err
	}
	dacl, _, err := sd.DACL()
	if err != nil {
		return err
	}
	return windows.SetNamedSecurityInfo(
		dir,
		windows.SE_FILE_OBJECT,
		windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION,
		nil, nil, dacl, nil,
	)
}

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

func configureServiceChild(*exec.Cmd) {}

func terminateServiceChild(cmd *exec.Cmd) error {
	if cmd == nil || cmd.Process == nil {
		return nil
	}
	// /T kills the OpenVPN child of the `run` helper as well.
	taskkill := "taskkill"
	if root := os.Getenv("SystemRoot"); root != "" {
		taskkill = filepath.Join(root, "System32", "taskkill.exe")
	}
	_ = exec.Command(taskkill, "/T", "/F", "/PID", strconv.Itoa(cmd.Process.Pid)).Run()
	return cmd.Process.Kill()
}
