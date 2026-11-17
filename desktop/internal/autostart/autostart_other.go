//go:build !linux && !windows && !darwin

package autostart

import "errors"

var errUnsupported = errors.New("start at login is not implemented for this platform")

// Supported reports whether start at login is available.
func Supported() bool { return false }

// Path is unsupported.
func Path() (string, error) { return "", errUnsupported }

// Installed reports whether start at login is installed.
func Installed() bool { return false }

// Install is unsupported.
func Install(string) (string, error) { return "", errUnsupported }

// Remove is unsupported.
func Remove() error { return errUnsupported }
