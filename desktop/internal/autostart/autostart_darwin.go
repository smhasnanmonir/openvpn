//go:build darwin

package autostart

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
)

const label = "com.smhasnanmonir.openvpndesktop"

// Supported reports whether a per-user LaunchAgent is available.
func Supported() bool { return true }

// Path returns the LaunchAgent plist.
func Path() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, "Library", "LaunchAgents", label+".plist"), nil
}

// Installed reports whether the LaunchAgent plist exists.
func Installed() bool {
	p, err := Path()
	if err != nil {
		return false
	}
	st, err := os.Stat(p)
	return err == nil && !st.IsDir()
}

// Install writes a LaunchAgent that opens the app locked at login. The app
// connects only after the vault is unlocked.
func Install(execPath string) (string, error) {
	if !filepath.IsAbs(execPath) {
		return "", fmt.Errorf("autostart needs an absolute executable path, got %q", execPath)
	}
	if strings.ContainsAny(execPath, "\r\n") {
		return "", fmt.Errorf("executable path contains a newline")
	}
	path, err := Path()
	if err != nil {
		return "", err
	}
	plist := fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>%s</string>
  <key>ProgramArguments</key>
  <array>
    <string>%s</string>
    <string>--autoconnect</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><false/>
  <key>ProcessType</key><string>Interactive</string>
  <key>LimitLoadToSessionType</key><string>Aqua</string>
</dict>
</plist>
`, label, xmlEscape(execPath))
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return "", err
	}
	if err := os.WriteFile(path, []byte(plist), 0o644); err != nil {
		return "", err
	}
	// Load it now as well as at the next login. The GUI runs in the Aqua
	// session, so its uid owns the agent.
	uid := strconv.Itoa(os.Getuid())
	_ = exec.Command("launchctl", "bootout", "gui/"+uid+"/"+label).Run()
	_ = exec.Command("launchctl", "bootstrap", "gui/"+uid, path).Run()
	return path, nil
}

// Remove deletes the LaunchAgent and unloads it.
func Remove() error {
	path, err := Path()
	if err != nil {
		return err
	}
	uid := strconv.Itoa(os.Getuid())
	_ = exec.Command("launchctl", "bootout", "gui/"+uid+"/"+label).Run()
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}

func xmlEscape(s string) string {
	r := strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;", `"`, "&quot;", "'", "&apos;")
	return r.Replace(s)
}
