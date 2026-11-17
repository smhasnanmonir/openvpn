//go:build integration

package app

import (
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/smhasnanmonir/openvpn/desktop/internal/model"
	"github.com/smhasnanmonir/openvpn/desktop/internal/ovpn"
	"github.com/smhasnanmonir/openvpn/desktop/internal/serviceproto"
	"github.com/smhasnanmonir/openvpn/desktop/internal/settings"
	"github.com/smhasnanmonir/openvpn/desktop/internal/vault"
)

// TestLiveTunnelService exercises the shared privileged-service protocol and
// GUI client: the helper `serve` process starts a `run` child, streams logs,
// and the GUI connects through the management interface. It uses the same
// local OpenVPN server as TestLiveTunnel.
func TestLiveTunnelService(t *testing.T) {
	if os.Getenv("OPENVPN_DESKTOP_INTEGRATION") != "1" {
		t.Skip("set OPENVPN_DESKTOP_INTEGRATION=1 and run scripts/test-live-tunnel.sh")
	}
	helperPath := os.Getenv("OPENVPN_DESKTOP_HELPER")
	profilePath := os.Getenv("OPENVPN_DESKTOP_LIVE_PROFILE")
	if helperPath == "" || profilePath == "" {
		t.Fatal("OPENVPN_DESKTOP_HELPER and OPENVPN_DESKTOP_LIVE_PROFILE are required")
	}
	token, err := serviceproto.NewToken()
	if err != nil {
		t.Fatal(err)
	}
	port := freeTestPort(t)
	addr := fmt.Sprintf("127.0.0.1:%d", port)
	cfgPath := filepath.Join(t.TempDir(), "service.json")
	if err := serviceproto.Save(cfgPath, serviceproto.Config{Addr: addr, Token: token}); err != nil {
		t.Fatal(err)
	}
	service := exec.Command(helperPath, "serve", "--config", cfgPath)
	if err := service.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = service.Process.Kill()
		_, _ = service.Process.Wait()
	})
	waitService(t, addr, 10*time.Second)

	dir := t.TempDir()
	v, err := vault.Create(filepath.Join(dir, "vault.ovpnx"), "integration-password")
	if err != nil {
		t.Fatal(err)
	}
	svc := New(v, dir, ovpn.HelperInfo{
		Mode:         ovpn.PrivilegeService,
		ServiceAddr:  addr,
		ServiceToken: token,
		Available:    true,
		Detail:       "integration service",
	}, nil)
	t.Cleanup(svc.Close)

	out := svc.ImportFiles([]string{profilePath})
	if len(out) != 1 || !out[0].OK() {
		t.Fatalf("import failed: %+v", out)
	}
	if err := svc.UpdateSettings(func(s *settings.Settings) error {
		s.RequireTunnelDNS = false
		s.AutoCheckIP = false
		s.KillSwitch = false
		s.Compression = settings.CompressionStrict
		s.MtuMode = settings.MtuAuto
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	if err := svc.Connect(out[0].ProfileID, model.Credentials{}); err != nil {
		t.Fatal(err)
	}
	snap := waitForState(t, svc, model.StateConnected, 45*time.Second)
	if snap.Details.VPNIP4 != "10.8.0.2" {
		t.Fatalf("VPN IPv4 = %q, details=%+v", snap.Details.VPNIP4, snap.Details)
	}
	ping := exec.Command("ping", "-c", "2", "-W", "2", "10.8.0.1")
	if output, err := ping.CombinedOutput(); err != nil {
		t.Fatalf("ping through the service-managed tunnel failed: %v\n%s", err, output)
	}
	svc.Disconnect()
	waitForState(t, svc, model.StateDisconnected, 20*time.Second)
	t.Log("service-managed tunnel disconnected cleanly")
}

func freeTestPort(t *testing.T) int {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}

func waitService(t *testing.T, addr string, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		conn, err := net.DialTimeout("tcp", addr, 200*time.Millisecond)
		if err == nil {
			conn.Close()
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatalf("helper service did not listen on %s", addr)
}
