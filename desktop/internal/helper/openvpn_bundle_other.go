//go:build !windows

package helper

// ensureOpenVPNAvailable is a no-op outside Windows. Linux and macOS packages
// depend on the distribution's or the user's OpenVPN installation.
func ensureOpenVPNAvailable() (string, error) { return "", nil }
