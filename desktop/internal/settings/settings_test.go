package settings

import "testing"

func TestDefaultsAndNormalize(t *testing.T) {
	d := Default()
	if d.PortalProbeURL == "" || !d.AutoPortalDetect {
		t.Fatalf("portal defaults = %+v", d)
	}
	if d.AutoConnect || d.MinimizeToTray {
		t.Fatal("startup defaults should be opt-in")
	}
	var zero Settings
	n := zero.Normalize()
	if n.PortalProbeURL != d.PortalProbeURL || n.MtuMode != d.MtuMode || n.Compression != d.Compression {
		t.Fatalf("normalize did not fill defaults: %+v", n)
	}
}

func TestValidate(t *testing.T) {
	s := Default()
	if err := s.Validate(); err != nil {
		t.Fatal(err)
	}
	for _, bad := range []string{"javascript:alert(1)", "file:///etc/passwd", "/relative", "ftp://x"} {
		s := Default()
		s.PortalProbeURL = bad
		if err := s.Validate(); err == nil {
			t.Fatalf("accepted portal URL %q", bad)
		}
	}
	s = Default()
	s.PortalProbeURL = "https://portal.example/check"
	if err := s.Validate(); err != nil {
		t.Fatal(err)
	}
	s = Default()
	s.SplitTunnel = SplitExclude
	s.SplitTunnelCIDRs = "not-a-cidr"
	if err := s.Validate(); err == nil {
		t.Fatal("accepted an invalid split-tunnel CIDR")
	}

	s = Default()
	s.PerAppMode = PerAppInclude
	if err := s.Validate(); err == nil {
		t.Fatal("per-app include without executables was accepted")
	}
	s.PerAppExecutables = []string{"relative/path"}
	if err := s.Validate(); err == nil {
		t.Fatal("a relative per-app executable was accepted")
	}
	s.PerAppExecutables = []string{"/usr/bin/firefox", "/usr/bin/firefox"}
	if err := s.Validate(); err == nil {
		t.Fatal("a duplicate per-app executable was accepted")
	}
	s.PerAppExecutables = []string{"/usr/bin/firefox", "/opt/app/bin/app"}
	if err := s.Validate(); err != nil {
		t.Fatal(err)
	}
	s.PerAppMode = PerAppExclude
	if err := s.Validate(); err != nil {
		t.Fatal(err)
	}
	s.PerAppMode = "sideways"
	if err := s.Validate(); err == nil {
		t.Fatal("an unknown per-app mode was accepted")
	}
}

func TestParseCIDRList(t *testing.T) {
	got, err := ParseCIDRList("10.0.0.0/8, 192.168.1.5, 2606:4700:4700::1")
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"10.0.0.0/8", "192.168.1.5/32", "2606:4700:4700::1/128"}
	if len(got) != len(want) {
		t.Fatalf("got %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("got %v, want %v", got, want)
		}
	}
}
