//go:build linux

package serviceinstall

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"syscall"

	"github.com/smhasnanmonir/openvpn/desktop/internal/serviceproto"
)

const (
	linuxHelper  = "/usr/local/libexec/openvpn-desktop/openvpn-desktop-helper"
	linuxPolicy  = "/usr/share/polkit-1/actions/" + Label + ".policy"
	linuxMarker  = "/usr/local/libexec/openvpn-desktop/privilege-mode"
	linuxLibexec = "/usr/local/libexec/openvpn-desktop"
	linuxOptDir  = "/opt/openvpn-desktop"
)

type linuxPaths struct {
	dir    string
	helper string
	marker string
}

func linuxTarget(src string) linuxPaths {
	dir := linuxLibexec
	if strings.HasPrefix(filepath.Clean(src), linuxOptDir+string(filepath.Separator)) {
		dir = linuxOptDir
	}
	return linuxPaths{
		dir:    dir,
		helper: filepath.Join(dir, "openvpn-desktop-helper"),
		marker: filepath.Join(dir, "privilege-mode"),
	}
}

func privileged() bool { return os.Geteuid() == 0 }

// InstalledVersion returns the version written by the last install, or "".
func InstalledVersion() string {
	for _, dir := range []string{linuxOptDir, linuxLibexec} {
		if v := readVersion(filepath.Join(dir, "helper.version")); v != "" {
			return v
		}
	}
	return ""
}

// Install copies the helper into a root-owned directory and writes a polkit
// action that runs that exact binary. A package's /opt helper is updated in
// place; a source install goes to /usr/local/libexec.
func Install(opts Options) error {
	if !privileged() {
		return fmt.Errorf("install must run as root")
	}
	opts, err := normalize(opts)
	if err != nil {
		return err
	}
	if st, err := os.Stat(opts.HelperPath); err == nil {
		if sys, ok := st.Sys().(*syscall.Stat_t); ok && sys.Uid != 0 {
			return fmt.Errorf("%s is owned by uid %d; install a root-owned helper", opts.HelperPath, sys.Uid)
		}
	}
	paths := linuxTarget(opts.HelperPath)
	if err := os.MkdirAll(paths.dir, 0o755); err != nil {
		return err
	}
	if !sameFile(opts.HelperPath, paths.helper) {
		if err := copyFile(opts.HelperPath, paths.helper, 0o755); err != nil {
			return err
		}
	}
	if err := os.Chown(paths.helper, 0, 0); err != nil {
		return err
	}
	if err := os.Chmod(paths.helper, 0o755); err != nil {
		return err
	}
	policy := fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE policyconfig PUBLIC
 "-//freedesktop//DTD PolicyKit Policy Configuration 1.0//EN"
 "http://www.freedesktop.org/standards/PolicyKit/1/policyconfig.dtd">
<policyconfig>
  <vendor>OpenVPN Desktop</vendor>
  <action id="%s">
    <description>Connect the OpenVPN Desktop VPN</description>
    <message>Authentication is required to run the OpenVPN Desktop privileged helper.</message>
    <defaults>
      <allow_any>auth_admin</allow_any>
      <allow_inactive>auth_admin</allow_inactive>
      <allow_active>auth_admin_keep</allow_active>
    </defaults>
    <annotate key="org.freedesktop.policykit.exec.path">%s</annotate>
    <annotate key="org.freedesktop.policykit.exec.allow_gui">true</annotate>
  </action>
</policyconfig>
`, Label, paths.helper)
	if err := os.MkdirAll(filepath.Dir(linuxPolicy), 0o755); err != nil {
		return err
	}
	if err := os.WriteFile(linuxPolicy, []byte(policy), 0o644); err != nil {
		return err
	}
	if err := writeVersion(paths.dir, opts.Version); err != nil {
		return err
	}
	if err := os.WriteFile(paths.marker, []byte("polkit\n"), 0o644); err != nil {
		return err
	}
	if err := os.Chown(paths.marker, 0, 0); err != nil {
		return err
	}
	return nil
}

// Uninstall removes the polkit action and helper. It never removes the
// user's vault or profiles.
func Uninstall(opts Options) error {
	if !privileged() {
		return fmt.Errorf("uninstall must run as root")
	}
	_ = os.Remove(linuxPolicy)
	for _, dir := range []string{linuxOptDir, linuxLibexec} {
		_ = os.Remove(filepath.Join(dir, "privilege-mode"))
		_ = os.Remove(filepath.Join(dir, "helper.version"))
		_ = os.Remove(filepath.Join(dir, "openvpn-desktop-helper"))
	}
	if opts.Purge {
		if p, err := serviceproto.SystemPath(); err == nil {
			_ = os.Remove(p)
		}
	}
	return nil
}
