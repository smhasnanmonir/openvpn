//go:build linux

// Package perapp discovers running processes by executable path for Linux
// per-app split tunneling.
package perapp

import (
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

// App is a unique executable and the PIDs currently running it.
type App struct {
	Exe  string
	PIDs []int
}

// NormalizeExe strips the kernel's " (deleted)" suffix from /proc/PID/exe.
func NormalizeExe(exe string) string {
	return strings.TrimSuffix(strings.TrimSpace(exe), " (deleted)")
}

// Canonical resolves a path to the executable /proc/PID/exe reports, so a
// symlink such as /usr/bin/firefox matches /usr/lib/firefox/firefox.
func Canonical(path string) string {
	if resolved, err := filepath.EvalSymlinks(path); err == nil {
		return NormalizeExe(resolved)
	}
	return NormalizeExe(path)
}

// Match reports whether exe is one of the selected absolute paths.
func Match(selected []string, exe string) bool {
	exe = NormalizeExe(exe)
	if exe == "" {
		return false
	}
	for _, s := range selected {
		if s == exe {
			return true
		}
	}
	return false
}

// Scan returns running executables, de-duplicated and sorted by path.
func Scan() []App {
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return nil
	}
	byExe := map[string][]int{}
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		pid, err := strconv.Atoi(entry.Name())
		if err != nil || pid <= 0 {
			continue
		}
		exe, err := os.Readlink(filepath.Join("/proc", entry.Name(), "exe"))
		if err != nil {
			continue
		}
		exe = NormalizeExe(exe)
		if exe == "" || strings.HasPrefix(exe, "[") {
			continue
		}
		byExe[exe] = append(byExe[exe], pid)
	}
	out := make([]App, 0, len(byExe))
	for exe, pids := range byExe {
		sort.Ints(pids)
		out = append(out, App{Exe: exe, PIDs: pids})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Exe < out[j].Exe })
	return out
}

// Name returns the executable's base name for display.
func (a App) Name() string { return filepath.Base(a.Exe) }
