//go:build integration && linux

package helper

import (
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
)

// TestPerAppSplitInclude verifies the include variant: selected apps stay on
// the VPN, every other process is marked for the physical path, and the VPN
// process itself is exempt in its own cgroup.
func TestPerAppSplitInclude(t *testing.T) {
	if os.Getenv("OPENVPN_DESKTOP_INTEGRATION") != "1" {
		t.Skip("set OPENVPN_DESKTOP_INTEGRATION=1 and run scripts/test-per-app-split.sh")
	}
	if os.Geteuid() != 0 {
		t.Skip("per-app rules need root")
	}
	sleeper := os.Getenv("OPENVPN_DESKTOP_SPLIT_SLEEPER")
	veth := os.Getenv("OPENVPN_DESKTOP_SPLIT_VETH")
	if sleeper == "" || veth == "" {
		t.Fatal("OPENVPN_DESKTOP_SPLIT_SLEEPER and OPENVPN_DESKTOP_SPLIT_VETH are required")
	}
	cleanupStaleSplits()

	// This process plays the role of the VPN process for the exemption rule.
	// It must not use the selected executable, or the monitor would move it
	// into the selected-app cgroup.
	vpnPID := startSleeper(t, "/bin/sleep")
	split, err := startSplit(splitSpec{Mode: "include", Executables: []string{sleeper}})
	if err != nil {
		t.Fatal(err)
	}
	ls := split.(*linuxSplit)
	if setter, ok := split.(vpnProcessSetter); ok {
		if err := setter.SetVPNProcess(vpnPID); err != nil {
			split.Stop()
			t.Fatal(err)
		}
	} else {
		split.Stop()
		t.Fatal("split controller has no SetVPNProcess")
	}
	stopped := false
	defer func() {
		if !stopped {
			split.Stop()
		}
	}()
	waitCgroupPID(t, ls.vpnDir, vpnPID, 5*time.Second)

	selected := startSleeper(t, sleeper)
	waitCgroupPID(t, ls.appDir, selected, 5*time.Second)

	// A different executable (the distribution sleep, not the selected copy)
	// must not join the selected-app cgroup.
	other := exec.Command("/bin/sleep", "60")
	if err := other.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = other.Process.Kill(); _, _ = other.Process.Wait() })
	time.Sleep(1500 * time.Millisecond)
	if cgroupContains(ls.appDir, other.Process.Pid) {
		t.Fatal("a non-selected process joined the selected-app cgroup")
	}

	nft := runCmd(t, "nft", "list", "table", "inet", "openvpn_desktop_split")
	for _, want := range []string{"meta cgroup", "meta mark set"} {
		if !strings.Contains(nft, want) {
			t.Fatalf("include nft rules missing %q:\n%s", want, nft)
		}
	}
	rules := runCmd(t, "ip", "rule", "show")
	if !strings.Contains(rules, "fwmark 0x1/0x1") {
		t.Fatalf("policy rule missing:\n%s", rules)
	}

	runCmd(t, "ip", "route", "replace", "default", "via", "10.99.0.1", "dev", "tun-test")
	marked := runCmd(t, "ip", "route", "get", "1.1.1.1", "mark", "0x1")
	if !strings.Contains(marked, "dev "+veth) {
		t.Fatalf("include bypass traffic did not use the physical path:\n%s", marked)
	}
	if gw := os.Getenv("OPENVPN_DESKTOP_SPLIT_GATEWAY"); gw != "" {
		t.Cleanup(func() {
			_ = exec.Command("ip", "route", "replace", "default", "via", gw, "dev", veth).Run()
		})
	}

	split.Stop()
	stopped = true
	if _, err := os.Stat(ls.rootDir); !os.IsNotExist(err) {
		t.Fatalf("cgroup %s was not removed: %v", ls.rootDir, err)
	}
}
