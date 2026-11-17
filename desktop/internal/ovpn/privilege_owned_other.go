//go:build !linux

package ovpn

// helperRootOwned is only used by the Linux helper discovery path.
func helperRootOwned(string) bool { return true }
