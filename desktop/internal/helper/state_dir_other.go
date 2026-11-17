//go:build !linux && !darwin && !windows

package helper

func stateDir() string { return "/tmp/openvpn-desktop" }
