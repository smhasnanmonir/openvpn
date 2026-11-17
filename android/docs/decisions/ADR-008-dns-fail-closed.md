# ADR-008: Fail-closed tunnel DNS ("Require tunnel DNS")

## Status

Accepted.

## Context

Android's `VpnService` does not block DNS by itself. If the VPN's
`VpnService.Builder` has no DNS server configured for an address family,
Android resolves queries through the underlying (unprotected) network. OpenVPN
Core only calls the TUN builder's DNS callbacks when the server pushes usable
`dhcp-option DNS` entries or the profile declares `dns` directives. Some
providers push no DNS at all, and some push values Android cannot express (for
example DoH/DoT URLs, hostnames, or addresses with a port; see
`docs/openvpn-core-integration.md`).

Before this decision the app applied whatever DNS it received, logged a warning
when the list was empty, and connected anyway. That is a silent DNS leak: the
session shows "Connected" while every lookup goes to the ISP/underlying
network. PR #5 documented the gap; issue #8 asks for fail-closed behaviour.

A second problem was that the fallback was left entirely to Android. The app
explicitly refuses to inject a public resolver silently, and users on networks
with broken or hijacked DNS need a way to supply a resolver that is still
carried through the tunnel.

## Decision

1. **New setting, secure by default.** `AppSettings.requireTunnelDns` defaults
   to `true` ("Require tunnel DNS"). While enabled, a connection attempt whose
   resolved TUN configuration has no usable DNS server for any address family
   must not create the TUN.
2. **Explicit failure instead of a silent fallback.** The service evaluates
   the pushed DNS servers with a dedicated, Android-free `DnsLeakPolicy` at TUN
   establishment time. When the setting is on and there is no usable pushed
   resolver and no usable configured fallback, the connect fails with an
   explicit, actionable error telling the user that the server pushed no
   usable DNS and that the connection was refused to avoid a DNS leak. The
   service does not fall through to `Builder.establish()`.
3. **Optional fallback resolver carried through the tunnel.** A new setting
   `fallbackDnsServers` accepts a comma/space separated list of IPv4 and IPv6
   literals. `DnsLeakPolicy` normalises and validates the entries (whitespace,
   duplicate removal, IPv4/IPv6 parsing, reject hostnames/ports/URLs, and
   reject loopback/link-local/unspecified/multicast addresses that cannot be
   reached through a tunnel); only valid unicast literals are ever passed to
   `VpnService.Builder.addDnsServer()`. The fallback is used **only** when the
   server pushed no usable resolver, and the diagnostics log states that
   fallback DNS is in use. It is still routed through the tunnel, so it is not
   a public-resolver shortcut.
4. **Both families are covered.** The policy evaluates IPv4 and IPv6 pushed
   servers independently. A tunnel that carries one family but has no resolver
   for the other family still fails closed while the setting is on, because
   Android could otherwise resolve that family through the underlying network.
   IPv6 literals are normalised and validated the same way as IPv4.
5. **The setting is user-visible and reversible.** Turning "Require tunnel
   DNS" off restores the historical behaviour (apply pushed DNS, log the
   warning, connect). The UI must state that this can leak DNS to the
   underlying network; it is a compatibility escape hatch, not a recommended
   mode.
6. **No automatic public DNS injection anywhere.** The app never hardcodes a
   resolver and never enables Core's `googleDnsFallback`. The fallback comes
   only from the user's validated list.

## Consequences

- **Intentional behaviour change:** profiles whose server pushes no usable DNS
  can no longer connect by default. This is the point of the change and is
  called out in the changelog and README. Users who need to connect get a
  clear refusal message instead of a silent leak.
- DNS reconfiguration on reconnect goes through the same path: every TUN
  establishment (initial connect, resume, reconnect) re-evaluates the pushed
  DNS and the policy, so a resume cannot bypass the check.
- The check happens before `Builder.establish()`, so a refused connection never
  creates a TUN and never enters a "Connected" state.
- Fallback DNS is only as good as the user's input; a wrong resolver still
  fails or resolves through the tunnel. The app logs which path was taken
  (`pushed`, `fallback`, or refusal).
- `DnsLeakPolicy` is pure Kotlin and unit-tested without a device; actual DNS
  routing still needs the controlled-server + packet-capture tests in
  `docs/security/leak-test-matrix.md`.
- Search domains and pushed DNS metadata are unaffected; the policy only
  decides whether the TUN may be created and which resolvers are installed.

## Alternatives considered

- **Always connect and only warn.** Rejected: that is the pre-existing silent
  leak and contradicts the "never silently weaken VPN security" goal.
- **Inject a public resolver automatically.** Rejected: privacy-hostile,
  changes the user's DNS provider without consent, and hides a provider
  misconfiguration.
- **Block DNS in the TUN with `addDisallowedApplication`-style filtering.**
  Android `VpnService` has no DNS-only kill switch; the supported mechanism is
  to configure DNS or not to establish the tunnel.
- **Make the setting default off.** Rejected: a VPN must fail closed by
  default; operators with DNS-less servers can opt out explicitly.

## References

- Issue: [#8](https://github.com/smhasnanmonir/openvpn/issues/8)
- `app/src/main/java/com/smhasnan/openvpn/vpn/DnsLeakPolicy.kt`
- `app/src/main/java/com/smhasnan/openvpn/vpn/MyVpnService.kt` (`establishTun`)
- `app/src/main/java/com/smhasnan/openvpn/settings/SettingsRepository.kt`
- `docs/security.md` ("Routing, DNS, IPv4, and IPv6")
- `docs/security/leak-test-matrix.md`
