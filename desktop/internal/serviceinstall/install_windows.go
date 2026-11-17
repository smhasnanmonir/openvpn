//go:build windows

package serviceinstall

import (
	"fmt"
	"os"
	"path/filepath"
	"time"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/svc"
	"golang.org/x/sys/windows/svc/mgr"

	"github.com/smhasnanmonir/openvpn/desktop/internal/serviceproto"
)

func privileged() bool {
	return windows.GetCurrentProcessToken().IsElevated()
}

// protectConfigFiles applies explicit DACLs to the system and per-user token
// files and their directories. SDDL SIDs are language independent, unlike the
// localized "Administrators" account name.
func protectConfigFiles(systemPath, userConfig, userSID string) error {
	if userSID == "" {
		user, err := windows.GetCurrentProcessToken().GetTokenUser()
		if err != nil {
			return fmt.Errorf("read the installing user: %w", err)
		}
		userSID = user.User.Sid.String()
	}
	systemDDL := "D:P(A;;FA;;;S-1-5-18)(A;;FA;;;S-1-5-32-544)"
	userDDL := "D:P(A;;FA;;;S-1-5-18)(A;;FA;;;" + userSID + ")"
	for _, item := range []struct {
		path string
		sddl string
	}{
		{filepath.Dir(systemPath), systemDDL},
		{systemPath, systemDDL},
		{filepath.Dir(userConfig), userDDL},
		{userConfig, userDDL},
	} {
		sd, err := windows.SecurityDescriptorFromString(item.sddl)
		if err != nil {
			return fmt.Errorf("build ACL for %s: %w", item.path, err)
		}
		dacl, _, err := sd.DACL()
		if err != nil {
			return fmt.Errorf("read ACL for %s: %w", item.path, err)
		}
		if err := windows.SetNamedSecurityInfo(
			item.path,
			windows.SE_FILE_OBJECT,
			windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION,
			nil, nil, dacl, nil,
		); err != nil {
			return fmt.Errorf("protect %s: %w", item.path, err)
		}
	}
	return nil
}

// InstalledVersion returns the version written by the last install, or "".
func InstalledVersion() string {
	target, err := installedHelperPath()
	if err != nil {
		return ""
	}
	return readVersion(filepath.Join(filepath.Dir(target), "helper.version"))
}

func installedHelperPath() (string, error) {
	base := envOr(os.Getenv("ProgramFiles"), `C:\Program Files`)
	return filepath.Join(base, "OpenVPN Desktop", "openvpn-desktop-helper.exe"), nil
}

// Install copies the helper into Program Files, writes the system and per-user
// tokens, and creates or replaces the automatic LocalSystem service. An
// existing service is stopped before the executable is replaced, so an upgrade
// does not hit a locked binary.
func Install(opts Options) error {
	if !privileged() {
		return fmt.Errorf("install-service must run as administrator")
	}
	opts, err := normalize(opts)
	if err != nil {
		return err
	}
	target, err := installedHelperPath()
	if err != nil {
		return err
	}
	m, err := mgr.Connect()
	if err != nil {
		return fmt.Errorf("connect to the service manager: %w", err)
	}
	defer m.Disconnect()
	stopAndDelete(m)

	if !sameFile(opts.HelperPath, target) {
		if err := copyFile(opts.HelperPath, target, 0o755); err != nil {
			return fmt.Errorf("install helper to %s: %w", target, err)
		}
	}
	if _, err := ensureConfig(opts); err != nil {
		return err
	}
	systemPath, err := serviceproto.SystemPath()
	if err != nil {
		return err
	}
	if err := protectConfigFiles(systemPath, opts.UserConfig, opts.UserSID); err != nil {
		return err
	}
	if err := writeVersion(filepath.Dir(target), opts.Version); err != nil {
		return err
	}

	s, err := m.CreateService(WindowsServiceName, target, mgr.Config{
		DisplayName:  "OpenVPN Desktop Helper",
		Description:  "Privileged OpenVPN helper for OpenVPN Desktop",
		StartType:    mgr.StartAutomatic,
		ErrorControl: mgr.ErrorNormal,
	}, "service", "--config", systemPath)
	if err != nil {
		return fmt.Errorf("create service: %w", err)
	}
	defer s.Close()
	if err := s.Start(); err != nil {
		return fmt.Errorf("start service: %w", err)
	}
	return nil
}

func stopAndDelete(m *mgr.Mgr) {
	s, err := m.OpenService(WindowsServiceName)
	if err != nil {
		return
	}
	_, _ = s.Control(svc.Stop)
	for i := 0; i < 40; i++ {
		st, qerr := s.Query()
		if qerr != nil || st.State == svc.Stopped {
			break
		}
		time.Sleep(250 * time.Millisecond)
	}
	_ = s.Delete()
	s.Close()
	for i := 0; i < 40; i++ {
		s, err := m.OpenService(WindowsServiceName)
		if err != nil {
			return
		}
		s.Close()
		time.Sleep(250 * time.Millisecond)
	}
}

// Uninstall stops and deletes the service. It keeps configuration unless
// Purge is set.
func Uninstall(opts Options) error {
	if !privileged() {
		return fmt.Errorf("uninstall-service must run as administrator")
	}
	m, err := mgr.Connect()
	if err != nil {
		return err
	}
	defer m.Disconnect()
	s, err := m.OpenService(WindowsServiceName)
	if err == nil {
		_, _ = s.Control(svc.Stop)
		for i := 0; i < 40; i++ {
			st, qerr := s.Query()
			if qerr != nil || st.State == svc.Stopped {
				break
			}
			time.Sleep(250 * time.Millisecond)
		}
		if err := s.Delete(); err != nil {
			s.Close()
			return err
		}
		s.Close()
	}
	if opts.Purge {
		if systemPath, err := serviceproto.SystemPath(); err == nil {
			_ = os.Remove(systemPath)
		}
		userPath := opts.UserConfig
		if userPath == "" {
			userPath, _ = serviceproto.UserPath()
		}
		_ = os.Remove(userPath)
		if target, err := installedHelperPath(); err == nil {
			_ = os.Remove(target)
			_ = os.Remove(filepath.Join(filepath.Dir(target), "helper.version"))
			_ = os.Remove(filepath.Dir(target))
		}
	}
	return nil
}
