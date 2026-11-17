//go:build darwin

package ovpn

import (
	"fmt"
	"os"
	"os/exec"
	"os/user"
	"strings"
	"time"

	"github.com/smhasnanmonir/openvpn/desktop/internal/serviceproto"
)

func installSupported(bundled string) bool {
	if _, err := os.Stat("/usr/bin/osascript"); err != nil {
		return false
	}
	st, err := os.Stat(bundled)
	return err == nil && st.Mode().IsRegular()
}

// InstallHelper runs the bundled helper through osascript's administrator
// prompt. The helper installs itself and a root-owned OpenVPN copy under
// /Library, then loads the launchd daemon.
func InstallHelper(h HelperInfo) error {
	src := h.BundledPath
	if src == "" {
		src = h.Path
	}
	if src == "" {
		return fmt.Errorf("the bundled helper was not found")
	}
	userConfig, err := serviceproto.UserPath()
	if err != nil {
		return err
	}
	name := os.Getenv("USER")
	if name == "" {
		if u, err := user.Current(); err == nil {
			name = u.Username
		}
	}
	command := strings.Join([]string{
		shellQuote(src), "install-service",
		"--helper", shellQuote(src),
		"--user-config", shellQuote(userConfig),
		"--user", shellQuote(name),
	}, " ")
	script := `do shell script "` + appleEscape(command) + `" with administrator privileges`
	out, err := exec.Command("/usr/bin/osascript", "-e", script).CombinedOutput()
	if err != nil {
		return fmt.Errorf("administrator install failed: %w: %s", err, strings.TrimSpace(string(out)))
	}
	deadline := time.Now().Add(30 * time.Second)
	for time.Now().Before(deadline) {
		if _, err := serviceproto.Load(userConfig); err == nil {
			return nil
		}
		time.Sleep(300 * time.Millisecond)
	}
	return fmt.Errorf("the helper did not write its per-user token")
}

func shellQuote(s string) string {
	return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
}

func appleEscape(s string) string {
	s = strings.ReplaceAll(s, `\`, `\\`)
	return strings.ReplaceAll(s, `"`, `\"`)
}
