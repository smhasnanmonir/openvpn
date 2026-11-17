//go:build !windows && !darwin && !linux

package serviceinstall

// Install is unsupported on this platform.
func Install(Options) error { return errUnsupported }

// Uninstall is unsupported on this platform.
func Uninstall(Options) error { return errUnsupported }

// InstalledVersion is unsupported on this platform.
func InstalledVersion() string { return "" }
