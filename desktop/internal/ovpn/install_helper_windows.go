//go:build windows

package ovpn

import (
	"fmt"
	"os"
	"strings"
	"time"

	"golang.org/x/sys/windows"

	"github.com/smhasnanmonir/openvpn/desktop/internal/serviceproto"
)

func installSupported(bundled string) bool {
	st, err := os.Stat(bundled)
	return err == nil && st.Mode().IsRegular()
}

// InstallHelper starts the bundled helper with a UAC prompt and waits for the
// per-user token file. The helper copies itself into Program Files, writes the
// tokens and creates the LocalSystem service.
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
	userSID := ""
	if token, err := windows.GetCurrentProcessToken().GetTokenUser(); err == nil {
		userSID = token.User.Sid.String()
	}
	args := "install-service --helper " + winQuote(src) + " --user-config " + winQuote(userConfig)
	if userSID != "" {
		args += " --user-sid " + userSID
	}
	verb, err := windows.UTF16PtrFromString("runas")
	if err != nil {
		return err
	}
	file, err := windows.UTF16PtrFromString(src)
	if err != nil {
		return err
	}
	argp, err := windows.UTF16PtrFromString(args)
	if err != nil {
		return err
	}
	if err := windows.ShellExecute(0, verb, file, argp, nil, windows.SW_SHOWNORMAL); err != nil {
		return fmt.Errorf("administrator approval failed: %w", err)
	}
	deadline := time.Now().Add(90 * time.Second)
	for time.Now().Before(deadline) {
		if _, err := serviceproto.Load(userConfig); err == nil {
			time.Sleep(time.Second)
			return nil
		}
		time.Sleep(500 * time.Millisecond)
	}
	return fmt.Errorf("the helper did not finish installing; approve the UAC prompt and try again")
}

func winQuote(s string) string {
	if s != "" && !strings.ContainsAny(s, " \t") {
		return s
	}
	return `"` + strings.ReplaceAll(s, `"`, `\"`) + `"`
}
