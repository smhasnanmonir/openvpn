//go:build windows

package autostart

import (
	"fmt"
	"path/filepath"
	"strings"

	"golang.org/x/sys/windows/registry"
)

const runKey = `Software\Microsoft\Windows\CurrentVersion\Run`
const valueName = "OpenVPN Desktop"

// Supported reports whether the per-user Run key is available.
func Supported() bool { return true }

// Path names the registry value for diagnostics.
func Path() (string, error) { return `HKCU\` + runKey + `\` + valueName, nil }

// Installed reports whether the Run value exists.
func Installed() bool {
	k, err := registry.OpenKey(registry.CURRENT_USER, runKey, registry.QUERY_VALUE)
	if err != nil {
		return false
	}
	defer k.Close()
	_, _, err = k.GetStringValue(valueName)
	return err == nil
}

// Install writes the per-user Run value. It starts the app locked; the app only
// connects after the vault is unlocked.
func Install(execPath string) (string, error) {
	if !filepath.IsAbs(execPath) {
		return "", fmt.Errorf("autostart needs an absolute executable path, got %q", execPath)
	}
	if strings.ContainsAny(execPath, "\r\n") {
		return "", fmt.Errorf("executable path contains a newline")
	}
	k, _, err := registry.CreateKey(registry.CURRENT_USER, runKey, registry.SET_VALUE)
	if err != nil {
		return "", err
	}
	defer k.Close()
	value := `"` + execPath + `" --autoconnect`
	if err := k.SetStringValue(valueName, value); err != nil {
		return "", err
	}
	return `HKCU\` + runKey + `\` + valueName, nil
}

// Remove deletes the Run value. A missing value is not an error.
func Remove() error {
	k, err := registry.OpenKey(registry.CURRENT_USER, runKey, registry.SET_VALUE)
	if err != nil {
		return nil
	}
	defer k.Close()
	if err := k.DeleteValue(valueName); err != nil && err != registry.ErrNotExist {
		return err
	}
	return nil
}
