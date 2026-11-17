//go:build !linux && !darwin && !windows

package helper

func findOpenVPN() (string, error) { return findTrusted("openvpn") }
