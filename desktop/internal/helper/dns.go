package helper

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/smhasnanmonir/openvpn/desktop/internal/dnspolicy"
)

func dnsMarkerPath(dev string) string {
	return filepath.Join(StateDir, "dns."+sanitizeDev(dev))
}

func sanitizeDev(dev string) string {
	var b strings.Builder
	for _, r := range dev {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '-' || r == '_' || r == '.' {
			b.WriteRune(r)
		}
	}
	if b.Len() == 0 {
		return "tun"
	}
	return b.String()
}

// DNSUp is OpenVPN's route-up hook. It resolves the pushed DNS options with the
// fail-closed policy and applies them. A non-nil error aborts the connection,
// which is exactly what must happen when the policy refuses a leak.
func DNSUp(env map[string]string) error {
	if !isPrivileged() {
		return errors.New("dns-up must run with administrator/root privileges")
	}
	if err := ensureStateDir(); err != nil {
		return err
	}
	dev := firstNonEmpty(env["dev"], env["tun_dev"], "tun0")
	var values []string
	for i := 1; i <= 64; i++ {
		v := env[fmt.Sprintf("foreign_option_%d", i)]
		if v == "" {
			continue
		}
		values = append(values, v)
	}
	pushed, domains := dnspolicy.ParseForeignOptions(values)
	require := env["OPENVPN_DESKTOP_REQUIRE_DNS"] == "1"
	fallback := dnspolicy.ParseFallback(env["OPENVPN_DESKTOP_FALLBACK_DNS"])
	v4 := env["ifconfig_local"] != ""
	v6 := env["ifconfig_ipv6_local"] != ""
	if !v4 && !v6 {
		v4 = true
	}
	decision := dnspolicy.Decide(pushed, require, fallback, v4, v6)
	if decision.Refused {
		return errors.New(decision.Reason)
	}
	if len(decision.Servers) == 0 {
		if require {
			return errors.New("no usable tunnel DNS resolver was provided and Require tunnel DNS is on")
		}
		return nil
	}
	full := env["OPENVPN_DESKTOP_FULL_DNS"] != "0"
	if err := applyDNS(dev, decision.Servers, domains, full); err != nil {
		if require {
			return err
		}
		fmt.Fprintf(os.Stderr, "openvpn-desktop: warning: DNS policy could not be applied: %v\n", err)
		return nil
	}
	_ = os.WriteFile(dnsMarkerPath(dev), []byte(strings.Join(decision.Servers, ",")), 0o644)
	fmt.Printf("[openvpn-desktop] dns=%s fallback=%d\n",
		strings.Join(decision.Servers, ","), boolToInt(decision.UsedFallback))
	return nil
}

// DNSDown restores the previous resolver configuration.
func DNSDown(env map[string]string) error {
	dev := firstNonEmpty(env["dev"], env["tun_dev"], "tun0")
	revertDNS(dev)
	_ = restoreResolvBackup()
	_ = os.Remove(dnsMarkerPath(dev))
	return nil
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}

func boolToInt(v bool) int {
	if v {
		return 1
	}
	return 0
}
