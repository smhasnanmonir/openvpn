//go:build !windows && !darwin && !linux

package ovpn

import "fmt"

func installSupported(string) bool { return false }

func InstallHelper(HelperInfo) error {
	return fmt.Errorf("the privileged helper installer is not implemented for this platform")
}
