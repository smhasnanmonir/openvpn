//go:build darwin

package serviceinstall

import (
	"fmt"
	"os"
	"os/exec"
	"os/user"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/smhasnanmonir/openvpn/desktop/internal/serviceproto"
)

const (
	macHelperPath = "/Library/PrivilegedHelperTools/" + Label
	macPlistPath  = "/Library/LaunchDaemons/" + Label + ".plist"
	macSystemDir  = "/Library/Application Support/OpenVPN Desktop"
	macOpenVPN    = "/Library/Application Support/OpenVPN Desktop/bin/openvpn"
)

func privileged() bool { return os.Geteuid() == 0 }

// InstalledVersion returns the version written by the last install, or "".
func InstalledVersion() string {
	return readVersion(filepath.Join(macSystemDir, "helper.version"))
}

func findOpenVPNSource(explicit string) (string, error) {
	if explicit != "" {
		if st, err := os.Stat(explicit); err == nil && st.Mode().IsRegular() {
			return explicit, nil
		}
		return "", fmt.Errorf("OpenVPN binary %s is not a regular file", explicit)
	}
	candidates := []string{}
	if env := os.Getenv("OPENVPN_DESKTOP_OPENVPN"); env != "" {
		candidates = append(candidates, env)
	}
	candidates = append(candidates,
		"/usr/local/sbin/openvpn",
		"/opt/homebrew/sbin/openvpn",
		"/usr/local/bin/openvpn",
		"/opt/homebrew/bin/openvpn",
	)
	for _, p := range candidates {
		if st, err := os.Stat(p); err == nil && st.Mode().IsRegular() {
			return p, nil
		}
	}
	if p, err := exec.LookPath("openvpn"); err == nil {
		if st, err := os.Stat(p); err == nil && st.Mode().IsRegular() {
			return p, nil
		}
	}
	return "", fmt.Errorf("OpenVPN 2.7+ was not found; install it from Homebrew or the official package first")
}

func configOwner(userConfig, userName string) (int, int, error) {
	name := userName
	if name == "" {
		if u, err := user.Current(); err == nil && u.Uid != "0" {
			name = u.Username
		}
	}
	if name == "" {
		parts := strings.Split(filepath.Clean(userConfig), string(filepath.Separator))
		for i, p := range parts {
			if p == "Users" && i+1 < len(parts) {
				name = parts[i+1]
				break
			}
		}
	}
	if name == "" {
		return 0, 0, fmt.Errorf("cannot determine the GUI user for %s; pass --user", userConfig)
	}
	u, err := user.Lookup(name)
	if err != nil {
		return 0, 0, fmt.Errorf("look up user %q: %w", name, err)
	}
	uid, err := strconv.Atoi(u.Uid)
	if err != nil {
		return 0, 0, err
	}
	gid, err := strconv.Atoi(u.Gid)
	if err != nil {
		return 0, 0, err
	}
	return uid, gid, nil
}

// Install copies the helper and OpenVPN into root-owned locations, writes the
// launchd daemon and tokens, and reloads the daemon.
func Install(opts Options) error {
	if !privileged() {
		return fmt.Errorf("install-service must run as root")
	}
	opts, err := normalize(opts)
	if err != nil {
		return err
	}
	source, err := findOpenVPNSource(opts.OpenVPNPath)
	if err != nil {
		return err
	}
	// The GUI runs the helper from a signed app bundle. Verify that signature
	// before copying it to a root-owned location; a terminal build outside an
	// app bundle is allowed for development.
	if strings.Contains(opts.HelperPath, ".app/Contents/") {
		if out, err := exec.Command("/usr/bin/codesign", "--verify", "--strict", opts.HelperPath).CombinedOutput(); err != nil {
			return fmt.Errorf("the bundled helper's code signature is invalid: %w: %s", err, strings.TrimSpace(string(out)))
		}
	}
	if err := os.MkdirAll(filepath.Dir(macHelperPath), 0o755); err != nil {
		return err
	}
	if !sameFile(opts.HelperPath, macHelperPath) {
		if err := copyFile(opts.HelperPath, macHelperPath, 0o755); err != nil {
			return fmt.Errorf("install helper: %w", err)
		}
	}
	if err := os.Chown(macHelperPath, 0, 0); err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(macOpenVPN), 0o755); err != nil {
		return err
	}
	if !sameFile(source, macOpenVPN) {
		if err := copyFile(source, macOpenVPN, 0o755); err != nil {
			return fmt.Errorf("copy OpenVPN to %s: %w", macOpenVPN, err)
		}
	}
	if err := os.Chown(macOpenVPN, 0, 0); err != nil {
		return err
	}
	if err := os.MkdirAll(macSystemDir, 0o755); err != nil {
		return err
	}
	if err := os.Chmod(macSystemDir, 0o755); err != nil {
		return err
	}
	if err := writeVersion(macSystemDir, opts.Version); err != nil {
		return err
	}

	if _, err := ensureConfig(opts); err != nil {
		return err
	}
	systemPath, err := serviceproto.SystemPath()
	if err != nil {
		return err
	}
	if err := os.Chmod(systemPath, 0o600); err != nil {
		return err
	}
	if err := os.Chown(systemPath, 0, 0); err != nil {
		return err
	}
	uid, gid, err := configOwner(opts.UserConfig, opts.UserName)
	if err != nil {
		return err
	}
	if err := os.Chmod(filepath.Dir(opts.UserConfig), 0o700); err != nil {
		return err
	}
	if err := os.Chown(filepath.Dir(opts.UserConfig), uid, gid); err != nil {
		return err
	}
	if err := os.Chmod(opts.UserConfig, 0o600); err != nil {
		return err
	}
	if err := os.Chown(opts.UserConfig, uid, gid); err != nil {
		return err
	}
	plist := fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>%s</string>
  <key>ProgramArguments</key>
  <array>
    <string>%s</string>
    <string>serve</string>
    <string>--config</string>
    <string>%s</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ProcessType</key><string>Interactive</string>
</dict>
</plist>
`, Label, macHelperPath, systemPath)
	if err := os.WriteFile(macPlistPath, []byte(plist), 0o644); err != nil {
		return err
	}
	if err := os.Chown(macPlistPath, 0, 0); err != nil {
		return err
	}
	_ = exec.Command("launchctl", "bootout", "system/"+Label).Run()
	var out []byte
	var errBootstrap error
	for i := 0; i < 5; i++ {
		out, errBootstrap = exec.Command("launchctl", "bootstrap", "system", macPlistPath).CombinedOutput()
		if errBootstrap == nil {
			break
		}
		time.Sleep(200 * time.Millisecond)
	}
	if errBootstrap != nil {
		return fmt.Errorf("launchctl bootstrap: %w: %s", errBootstrap, strings.TrimSpace(string(out)))
	}
	_ = exec.Command("launchctl", "enable", "system/"+Label).Run()
	return nil
}

// Uninstall removes the daemon. Purge also removes its configuration and
// root-owned OpenVPN copy.
func Uninstall(opts Options) error {
	if !privileged() {
		return fmt.Errorf("uninstall-service must run as root")
	}
	_ = exec.Command("launchctl", "bootout", "system/"+Label).Run()
	_ = os.Remove(macPlistPath)
	_ = os.Remove(macHelperPath)
	if opts.Purge {
		if systemPath, err := serviceproto.SystemPath(); err == nil {
			_ = os.Remove(systemPath)
		}
		userPath := opts.UserConfig
		if userPath == "" {
			userPath, _ = serviceproto.UserPath()
		}
		_ = os.Remove(userPath)
		_ = os.Remove(macOpenVPN)
		_ = os.Remove(filepath.Dir(macOpenVPN))
		_ = os.Remove(filepath.Join(macSystemDir, "helper.version"))
	}
	return nil
}
