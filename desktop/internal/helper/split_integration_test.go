//go:build integration && linux

package helper

import (
	"os"
	"os/exec"
	"strconv"
	"strings"
	"testing"
	"time"
)

// TestPerAppSplit runs the real cgroup v2 + nftables + policy-routing setup in
// an isolated network namespace. scripts/test-per-app-split.sh creates the
// namespace, a physical veth default and a dummy "VPN" device.
func TestPerAppSplit(t *testing.T) {
	if os.Getenv("OPENVPN_DESKTOP_INTEGRATION") != "1" {
		t.Skip("set OPENVPN_DESKTOP_INTEGRATION=1 and run scripts/test-per-app-split.sh")
	}
	if os.Geteuid() != 0 {
		t.Skip("per-app rules need root")
	}
	veth := os.Getenv("OPENVPN_DESKTOP_SPLIT_VETH")
	sleeper := os.Getenv("OPENVPN_DESKTOP_SPLIT_SLEEPER")
	if veth == "" || sleeper == "" {
		t.Fatal("OPENVPN_DESKTOP_SPLIT_VETH and OPENVPN_DESKTOP_SPLIT_SLEEPER are required")
	}
	cleanupStaleSplits()

	split, err := startSplit(splitSpec{Mode: "exclude", Executables: []string{sleeper}})
	if err != nil {
		t.Fatal(err)
	}
	ls, ok := split.(*linuxSplit)
	if !ok || ls == nil {
		t.Fatalf("unexpected split controller %T", split)
	}
	stopped := false
	defer func() {
		if !stopped {
			split.Stop()
		}
	}()

	// Selected process, started before the rule was installed.
	p1 := startSleeper(t, sleeper)
	waitCgroupPID(t, ls.appDir, p1, 5*time.Second)

	// A new process must be moved by the one-second monitor.
	p2 := startSleeper(t, sleeper)
	waitCgroupPID(t, ls.appDir, p2, 5*time.Second)

	if cgroupContains(ls.appDir, os.Getpid()) {
		t.Fatal("the test process was moved into the selected-app cgroup")
	}

	nft := runCmd(t, "nft", "list", "table", "inet", "openvpn_desktop_split")
	if !strings.Contains(nft, "meta cgroup") || !strings.Contains(nft, "meta mark set") {
		t.Fatalf("nft table is missing the mark rule:\n%s", nft)
	}
	rules := runCmd(t, "ip", "rule", "show")
	if !strings.Contains(rules, "fwmark 0x1/0x1") || !strings.Contains(rules, "lookup 51820") {
		t.Fatalf("policy rule missing:\n%s", rules)
	}
	table := runCmd(t, "ip", "route", "show", "table", "51820")
	if !strings.Contains(table, "dev "+veth) {
		t.Fatalf("policy table does not use the physical veth %s:\n%s", veth, table)
	}

	// Simulate the VPN taking over the main default. Marked traffic must still
	// resolve to the physical veth, while unmarked traffic uses the VPN dummy.
	runCmd(t, "ip", "route", "replace", "default", "via", "10.99.0.1", "dev", "tun-test")
	direct := runCmd(t, "ip", "route", "get", "1.1.1.1")
	if !strings.Contains(direct, "dev tun-test") {
		t.Fatalf("unmarked traffic did not use the VPN path:\n%s", direct)
	}
	marked := runCmd(t, "ip", "route", "get", "1.1.1.1", "mark", "0x1")
	if !strings.Contains(marked, "dev "+veth) {
		t.Fatalf("marked traffic did not use the physical path:\n%s", marked)
	}
	if gw := os.Getenv("OPENVPN_DESKTOP_SPLIT_GATEWAY"); gw != "" {
		t.Cleanup(func() {
			_ = exec.Command("ip", "route", "replace", "default", "via", gw, "dev", veth).Run()
		})
	}

	split.Stop()
	stopped = true
	if _, err := exec.Command("nft", "list", "table", "inet", "openvpn_desktop_split").CombinedOutput(); err == nil {
		t.Fatal("nft table was not removed")
	}
	if strings.Contains(runCmd(t, "ip", "rule", "show"), "priority "+strconv.Itoa(ls.rulePriority)) {
		t.Fatal("policy rule was not removed")
	}
	if _, err := os.Stat(ls.rootDir); !os.IsNotExist(err) {
		t.Fatalf("cgroup %s was not removed: %v", ls.rootDir, err)
	}
}

func startSleeper(t *testing.T, path string) int {
	t.Helper()
	cmd := exec.Command(path, "120")
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = cmd.Process.Kill()
		_, _ = cmd.Process.Wait()
	})
	return cmd.Process.Pid
}

func waitCgroupPID(t *testing.T, dir string, pid int, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if cgroupContains(dir, pid) {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("pid %d was not moved into %s (procs=%v)", pid, dir, cgroupPIDs(dir))
}

func cgroupContains(dir string, pid int) bool {
	for _, p := range cgroupPIDs(dir) {
		if p == pid {
			return true
		}
	}
	return false
}

func runCmd(t *testing.T, name string, args ...string) string {
	t.Helper()
	out, err := exec.Command(name, args...).CombinedOutput()
	if err != nil {
		t.Fatalf("%s %s: %v\n%s", name, strings.Join(args, " "), err, out)
	}
	return string(out)
}

var _ = strconv.Itoa // keep strconv available for future diagnostics
