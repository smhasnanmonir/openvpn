//go:build integration

package app

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/smhasnanmonir/openvpn/desktop/internal/model"
	"github.com/smhasnanmonir/openvpn/desktop/internal/ovpn"
	"github.com/smhasnanmonir/openvpn/desktop/internal/settings"
	"github.com/smhasnanmonir/openvpn/desktop/internal/vault"
)

// TestLiveTunnel runs the real client against a local OpenVPN server. It needs
// root, /dev/net/tun, and a network namespace; scripts/test-live-tunnel.sh sets
// all of that up and runs it with -tags integration.
func TestLiveTunnel(t *testing.T) {
	if os.Getenv("OPENVPN_DESKTOP_INTEGRATION") != "1" {
		t.Skip("set OPENVPN_DESKTOP_INTEGRATION=1 and run scripts/test-live-tunnel.sh")
	}
	helper := os.Getenv("OPENVPN_DESKTOP_HELPER")
	profilePath := os.Getenv("OPENVPN_DESKTOP_LIVE_PROFILE")
	if helper == "" || profilePath == "" {
		t.Fatal("OPENVPN_DESKTOP_HELPER and OPENVPN_DESKTOP_LIVE_PROFILE are required")
	}
	if _, err := exec.LookPath("ping"); err != nil {
		t.Skip("ping is not installed")
	}

	dir := t.TempDir()
	v, err := vault.Create(filepath.Join(dir, "vault.ovpnx"), "integration-password")
	if err != nil {
		t.Fatal(err)
	}
	svc := New(v, dir, ovpn.HelperInfo{Path: helper, Mode: ovpn.PrivilegeDirect, Available: true, Detail: "integration"}, nil)
	t.Cleanup(svc.Close)

	out := svc.ImportFiles([]string{profilePath})
	if len(out) != 1 || !out[0].OK() {
		t.Fatalf("import failed: %+v", out)
	}
	id := out[0].ProfileID
	if err := svc.UpdateSettings(func(s *settings.Settings) error {
		s.RequireTunnelDNS = false // the test network namespace has no systemd-resolved
		s.AutoCheckIP = false
		s.KillSwitch = false
		s.Compression = settings.CompressionStrict
		s.MtuMode = settings.MtuAuto
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	if err := svc.Connect(id, model.Credentials{}); err != nil {
		t.Fatal(err)
	}
	snap := waitForState(t, svc, model.StateConnected, 45*time.Second)
	if snap.Details.VPNIP4 != "10.8.0.2" {
		t.Fatalf("VPN IPv4 = %q, want 10.8.0.2; details=%+v", snap.Details.VPNIP4, snap.Details)
	}
	if snap.Details.ServerIP != "192.168.123.1" {
		t.Fatalf("server IP = %q, want 192.168.123.1", snap.Details.ServerIP)
	}

	ping := exec.Command("ping", "-c", "3", "-W", "2", "10.8.0.1")
	if output, err := ping.CombinedOutput(); err != nil {
		t.Fatalf("ping through the tunnel failed: %v\n%s", err, output)
	}
	deadline := time.Now().Add(10 * time.Second)
	for {
		snap = svc.Snapshot()
		if snap.Stats.BytesIn > 0 && snap.Stats.BytesOut > 0 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("traffic counters did not move: %+v", snap.Stats)
		}
		time.Sleep(200 * time.Millisecond)
	}
	t.Logf("connected: ip=%s server=%s stats=%+v", snap.Details.VPNIP4, snap.Details.ServerLabel(), snap.Stats)

	for _, entry := range svc.Logs() {
		if strings.Contains(entry.Message, "BEGIN PRIVATE KEY") || strings.Contains(entry.Message, "BEGIN CERTIFICATE") {
			t.Fatalf("secret material reached the log: %q", entry.Message)
		}
		if strings.Contains(entry.Message, "dns-updown") {
			t.Fatalf("OpenVPN's built-in DNS script ran despite --dns-updown disable: %q", entry.Message)
		}
	}

	svc.Disconnect()
	waitForState(t, svc, model.StateDisconnected, 20*time.Second)
	t.Log("disconnected cleanly")
}

func waitForState(t *testing.T, svc *Service, want model.VPNState, timeout time.Duration) model.Snapshot {
	t.Helper()
	deadline := time.Now().Add(timeout)
	var last model.Snapshot
	for time.Now().Before(deadline) {
		last = svc.Snapshot()
		if last.State == want {
			return last
		}
		if last.State == model.StateError || last.State == model.StateAuthFailed {
			t.Fatalf("connection failed while waiting for %s: state=%s error=%s", want, last.State, last.Error)
		}
		time.Sleep(200 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s; last snapshot=%+v\nlogs:\n%s", want, last, formatLogs(svc.Logs()))
	return last
}

func formatLogs(logs []model.LogEntry) string {
	var b strings.Builder
	for _, e := range logs {
		b.WriteString(e.Time.Format("15:04:05") + " [" + string(e.Level) + "] " + e.Message + "\n")
	}
	return b.String()
}
