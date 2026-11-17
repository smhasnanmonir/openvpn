package ovpn

import (
	"strings"
	"testing"
	"time"

	"github.com/smhasnanmonir/openvpn/desktop/internal/model"
)

func TestParseManagementState(t *testing.T) {
	ev, ok := ParseManagementLine(">STATE:1712345678,CONNECTED,SUCCESS,10.8.0.2,203.0.113.5,1194,1194,tun0")
	if !ok || ev.Kind != "state" {
		t.Fatalf("not parsed: %+v", ev)
	}
	if ev.State != "CONNECTED" || ev.LocalIP != "10.8.0.2" || ev.RemoteIP != "203.0.113.5" ||
		ev.RemotePort != "1194" || ev.TunName != "tun0" {
		t.Fatalf("unexpected state event %+v", ev)
	}
	ev, ok = ParseManagementLine(">STATE:1,RECONNECTING,ping-restart,10.8.0.2,,,,,")
	if !ok || ev.State != "RECONNECTING" || ev.Description != "ping-restart" {
		t.Fatalf("reconnecting parse: %+v", ev)
	}
	// The one-shot `state` command response is a bare CSV line.
	ev, ok = ParseManagementLine("1794865860,CONNECTED,SUCCESS,10.8.0.2,192.168.123.1,1194,,")
	if !ok || ev.State != "CONNECTED" || ev.LocalIP != "10.8.0.2" || ev.RemoteIP != "192.168.123.1" {
		t.Fatalf("bare state parse: %+v (ok=%v)", ev, ok)
	}
	if _, ok := ParseManagementLine("END"); ok {
		t.Fatal("END should not parse")
	}
	if _, ok := ParseManagementLine("SUCCESS: real-time state notification set to ON"); ok {
		t.Fatal("a SUCCESS line should not parse")
	}
}

func TestParseManagementBytecountAndLog(t *testing.T) {
	ev, ok := ParseManagementLine(">BYTECOUNT:1234,5678")
	if !ok || ev.BytesIn != 1234 || ev.BytesOut != 5678 {
		t.Fatalf("bytecount parse: %+v", ev)
	}
	ev, ok = ParseManagementLine(">LOG:1712345678,E,TLS Error: certificate verify failed")
	if !ok || ev.LogLevel != model.LogError || !strings.Contains(ev.Message, "TLS Error") {
		t.Fatalf("log parse: %+v", ev)
	}
	ev, _ = ParseManagementLine(">LOG:1,W,warning here")
	if ev.LogLevel != model.LogWarn {
		t.Fatalf("warn flag parse: %+v", ev)
	}
	if _, ok := ParseManagementLine("SUCCESS: state on"); ok {
		t.Fatal("non-notification line should not parse")
	}
}

func TestStateMapping(t *testing.T) {
	cases := map[string]model.VPNState{
		"CONNECTED":    model.StateConnected,
		"RECONNECTING": model.StateReconnecting,
		"EXITING":      model.StateDisconnecting,
		"CONNECTING":   model.StateConnecting,
		"RESOLVE":      model.StateConnecting,
		"ASSIGN_IP":    model.StateConnecting,
	}
	for in, want := range cases {
		got, ok := stateFromManagement(in, "")
		if !ok || got != want {
			t.Fatalf("stateFromManagement(%q) = %q,%v; want %q", in, got, ok, want)
		}
	}
	if _, ok := stateFromManagement("UNKNOWN", ""); ok {
		t.Fatal("unknown state should not map")
	}
	if got, _ := stateFromManagement("EXITING", "AUTH_FAILED"); got != model.StateAuthFailed {
		t.Fatalf("auth failure = %q", got)
	}
}

func TestRetryPolicy(t *testing.T) {
	p := DefaultRetryPolicy()
	want := []time.Duration{5 * time.Second, 10 * time.Second, 20 * time.Second, 40 * time.Second, 60 * time.Second, 60 * time.Second}
	for i, w := range want {
		if got := p.BaseDelay(i + 1); got != w {
			t.Fatalf("BaseDelay(%d) = %v, want %v", i+1, got, w)
		}
	}
	for attempt := 1; attempt <= 6; attempt++ {
		base := p.BaseDelay(attempt)
		got := p.Delay(attempt, nil)
		if got < base/2 || got > base {
			t.Fatalf("Delay(%d) = %v outside [%v,%v]", attempt, got, base/2, base)
		}
	}
	if !p.ShouldGiveUp(6) || p.ShouldGiveUp(5) {
		t.Fatal("retry budget boundary is wrong")
	}
}

func TestCredentialsFileSCRV1(t *testing.T) {
	got := credentialsFile(model.Credentials{Username: "user", Password: "pass", Response: "123456"}, true)
	lines := strings.Split(got, "\n")
	if len(lines) < 3 || lines[0] != "user" {
		t.Fatalf("unexpected credentials file %q", got)
	}
	if !strings.HasPrefix(lines[1], "SCRV1:") {
		t.Fatalf("expected SCRV1, got %q", lines[1])
	}
	plain := credentialsFile(model.Credentials{Username: "user", Password: "pass"}, false)
	if strings.Contains(plain, "SCRV1:") {
		t.Fatalf("unexpected SCRV1 in plain credentials: %q", plain)
	}
}

func TestClassifyLog(t *testing.T) {
	if classifyLog("TLS Error: certificate verify failed") != model.LogError {
		t.Fatal("TLS error should be an error")
	}
	if classifyLog("WARNING: compression enabled") != model.LogWarn {
		t.Fatal("warning should be a warning")
	}
	if classifyLog("OpenVPN 2.7.0 started") != model.LogInfo {
		t.Fatal("start line should be info")
	}
}
