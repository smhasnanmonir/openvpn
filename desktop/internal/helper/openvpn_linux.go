//go:build linux

package helper

func findOpenVPN() (string, error) { return findTrusted("openvpn") }
