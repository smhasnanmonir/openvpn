//go:build darwin

package helper

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

type macDNSState struct {
	Service       string   `json:"service"`
	Servers       []string `json:"servers,omitempty"`
	Search        []string `json:"search,omitempty"`
	SearchChanged bool     `json:"searchChanged,omitempty"`
}

func macDNSStatePath(dev string) string {
	return filepath.Join(StateDir, "dns-macos-"+sanitizeDev(dev)+".json")
}

// applyDNS configures the primary network service with networksetup. macOS
// resolves DNS per service, not per utun link, so the resolvers apply to the
// active physical service; domain search lists are set when the server pushes
// them. The previous values are saved and restored on teardown.
func applyDNS(dev string, servers, domains []string, full bool) error {
	_ = full
	service, err := macServiceForDevice(dev)
	if err != nil {
		return err
	}
	oldServers, err := macGetList("-getdnsservers", service)
	if err != nil {
		return err
	}
	oldSearch, err := macGetList("-getsearchdomains", service)
	if err != nil {
		return err
	}
	state := macDNSState{Service: service, Servers: oldServers, Search: oldSearch}
	if len(domains) > 0 {
		state.SearchChanged = true
	}
	data, _ := json.Marshal(state)
	if err := writeFile(macDNSStatePath(dev), data, 0o600); err != nil {
		return err
	}
	if err := macSetList("-setdnsservers", service, servers); err != nil {
		return err
	}
	if len(domains) > 0 {
		if err := macSetList("-setsearchdomains", service, domains); err != nil {
			return err
		}
	}
	return nil
}

func revertDNS(dev string) {
	path := macDNSStatePath(dev)
	data, err := os.ReadFile(path)
	if err != nil {
		return
	}
	var state macDNSState
	if json.Unmarshal(data, &state) != nil || state.Service == "" {
		_ = os.Remove(path)
		return
	}
	_ = macSetList("-setdnsservers", state.Service, state.Servers)
	if state.SearchChanged {
		_ = macSetList("-setsearchdomains", state.Service, state.Search)
	}
	_ = os.Remove(path)
}

func restoreResolvBackup() error { return nil }

func macServiceForDevice(dev string) (string, error) {
	physical := dev
	if physical == "" || strings.HasPrefix(physical, "utun") || strings.HasPrefix(physical, "tun") || strings.HasPrefix(physical, "ppp") {
		if route, err := findTrusted("route"); err == nil {
			if out, err := runTrustedOutput(route, "-n", "get", "default"); err == nil {
				for _, line := range strings.Split(out, "\n") {
					if strings.HasPrefix(strings.TrimSpace(line), "interface:") {
						physical = strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(line), "interface:"))
						break
					}
				}
			}
		}
	}
	networksetup, err := findTrusted("networksetup")
	if err != nil {
		return "", err
	}
	out, err := runTrustedOutput(networksetup, "-listnetworkserviceorder")
	if err != nil {
		return "", err
	}
	service := ""
	for _, line := range strings.Split(out, "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "(") {
			if i := strings.Index(line, ") "); i >= 0 {
				service = strings.TrimSpace(line[i+2:])
			}
			continue
		}
		if service != "" && strings.Contains(line, "Device: "+physical+")") {
			return service, nil
		}
	}
	return "", fmt.Errorf("could not find a macOS network service for %q", physical)
}

func macGetList(flag, service string) ([]string, error) {
	networksetup, err := findTrusted("networksetup")
	if err != nil {
		return nil, err
	}
	out, err := runTrustedOutput(networksetup, flag, service)
	if err != nil {
		return nil, err
	}
	if strings.Contains(strings.ToLower(out), "aren't any") {
		return nil, nil
	}
	var values []string
	for _, line := range strings.Split(out, "\n") {
		line = strings.TrimSpace(line)
		if line != "" {
			values = append(values, line)
		}
	}
	return values, nil
}

func macSetList(flag, service string, values []string) error {
	networksetup, err := findTrusted("networksetup")
	if err != nil {
		return err
	}
	args := []string{flag, service}
	if len(values) == 0 {
		args = append(args, "Empty")
	} else {
		args = append(args, values...)
	}
	if err := runTrusted(networksetup, args...); err != nil {
		return errors.New("networksetup " + flag + ": " + err.Error())
	}
	return nil
}
