package vault

import (
	"errors"
	"os"
)

// ErrRememberUnsupported means this platform has no usable system credential
// store, so the master password has to be typed on every launch.
var ErrRememberUnsupported = errors.New("this system has no supported credential store")

// RememberPath is the file the platform credential store uses for a vault. On
// Windows it holds a DPAPI blob bound to the current user; nothing here is
// readable by another account.
func RememberPath(vaultPath string) string { return vaultPath + ".remember" }

// removeRemembered deletes the remembered secret, ignoring a missing file.
func removeRemembered(vaultPath string) error {
	err := os.Remove(RememberPath(vaultPath))
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}
