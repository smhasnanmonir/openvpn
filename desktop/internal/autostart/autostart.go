//go:build linux

// Package autostart manages an XDG autostart entry so the app opens after the
// user logs in. It deliberately does not store the vault password: the app
// starts locked, and --autoconnect only connects after the user unlocks it.
package autostart

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// Supported reports whether XDG autostart applies to this platform.
func Supported() bool { return true }

// Path returns the per-user autostart desktop entry.
func Path() (string, error) {
	if !Supported() {
		return "", errors.New("start at login is implemented for Linux (XDG autostart)")
	}
	dir, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "autostart", "openvpn-desktop.desktop"), nil
}

// Installed reports whether the entry exists.
func Installed() bool {
	if !Supported() {
		return false
	}
	p, err := Path()
	if err != nil {
		return false
	}
	st, err := os.Stat(p)
	return err == nil && !st.IsDir()
}

// DesktopEntry builds the XDG desktop entry for execPath.
func DesktopEntry(execPath string) (string, error) {
	if !filepath.IsAbs(execPath) {
		return "", fmt.Errorf("autostart needs an absolute executable path, got %q", execPath)
	}
	if strings.ContainsAny(execPath, "\r\n") {
		return "", fmt.Errorf("executable path contains a newline")
	}
	return `[Desktop Entry]
Type=Application
Version=1.0
Name=OpenVPN Desktop
Comment=Open the VPN client after login; it connects after the vault is unlocked
Exec=` + quoteExec(execPath) + ` --autoconnect
Terminal=false
X-GNOME-Autostart-enabled=true
StartupNotify=false
`, nil
}

// Install writes (or replaces) the autostart entry.
func Install(execPath string) (string, error) {
	if !Supported() {
		return "", errors.New("start at login is implemented for Linux (XDG autostart)")
	}
	entry, err := DesktopEntry(execPath)
	if err != nil {
		return "", err
	}
	path, err := Path()
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return "", err
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), ".autostart-*")
	if err != nil {
		return "", err
	}
	name := tmp.Name()
	defer os.Remove(name)
	if err := tmp.Chmod(0o644); err != nil {
		tmp.Close()
		return "", err
	}
	if _, err := tmp.WriteString(entry); err != nil {
		tmp.Close()
		return "", err
	}
	if err := tmp.Close(); err != nil {
		return "", err
	}
	if err := os.Rename(name, path); err != nil {
		return "", err
	}
	return path, nil
}

// Remove deletes the autostart entry.
func Remove() error {
	if !Supported() {
		return nil
	}
	path, err := Path()
	if err != nil {
		return err
	}
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}

// quoteExec quotes a path for the Exec key, escaping the characters the
// desktop-entry specification reserves inside double quotes.
func quoteExec(s string) string {
	r := strings.NewReplacer(`\`, `\\`, `"`, `\"`, "`", "\\`", `$`, `\$`)
	return `"` + r.Replace(s) + `"`
}
