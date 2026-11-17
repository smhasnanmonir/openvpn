package helper

import (
	"bytes"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

var trustedDirs = []string{
	"/usr/sbin", "/usr/bin", "/sbin", "/bin", "/usr/local/sbin", "/usr/local/bin",
}

// findTrusted locates a root-owned executable that is not writable by group or
// other. The helper must never execute a binary from a user-controlled PATH:
// it may be running with elevated privileges.
func findTrusted(name string) (string, error) {
	if filepath.IsAbs(name) {
		if err := checkTrusted(name); err == nil {
			return name, nil
		}
	}
	for _, dir := range trustedDirs {
		p := filepath.Join(dir, name)
		if err := checkTrusted(p); err == nil {
			return p, nil
		}
	}
	if p, err := exec.LookPath(name); err == nil {
		if err := checkTrusted(p); err == nil {
			return p, nil
		}
	}
	return "", fmt.Errorf("%s not found as a root-owned, non-writable executable", name)
}

func checkTrusted(path string) error {
	st, err := os.Stat(path)
	if err != nil {
		return err
	}
	if !st.Mode().IsRegular() {
		return fmt.Errorf("%s is not a regular file", path)
	}
	if st.Mode().Perm()&0o022 != 0 {
		return fmt.Errorf("%s is writable by group or other users", path)
	}
	if !ownershipOK(st) {
		return fmt.Errorf("%s is not owned by root", path)
	}
	return nil
}

func runTrusted(path string, args ...string) error {
	cmd := exec.Command(path, args...)
	cmd.Env = []string{"HOME=/root", "PATH=/usr/sbin:/usr/bin:/sbin:/bin", "LC_ALL=C"}
	var out bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &out
	if err := cmd.Run(); err != nil {
		msg := strings.TrimSpace(out.String())
		if msg != "" {
			return fmt.Errorf("%s %s: %w: %s", filepath.Base(path), strings.Join(args, " "), err, msg)
		}
		return fmt.Errorf("%s %s: %w", filepath.Base(path), strings.Join(args, " "), err)
	}
	return nil
}

func runTrustedOutput(path string, args ...string) (string, error) {
	cmd := exec.Command(path, args...)
	cmd.Env = []string{"HOME=/root", "PATH=/usr/sbin:/usr/bin:/sbin:/bin", "LC_ALL=C"}
	var out bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &out
	if err := cmd.Run(); err != nil {
		return out.String(), fmt.Errorf("%s %s: %w: %s", filepath.Base(path), strings.Join(args, " "), err, strings.TrimSpace(out.String()))
	}
	return out.String(), nil
}

func runTrustedStdin(stdin, path string, args ...string) error {
	cmd := exec.Command(path, args...)
	cmd.Env = []string{"HOME=/root", "PATH=/usr/sbin:/usr/bin:/sbin:/bin", "LC_ALL=C"}
	cmd.Stdin = strings.NewReader(stdin)
	var out bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &out
	if err := cmd.Run(); err != nil {
		msg := strings.TrimSpace(out.String())
		if msg != "" {
			return fmt.Errorf("%s %s: %w: %s", filepath.Base(path), strings.Join(args, " "), err, msg)
		}
		return fmt.Errorf("%s %s: %w", filepath.Base(path), strings.Join(args, " "), err)
	}
	return nil
}
