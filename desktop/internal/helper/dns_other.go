//go:build !linux && !darwin

package helper

import "errors"

func applyDNS(string, []string, []string, bool) error {
	return errors.New("DNS route-up hooks are not used on this platform")
}

func revertDNS(string) {}

func restoreResolvBackup() error { return nil }

func restoreStaleDNS() {}
