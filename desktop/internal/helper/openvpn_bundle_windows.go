//go:build windows

package helper

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

// msiexec reports "success, reboot required" with 3010. The TAP driver can
// still be in use by another adapter, so treat it as installed.
const msiexecRebootRequired = 3010

// ensureOpenVPNAvailable installs the bundled OpenVPN engine when the machine
// has none, so `install-service` leaves a working client behind. It returns a
// message for the caller to print when it installed something.
//
// It runs after the helper service exists: the GUI waits for the helper token,
// and the installer takes long enough that running it first would exhaust that
// wait.
func ensureOpenVPNAvailable() (string, error) {
	if _, err := findOpenVPN(); err == nil {
		return "", nil
	}
	expected, ok := bundledOpenVPNSHA256(runtime.GOARCH)
	if !ok {
		return "", fmt.Errorf("openvpn.exe was not found and this build bundles no installer for %s", runtime.GOARCH)
	}
	exe, err := os.Executable()
	if err != nil {
		return "", err
	}
	src := bundledMSIPath(filepath.Dir(exe))
	if st, err := os.Stat(src); err != nil || !st.Mode().IsRegular() {
		return "", fmt.Errorf("openvpn.exe was not found; install OpenVPN %s or place %s next to the app", bundledOpenVPNVersion, bundledOpenVPNMSI)
	}

	// Stage into a directory only SYSTEM and Administrators can write, then
	// hash the copy. Installing straight from the app directory would let
	// anything running as the user replace the installer after it is checked.
	dir, err := secureTempDir()
	if err != nil {
		return "", err
	}
	defer os.RemoveAll(dir)
	staged := filepath.Join(dir, bundledOpenVPNMSI)
	if err := copyRegularFile(src, staged, 0o600); err != nil {
		return "", err
	}
	got, err := fileSHA256(staged)
	if err != nil {
		return "", err
	}
	if !strings.EqualFold(got, expected) {
		return "", fmt.Errorf("refusing to install %s: sha256 %s, want %s", src, got, expected)
	}

	// The elevated helper runs in a console window the user can see while this
	// takes a minute, so say what is happening instead of sitting silent.
	fmt.Printf("installing OpenVPN %s (bundled)\n", bundledOpenVPNVersion)
	logPath := filepath.Join(dir, "openvpn-install.log")
	out, err := exec.Command("msiexec.exe", "/i", staged, "/qn", "/norestart", "/L*v", logPath).CombinedOutput()
	if err != nil {
		exit, ok := err.(*exec.ExitError)
		if !ok || exit.ExitCode() != msiexecRebootRequired {
			return "", fmt.Errorf("install OpenVPN %s: %w: %s (log: %s)", bundledOpenVPNVersion, err, strings.TrimSpace(string(out)), logPath)
		}
	}
	if _, err := findOpenVPN(); err != nil {
		return "", fmt.Errorf("OpenVPN %s was installed but %w", bundledOpenVPNVersion, err)
	}
	return "installed OpenVPN " + bundledOpenVPNVersion, nil
}
