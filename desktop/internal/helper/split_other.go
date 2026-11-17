//go:build !linux

package helper

import "errors"

func startSplit(spec splitSpec) (splitController, error) {
	if spec.Mode == "" || spec.Mode == "off" {
		return nil, nil
	}
	return nil, errors.New("per-app split tunneling is implemented for Linux only")
}

func cleanupStaleSplits() {}
