package main

import "testing"

func TestResolveDataDirHonorsEnv(t *testing.T) {
	t.Setenv("OPENVPN_DESKTOP_DATA_DIR", "/tmp/openvpn-desktop-test")
	if got := resolveDataDir(); got != "/tmp/openvpn-desktop-test" {
		t.Fatalf("resolveDataDir() = %q", got)
	}
}
