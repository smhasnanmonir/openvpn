//go:build !windows && !linux

package vault

// RememberSupported is false here on purpose: the macOS keychain tool only
// accepts a password as a command-line argument, which would expose it in the
// process list. Until there is a leak-free path, the master password is asked
// for on every launch.
func RememberSupported() bool { return false }

// SaveRemembered reports that remembering is unavailable.
func SaveRemembered(string, string) error { return ErrRememberUnsupported }

// LoadRemembered returns "" because nothing can be stored.
func LoadRemembered(string) string { return "" }

// ClearRemembered removes any stale file left by another platform.
func ClearRemembered(vaultPath string) error { return removeRemembered(vaultPath) }
