package ovpn

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"

	"github.com/smhasnanmonir/openvpn/desktop/internal/serviceproto"
)

// PrivilegeMode selects how the privileged helper is launched.
type PrivilegeMode string

const (
	PrivilegeAuto    PrivilegeMode = "auto"
	PrivilegeDirect  PrivilegeMode = "direct"
	PrivilegeSudo    PrivilegeMode = "sudo"
	PrivilegePkexec  PrivilegeMode = "pkexec"
	PrivilegeService PrivilegeMode = "service"
)

// HelperInfo describes how the GUI will launch the privileged helper.
type HelperInfo struct {
	Path         string
	Mode         PrivilegeMode
	Available    bool
	Detail       string
	ServiceAddr  string
	ServiceToken string
}

// FindHelper locates the helper binary and decides how to run it. The helper
// must be root-owned and either setuid, launched by pkexec, or launched with
// passwordless sudo. The GUI never runs openvpn itself when unprivileged.
func FindHelper(selfDir string) HelperInfo {
	if addr, token := os.Getenv("OPENVPN_DESKTOP_SERVICE_ADDR"), os.Getenv("OPENVPN_DESKTOP_SERVICE_TOKEN"); addr != "" && token != "" {
		return HelperInfo{Mode: PrivilegeService, ServiceAddr: addr, ServiceToken: token, Available: true, Detail: "helper service from the environment"}
	}
	if runtime.GOOS == "windows" || runtime.GOOS == "darwin" {
		return findServiceHelper()
	}
	if env := os.Getenv("OPENVPN_DESKTOP_HELPER"); env != "" {
		if st, err := os.Stat(env); err == nil && !st.IsDir() {
			return HelperInfo{Path: env, Mode: PrivilegeDirect, Available: true, Detail: "helper from OPENVPN_DESKTOP_HELPER"}
		}
		return HelperInfo{Detail: "OPENVPN_DESKTOP_HELPER does not point to a file"}
	}

	candidates := []string{
		filepath.Join(selfDir, "openvpn-desktop-helper"),
		filepath.Join(selfDir, "bin", "openvpn-desktop-helper"),
		"/usr/local/libexec/openvpn-desktop/openvpn-desktop-helper",
		"/usr/libexec/openvpn-desktop/openvpn-desktop-helper",
	}
	if p, err := exec.LookPath("openvpn-desktop-helper"); err == nil {
		candidates = append(candidates, p)
	}
	var helper string
	for _, c := range candidates {
		if st, err := os.Stat(c); err == nil && !st.IsDir() {
			helper = c
			break
		}
	}
	if helper == "" {
		return HelperInfo{Detail: "the privileged helper is not installed; run desktop/scripts/install-helper.sh"}
	}

	if os.Geteuid() == 0 {
		return HelperInfo{Path: helper, Mode: PrivilegeDirect, Available: true, Detail: "running as root; the helper runs directly"}
	}

	if mode, ok := modeFromMarker(helper); ok {
		switch mode {
		case PrivilegePkexec:
			if p, err := exec.LookPath("pkexec"); err == nil {
				return HelperInfo{Path: helper, Mode: PrivilegePkexec, Available: true, Detail: "installed for polkit via " + p}
			}
			return HelperInfo{Path: helper, Detail: "the installer chose polkit but pkexec is not installed"}
		case PrivilegeSudo:
			if _, err := exec.LookPath("sudo"); err == nil {
				return HelperInfo{Path: helper, Mode: PrivilegeSudo, Available: true, Detail: "installed for passwordless sudo"}
			}
			return HelperInfo{Path: helper, Detail: "the installer chose sudo but sudo is not installed"}
		case PrivilegeDirect:
			return HelperInfo{Path: helper, Mode: PrivilegeDirect, Available: true, Detail: "installed setuid; the helper runs directly"}
		}
	}

	forced := PrivilegeMode(os.Getenv("OPENVPN_DESKTOP_PRIVILEGE"))
	switch forced {
	case PrivilegeDirect:
		return HelperInfo{Path: helper, Mode: PrivilegeDirect, Available: true, Detail: "direct helper launch requested"}
	case PrivilegePkexec:
		if p, err := exec.LookPath("pkexec"); err == nil {
			return HelperInfo{Path: helper, Mode: PrivilegePkexec, Available: true, Detail: "via " + p}
		}
		return HelperInfo{Path: helper, Detail: "pkexec was requested but is not installed"}
	case PrivilegeSudo:
		if _, err := exec.LookPath("sudo"); err == nil {
			return HelperInfo{Path: helper, Mode: PrivilegeSudo, Available: true, Detail: "via passwordless sudo"}
		}
		return HelperInfo{Path: helper, Detail: "sudo was requested but is not installed"}
	case PrivilegeAuto, "":
	}

	if _, err := exec.LookPath("pkexec"); err == nil {
		return HelperInfo{Path: helper, Mode: PrivilegePkexec, Available: true, Detail: "via pkexec (polkit prompt)"}
	}
	if _, err := exec.LookPath("sudo"); err == nil {
		return HelperInfo{Path: helper, Mode: PrivilegeSudo, Available: true, Detail: "via passwordless sudo"}
	}
	return HelperInfo{Path: helper, Detail: "install pkexec (polkit) or sudo, or run desktop/scripts/install-helper.sh"}
}

// modeFromMarker reads the installer's choice next to the helper. Without it,
// auto mode prefers pkexec even when the user explicitly installed the sudo
// rule, which would make a working installation fail to launch.
func modeFromMarker(helper string) (PrivilegeMode, bool) {
	data, err := os.ReadFile(filepath.Join(filepath.Dir(helper), "privilege-mode"))
	if err != nil {
		return "", false
	}
	switch strings.ToLower(strings.TrimSpace(string(data))) {
	case "polkit", "pkexec":
		return PrivilegePkexec, true
	case "sudo":
		return PrivilegeSudo, true
	case "setuid", "direct":
		return PrivilegeDirect, true
	default:
		return "", false
	}
}

func findServiceHelper() HelperInfo {
	path, err := serviceproto.UserPath()
	if err != nil {
		return HelperInfo{Detail: "cannot locate the helper service configuration: " + err.Error()}
	}
	cfg, err := serviceproto.Load(path)
	if err != nil {
		if os.IsNotExist(err) {
			return HelperInfo{Detail: "the privileged helper service is not installed; run the platform service installer"}
		}
		return HelperInfo{Detail: "cannot read the helper service configuration: " + err.Error()}
	}
	return HelperInfo{Mode: PrivilegeService, ServiceAddr: cfg.Addr, ServiceToken: cfg.Token, Available: true, Detail: "via the installed helper service"}
}

// Command builds the exec.Cmd that launches the helper. The helper replaces
// itself with openvpn, so the PID the GUI watches is OpenVPN's.
func (h HelperInfo) Command(helperArgs []string) (*exec.Cmd, error) {
	if h.Mode == PrivilegeService {
		return nil, fmt.Errorf("the helper service does not use a local command")
	}
	if !h.Available {
		return nil, fmt.Errorf("%s", h.Detail)
	}
	switch h.Mode {
	case PrivilegeDirect:
		return exec.Command(h.Path, helperArgs...), nil
	case PrivilegePkexec:
		args := append([]string{h.Path}, helperArgs...)
		return exec.Command("pkexec", args...), nil
	case PrivilegeSudo:
		args := append([]string{"-n", h.Path}, helperArgs...)
		return exec.Command("sudo", args...), nil
	default:
		return nil, fmt.Errorf("unknown privilege mode %q", h.Mode)
	}
}

// Platform describes the desktop platform for diagnostics.
func Platform() string { return runtime.GOOS + "/" + runtime.GOARCH }
