package dnspolicy

import (
	"reflect"
	"testing"
)

func TestNormalizeServer(t *testing.T) {
	valid := map[string]string{
		"1.1.1.1":              "1.1.1.1",
		" 8.8.8.8 ":            "8.8.8.8",
		"2606:4700:4700::1111": "2606:4700:4700::1111",
		"10.0.0.53":            "10.0.0.53",
		"::ffff:1.1.1.1":       "1.1.1.1",
	}
	for in, want := range valid {
		got, ok := NormalizeServer(in)
		if !ok || got != want {
			t.Fatalf("NormalizeServer(%q) = %q,%v; want %q", in, got, ok, want)
		}
	}
	for _, in := range []string{
		"", "127.0.0.1", "0.0.0.0", "169.254.1.1", "224.0.0.1", "255.255.255.255",
		"::1", "::", "fe80::1", "ff02::1", "010.0.0.1", "1.1.1", "dns.example.com",
	} {
		if got, ok := NormalizeServer(in); ok {
			t.Fatalf("NormalizeServer(%q) accepted as %q", in, got)
		}
	}
}

func TestParseFallback(t *testing.T) {
	got := ParseFallback("1.1.1.1, 9.9.9.9 2606:4700:4700::1111 127.0.0.1,1.1.1.1")
	want := []string{"1.1.1.1", "9.9.9.9", "2606:4700:4700::1111"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("ParseFallback = %v, want %v", got, want)
	}
}

func TestDecide(t *testing.T) {
	// Pushed resolvers win.
	d := Decide([]string{"10.8.0.1"}, true, []string{"1.1.1.1"}, true, false)
	if d.Refused || d.UsedFallback || !reflect.DeepEqual(d.Servers, []string{"10.8.0.1"}) {
		t.Fatalf("pushed decision = %+v", d)
	}
	// Fallback wins when nothing usable was pushed.
	d = Decide([]string{"127.0.0.1"}, true, []string{"1.1.1.1"}, true, false)
	if d.Refused || !d.UsedFallback || !reflect.DeepEqual(d.Servers, []string{"1.1.1.1"}) {
		t.Fatalf("fallback decision = %+v", d)
	}
	// Fail closed.
	d = Decide(nil, true, nil, true, true)
	if !d.Refused || d.Reason == "" {
		t.Fatalf("expected refusal, got %+v", d)
	}
	// Opt-out allows an empty resolver set.
	d = Decide(nil, false, nil, true, true)
	if d.Refused || len(d.Servers) != 0 {
		t.Fatalf("expected empty allowed decision, got %+v", d)
	}
	// Family filtering: only IPv4 tunnel, IPv6 resolver is dropped.
	d = Decide([]string{"2606:4700:4700::1111"}, true, []string{"1.1.1.1"}, true, false)
	if d.Refused || !d.UsedFallback || !reflect.DeepEqual(d.Servers, []string{"1.1.1.1"}) {
		t.Fatalf("family decision = %+v", d)
	}
	// Only a resolver for an unrouted family: refuse rather than unblock it.
	d = Decide([]string{"2606:4700:4700::1111"}, true, nil, true, false)
	if !d.Refused {
		t.Fatalf("expected family refusal, got %+v", d)
	}
}

func TestParseForeignOptions(t *testing.T) {
	dns, domains := ParseForeignOptions([]string{
		"dhcp-option DNS 10.8.0.1",
		"dhcp-option DNS6 2606:4700:4700::1111",
		"dhcp-option DOMAIN corp.example",
		"dhcp-option DOMAIN-SEARCH search.example",
		"not-an-option",
	})
	if !reflect.DeepEqual(dns, []string{"10.8.0.1", "2606:4700:4700::1111"}) {
		t.Fatalf("dns = %v", dns)
	}
	if !reflect.DeepEqual(domains, []string{"corp.example", "search.example"}) {
		t.Fatalf("domains = %v", domains)
	}
}
