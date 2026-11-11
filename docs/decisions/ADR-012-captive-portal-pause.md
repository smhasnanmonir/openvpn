# ADR-012: Captive-portal pause and sign-in

## Status

Accepted.

## Context

A full-tunnel VPN cannot complete a captive-portal sign-in: the portal's HTTP
redirect is captured by the tunnel, the portal is not reachable through the
server, and many portals block until the device's probe succeeds. Users work
around this by disabling the VPN entirely in Android settings and frequently
forget to re-enable it — a real-world leak. Issue #18 asks for a controlled
pause that is honest about the exposure.

Android exposes the portal state through network capabilities:
`NET_CAPABILITY_CAPTIVE_PORTAL` and `NET_CAPABILITY_VALIDATED`. The
capabilities are available on API 23+ for `ConnectivityManager` networks, but
the exact behaviour differs by OEM/RF and a portal can also appear while the
VPN is already up (for example after a Wi-Fi roam).

## Decision

1. **Detection is best-effort, not a security control.** `NetworkMonitor` and
   `CaptivePortalPolicy` watch the default network's capabilities and report
   *portal detected* only when the network reports `CAPTIVE_PORTAL` (or is
   unvalidated in a way the platform identifies as portal-like). The app never
   claims it can detect every portal or that a "no portal detected" state is a
   guarantee.
2. **Explicit "Pause VPN to sign in" action.** When a portal is detected while
   connected (or during connect), the UI/banner offers a single explicit
   action. Pausing tears the tunnel down deliberately (native stop, TUN
   closed), so the portal probe can use the underlying network. The app never
   disables the VPN service silently.
3. **Honest paused state.** While paused for a portal, the UI state is an
   explicit paused state (`pausedForPortal`) that says *traffic is NOT
   protected*; the app must never render the connection as Protected,
   Connected or "kill switch active". The notification and tile reflect the
   paused state.
4. **Manual sign-in URL.** Because portal auto-open intents are inconsistent
   across OEMs, the UI also offers the manual sign-in URL
   `http://connectivitycheck.gstatic.com/generate_204` and instructions to
   sign in through the system portal notification. The app does not proxy or
   inspect portal traffic.
5. **Auto-resume once the network validates.** When the underlying network
   reports `NET_CAPABILITY_VALIDATED` (or the portal capability clears), the
   app reconnects the previous profile with stored credentials. Resume uses the
   normal connect path (fresh native session, DNS fail-closed policy, split
   tunnel policy), then returns to the previous user-visible state. If resume
   fails, the app stays in the honest paused/error state and offers manual
   reconnect; it does not loop silently.
6. **No automatic pause without the user.** Detection alone never pauses the
   tunnel. Pausing is always the user's action, because pausing is a deliberate
   exposure window. A future auto-pause feature would need its own ADR.
7. **Reuse the bounded retry budget.** Portal resume is not a new unbounded
   retry loop; a failed resume reports an error and can be retried manually.
   The bounded `PauseRetryPolicy` remains specific to Core's post-connect
   timeout pause (ADR-007).

## Consequences

- The user can complete portal sign-in without disabling the VPN app, and the
  app explicitly tells them that traffic is unprotected while paused.
- While paused, traffic genuinely uses the underlying network (including DNS,
  subject to the DNS fail-closed policy of the next connection). This window is
  the cost of the feature and is documented in `docs/security.md` and the leak
  matrix.
- Portal detection is version/OEM-dependent; the manual URL and the system
  portal notification are the fallback. The feature must not be sold as
  automatic captive-portal handling.
- Auto-resume after validation needs the stored credentials/profile; if the
  user removed them, the app returns to the normal credential prompt instead of
  failing silently.
- Testing needs a real captive portal (hotel/cafe, or a controlled
  `iptables`-redirect portal) and cannot be covered by JVM tests alone.

## Alternatives considered

- **Disable the VPN automatically on portal detection and re-enable later.**
  Rejected: silent protection loss; the app cannot reliably re-enable itself
  and the user may never notice.
- **Route the portal through the tunnel.** Not possible generically: the
  portal host is unknown until the redirect happens and the tunnel hides the
  underlying DNS/HTTP.
- **Use `setUnderlyingNetworks(null)` / keep the VPN up.** Android does not
  provide a supported "temporarily send everything outside the tunnel while
  claiming to be a VPN" mode; the honest option is a full pause.
- **Never support portals.** Rejected: users disable the VPN entirely, which
  is worse.

## References

- Issue: [#18](https://github.com/smhasnanmonir/openvpn/issues/18)
- `app/src/main/java/com/smhasnan/openvpn/vpn/CaptivePortalPolicy.kt`
- `app/src/main/java/com/smhasnan/openvpn/vpn/NetworkMonitor.kt`
- `app/src/main/java/com/smhasnan/openvpn/vpn/MyVpnService.kt`
- `app/src/main/java/com/smhasnan/openvpn/vpn/VpnState.kt` (`pausedForPortal`)
- `docs/security.md`, `docs/security/leak-test-matrix.md`
