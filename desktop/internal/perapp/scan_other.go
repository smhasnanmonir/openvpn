//go:build !linux

package perapp

import "path/filepath"

type App struct {
	Exe  string
	PIDs []int
}

func NormalizeExe(exe string) string { return exe }

func Canonical(path string) string { return path }

func Match(selected []string, exe string) bool {
	for _, s := range selected {
		if s == exe {
			return true
		}
	}
	return false
}

func Scan() []App { return nil }

func (a App) Name() string { return filepath.Base(a.Exe) }
