// Package serviceinstall installs the privileged helper for the current
// platform: a Windows service, a macOS launchd daemon, or a Linux polkit
// helper. It is called by `openvpn-desktop-helper install-service` after the
// GUI has obtained administrator approval, so every path here runs as root.
package serviceinstall

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/smhasnanmonir/openvpn/desktop/internal/serviceproto"
)

// Label is the macOS launchd label and the Linux polkit action id.
const Label = "com.smhasnanmonir.openvpndesktop.helper"

// WindowsServiceName is the Windows service name.
const WindowsServiceName = "OpenVPNDesktopHelper"

// Options describes an installation. HelperPath defaults to the running
// executable; UserConfig is the per-user token file the GUI reads.
type Options struct {
	HelperPath  string
	UserConfig  string
	UserName    string
	UserSID     string
	ListenAddr  string
	OpenVPNPath string
	Version     string
	Purge       bool
}

func normalize(opts Options) (Options, error) {
	if opts.HelperPath == "" {
		exe, err := os.Executable()
		if err != nil {
			return opts, err
		}
		opts.HelperPath = exe
	}
	abs, err := filepath.Abs(opts.HelperPath)
	if err != nil {
		return opts, err
	}
	opts.HelperPath = abs
	st, err := os.Stat(opts.HelperPath)
	if err != nil {
		return opts, fmt.Errorf("helper binary: %w", err)
	}
	if !st.Mode().IsRegular() {
		return opts, fmt.Errorf("%s is not a regular file", opts.HelperPath)
	}
	if opts.ListenAddr == "" {
		opts.ListenAddr = serviceproto.ListenAddr
	}
	if opts.UserConfig == "" {
		p, err := serviceproto.UserPath()
		if err != nil {
			return opts, fmt.Errorf("user service config: %w", err)
		}
		opts.UserConfig = p
	}
	userConfig, err := filepath.Abs(opts.UserConfig)
	if err != nil {
		return opts, err
	}
	opts.UserConfig = userConfig
	return opts, nil
}

// ensureConfig reuses a valid system token so an upgrade does not invalidate
// the GUI's per-user token; it creates a new one when the file is missing or
// invalid. The per-user file is always rewritten so a repaired install works.
func ensureConfig(opts Options) (serviceproto.Config, error) {
	systemPath, err := serviceproto.SystemPath()
	if err != nil {
		return serviceproto.Config{}, err
	}
	cfg, err := serviceproto.Load(systemPath)
	if err != nil {
		token, tokenErr := serviceproto.NewToken()
		if tokenErr != nil {
			return serviceproto.Config{}, tokenErr
		}
		cfg = serviceproto.Config{Addr: opts.ListenAddr, Token: token}
		if saveErr := serviceproto.Save(systemPath, cfg); saveErr != nil {
			return serviceproto.Config{}, fmt.Errorf("write %s: %w", systemPath, saveErr)
		}
	}
	if opts.UserConfig != "" {
		if err := serviceproto.Save(opts.UserConfig, cfg); err != nil {
			return serviceproto.Config{}, fmt.Errorf("write %s: %w", opts.UserConfig, err)
		}
	}
	return cfg, nil
}

func copyFile(src, dst string, mode os.FileMode) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(dst), ".install-*")
	if err != nil {
		return err
	}
	name := tmp.Name()
	defer os.Remove(name)
	if _, err := io.Copy(tmp, in); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Chmod(mode); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(name, dst)
}

func sameFile(a, b string) bool {
	ai, err := os.Stat(a)
	if err != nil {
		return false
	}
	bi, err := os.Stat(b)
	if err != nil {
		return false
	}
	return os.SameFile(ai, bi)
}

func envOr(values ...string) string {
	for _, v := range values {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}

func writeVersion(dir, version string) error {
	if version == "" {
		return nil
	}
	return os.WriteFile(filepath.Join(dir, "helper.version"), []byte(version+"\n"), 0o644)
}

func readVersion(path string) string {
	data, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(data))
}

var errUnsupported = errors.New("the privileged helper installer is not implemented for this platform")
