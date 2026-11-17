// Package serviceproto is the local protocol between the GUI and the
// privileged helper service on Windows and macOS. The service listens on
// loopback and requires a random token installed per user; jobs carry profile
// text and credentials over the authenticated loopback connection.
package serviceproto

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

const (
	// ListenAddr is the default loopback endpoint. The token, not the port,
	// is the authentication boundary.
	ListenAddr = "127.0.0.1:51940"
	// MaxJobBytes bounds a job/config sent to the service.
	MaxJobBytes = 8 << 20
)

// Job starts one OpenVPN session.
type Job struct {
	Token              string   `json:"token"`
	Config             string   `json:"config"`
	Username           string   `json:"username,omitempty"`
	Password           string   `json:"password,omitempty"`
	PrivateKeyPassword string   `json:"privateKeyPassword,omitempty"`
	Response           string   `json:"response,omitempty"`
	ManagementAddr     string   `json:"managementAddr"`
	ManagementPassword string   `json:"managementPassword"`
	RequireDNS         bool     `json:"requireDns"`
	FallbackDNS        []string `json:"fallbackDns,omitempty"`
	KillSwitch         bool     `json:"killSwitch,omitempty"`
	PerAppMode         string   `json:"perAppMode,omitempty"`
	PerAppExecutables  []string `json:"perAppExecutables,omitempty"`
	Verb               int      `json:"verb,omitempty"`
}

// Message is a service-to-GUI event.
type Message struct {
	Type     string `json:"type"` // "log", "exit", "error"
	Line     string `json:"line,omitempty"`
	Error    string `json:"error,omitempty"`
	ExitCode int    `json:"exitCode,omitempty"`
}

// Config is the endpoint/token file. The root-owned copy is used by the
// service; the per-user copy is used by the GUI.
type Config struct {
	Addr  string `json:"addr"`
	Token string `json:"token"`
}

// NewToken returns a 256-bit random token.
func NewToken() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

// Validate checks a config before it is written or used.
func (c Config) Validate() error {
	if strings.TrimSpace(c.Token) == "" {
		return errors.New("service token is empty")
	}
	if len(c.Token) < 32 {
		return errors.New("service token is too short")
	}
	host, _, err := net.SplitHostPort(c.Addr)
	if err != nil {
		return fmt.Errorf("invalid service address: %w", err)
	}
	if host != "127.0.0.1" && host != "localhost" && host != "::1" {
		return errors.New("service must bind to loopback")
	}
	return nil
}

// UserPath is the per-user endpoint/token file read by the GUI.
func UserPath() (string, error) {
	dir, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "openvpn-desktop", "service.json"), nil
}

// SystemPath is the root-owned service config path.
func SystemPath() (string, error) {
	switch runtime.GOOS {
	case "windows":
		base := os.Getenv("ProgramData")
		if base == "" {
			base = `C:\ProgramData`
		}
		return filepath.Join(base, "OpenVPN Desktop", "service.json"), nil
	case "darwin":
		return "/Library/Application Support/OpenVPN Desktop/service.json", nil
	default:
		return "/etc/openvpn-desktop/service.json", nil
	}
}

// Load reads a config file and rejects insecure permissions on Unix.
func Load(path string) (Config, error) {
	st, err := os.Stat(path)
	if err != nil {
		return Config{}, err
	}
	if st.IsDir() {
		return Config{}, fmt.Errorf("%s is a directory", path)
	}
	if runtime.GOOS != "windows" && st.Mode().Perm()&0o077 != 0 {
		return Config{}, fmt.Errorf("%s is readable by other users (mode %o)", path, st.Mode().Perm())
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return Config{}, err
	}
	if len(data) > 64<<10 {
		return Config{}, errors.New("service config is too large")
	}
	var c Config
	if err := json.Unmarshal(data, &c); err != nil {
		return Config{}, err
	}
	if err := c.Validate(); err != nil {
		return Config{}, err
	}
	return c, nil
}

// Save writes a config atomically with mode 0600.
func Save(path string, c Config) error {
	if err := c.Validate(); err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	data = append(data, '\n')
	tmp, err := os.CreateTemp(filepath.Dir(path), ".service-*")
	if err != nil {
		return err
	}
	name := tmp.Name()
	defer os.Remove(name)
	if err := tmp.Chmod(0o600); err != nil {
		tmp.Close()
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(name, path)
}
