//go:build windows

package helper

import (
	"os"
	"path/filepath"
)

func stateDir() string {
	base := os.Getenv("ProgramData")
	if base == "" {
		base = `C:\ProgramData`
	}
	return filepath.Join(base, "OpenVPN Desktop")
}
