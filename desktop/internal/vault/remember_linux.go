//go:build linux

package vault

import (
	"fmt"
	"os/exec"
	"strings"
)

const rememberService = "openvpn-desktop-vault"

// RememberSupported reports whether libsecret is available. secret-tool reads
// the secret from stdin, so the password never appears in the process list.
func RememberSupported() bool {
	_, err := exec.LookPath("secret-tool")
	return err == nil
}

// SaveRemembered stores the master password in the login keyring.
func SaveRemembered(vaultPath, password string) error {
	if !RememberSupported() {
		return ErrRememberUnsupported
	}
	cmd := exec.Command("secret-tool", "store",
		"--label", "OpenVPN Desktop vault",
		"service", rememberService, "vault", vaultPath)
	cmd.Stdin = strings.NewReader(password)
	if out, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("secret-tool store: %w: %s", err, strings.TrimSpace(string(out)))
	}
	return nil
}

// LoadRemembered returns the stored password, or "" when there is none.
func LoadRemembered(vaultPath string) string {
	out, err := exec.Command("secret-tool", "lookup",
		"service", rememberService, "vault", vaultPath).Output()
	if err != nil {
		return ""
	}
	return strings.TrimRight(string(out), "\n")
}

// ClearRemembered removes the stored password.
func ClearRemembered(vaultPath string) error {
	if RememberSupported() {
		_ = exec.Command("secret-tool", "clear",
			"service", rememberService, "vault", vaultPath).Run()
	}
	return removeRemembered(vaultPath)
}
