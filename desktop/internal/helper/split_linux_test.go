//go:build linux

package helper

import (
	"os"
	"os/exec"
	"strings"
	"testing"
)

func TestNFTSplitScriptExclude(t *testing.T) {
	rules := nftSplitScript("exclude", 123, 456, true, true)
	for _, want := range []string{
		"table inet openvpn_desktop_split",
		"type route hook output priority mangle",
		"meta nfproto ipv4 meta cgroup 123 meta mark set 0x1",
		"meta nfproto ipv6 meta cgroup 123 meta mark set 0x1",
	} {
		if !strings.Contains(rules, want) {
			t.Fatalf("exclude rules missing %q:\n%s", want, rules)
		}
	}
	if strings.Contains(rules, " accept\n") {
		t.Fatalf("exclude rules should only mark:\n%s", rules)
	}
}

func TestNFTSplitScriptInclude(t *testing.T) {
	rules := nftSplitScript("include", 123, 456, true, false)
	for _, want := range []string{
		`oif "lo" accept`,
		"meta cgroup 456 accept",
		"meta cgroup 123 accept",
		"meta nfproto ipv4 meta mark set 0x1",
	} {
		if !strings.Contains(rules, want) {
			t.Fatalf("include rules missing %q:\n%s", want, rules)
		}
	}
	if strings.Contains(rules, "meta nfproto ipv6") {
		t.Fatal("include rules marked an address family with no physical route")
	}
}

func TestNftSyntaxCheck(t *testing.T) {
	if os.Geteuid() != 0 {
		t.Skip("nft -c needs root in this environment")
	}
	nft, err := findTrusted("nft")
	if err != nil {
		t.Skip("nft is not installed")
	}
	for _, mode := range []string{"include", "exclude"} {
		script := nftSplitScript(mode, 123, 456, true, true)
		cmd := exec.Command(nft, "-c", "-f", "-")
		cmd.Stdin = strings.NewReader(script)
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("nft -c %s failed: %v\n%s\n%s", mode, err, out, script)
		}
	}
}

func TestParseDefaultRoute(t *testing.T) {
	route, ok := parseDefaultRoute("default via 192.168.1.1 dev wlan0 proto dhcp metric 600", false)
	if !ok || route.gateway != "192.168.1.1" || route.dev != "wlan0" || route.v6 {
		t.Fatalf("route = %+v, ok=%v", route, ok)
	}
	route, ok = parseDefaultRoute("default via fe80::1 dev eth0 metric 1024", true)
	if !ok || route.gateway != "fe80::1" || !route.v6 {
		t.Fatalf("v6 route = %+v, ok=%v", route, ok)
	}
	if _, ok := parseDefaultRoute("default dev tun0 scope link", false); ok {
		t.Fatal("a VPN default route must not be treated as physical")
	}
	if _, ok := parseDefaultRoute("10.0.0.0/8 via 10.0.0.1 dev eth0", false); ok {
		t.Fatal("a non-default route must not match")
	}
}

func TestIsCgroup2Mount(t *testing.T) {
	if _, err := os.Stat("/sys/fs/cgroup/cgroup.controllers"); err != nil {
		t.Skip("cgroup v2 is not available")
	}
	if !isCgroup2Mount("/sys/fs/cgroup") {
		t.Fatal("/sys/fs/cgroup is not recognized as cgroup2")
	}
	if isCgroup2Mount("/tmp") {
		t.Fatal("/tmp was accepted as a cgroup2 mount")
	}
}

func TestHasFamily(t *testing.T) {
	routes := []physicalRoute{{v6: false}, {v6: true}}
	if !hasFamily(routes, false) || !hasFamily(routes, true) {
		t.Fatal("family detection failed")
	}
	if hasFamily(nil, false) {
		t.Fatal("empty routes has no family")
	}
}
