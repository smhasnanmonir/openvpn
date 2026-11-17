# Android ↔ Desktop feature parity

Legend: ✅ implemented, ⚠️ implemented differently or with a documented
desktop-specific limitation, ➖ not applicable to the desktop, ❌ not
implemented.

| Android feature | Desktop | Notes |
|---|---|---|
| Import `.ovpn` (SAF) | ✅ | Native file dialog; multiple selection for companions. |
| Inline CA/cert/key/tls-auth/tls-crypt | ✅ | Kept inline; external refs inlined at import only. |
| Companion file selection | ✅ | Same dialog; missing companion fails the import. |
| `.ovpn` metadata (server, port, proto, auth) | ✅ | Text parser instead of OpenVPN Core `eval_config`; same fields shown. |
| Reject TAP | ✅ | TUN only on desktop too. |
| Reject `config` includes / plugins | ✅ | Import + helper sanitization. |
| External PKI handling | ✅ | User/pass-only profiles get a certificate-less marker; true external PKI refused. |
| Legacy-only `tls-cipher` pin removal | ✅ | Lease ECDHE suites; TLS 1.3 negotiation. |
| `remote-cert-tls server` enforcement | ✅ | Import and connect. |
| Strict trust (CA or `tls-remote`) | ✅ | Import and connect; peer-fingerprint-only refused. |
| Peer-fingerprint warning | ✅ | Pin is reported; CA still authenticates. |
| CA-change warning on same-name import | ✅ | DER fingerprint, order-independent. |
| Content-hash dedupe | ✅ | Includes pre-hardening hash. |
| Encrypted profile storage | ⚠️ | Single Argon2id+AES-256-GCM vault, not the Android Keystore. Master password required. |
| Encrypted credentials | ✅ | Same vault; static-challenge response is session-only. |
| Biometric/UI lock | ➖ | Master password + Lock Vault; no biometric. |
| Connect/disconnect | ✅ | System OpenVPN 2.7+ over management. |
| Honest state machine | ✅ | Only `CONNECTED` is “Protected”. |
| Bounded reconnect + jitter | ✅ | 5 s → 60 s, 6 attempts, reset on connected. |
| Byte counters | ✅ | Management `BYTECOUNT`; packets not exposed by this interface. |
| Connection details | ✅ | Server, tunnel IP, TUN device, DNS, MTU. |
| Logs + redaction + ring buffer | ✅ | 500 lines in memory, never on disk. |
| Copy/clear logs | ✅ | UI buttons. |
| DNS fail-closed | ✅ | `internal/dnspolicy` + helper `dns-up`; abort on refusal. |
| Fallback DNS | ✅ | Validated IPv4/IPv6 list; family-filtered. |
| DNS family blocking | ✅ | Resolvers for unrouted families are dropped. |
| DNS through `resolvectl`/`resolvconf`/`/etc/resolv.conf` | ✅ | Linux; symlink-safe. macOS uses `networksetup` (per network service); Windows uses TAP + `--block-outside-dns`. |
| MTU Auto/1500/1400/1280 | ✅ | `pull-filter ignore tun-mtu` + `tun-mtu`. |
| Compression compatibility/strict | ✅ | `allow-compression asym/no` + push filters. |
| AEAD-only cipher policy | ✅ | Validate + `data-ciphers` + push filters. |
| Split tunneling | ✅ | **Per-app include/exclude on Linux** (cgroup v2 + nftables/policy routing) plus route-based include/exclude and LAN bypass. New processes are moved within ~1 s; system-resolver DNS is kept on the VPN path. |
| Split-tunnel leak warnings | ✅ | Explicit in Home and Settings. |
| Always-on VPN / lockdown guidance | ⚠️ | Linux nftables kill switch (opt-in); never claims system protection when off. Windows/macOS rely on OpenVPN's DNS blocking and OS firewall controls; no app kill switch there. |
| Captive portal pause/sign-in | ✅ | HTTP probe; pause sets an honest `pausedForPortal` state; validated http(s) sign-in URL; reconnect only after a 204. Automatic detection can be disabled. |
| Public IP check | ✅ | Off by default; `https://api.ipify.org`, 256-byte response, IP-literal validation. |
| Notifications | ✅ | Desktop notifications on connect/disconnect/failure. |
| Quick Settings tile | ✅ | System tray with Show/Connect/Disconnect/Quit plus optional minimize-to-tray; desktop menu and notifications. Tray availability depends on the OS host. |
| Theme system/light/dark | ✅ | MyGo native theme. |
| Multiple profiles + selection | ✅ | Profile list, select/use, delete. |
| Profile emoji | ✅ | Default emoji per import. |
| Process-death recovery | ⚠️ | Bounded restart while the app runs; no boot auto-start. |
| Auto-connect at launch | ✅ | Start-at-login on Linux (XDG), Windows (per-user `Run` key) and macOS (LaunchAgent), plus **Connect automatically after the vault is unlocked**. The master password is never stored. |
| “Require tunnel DNS” default ON | ✅ | Default ON. |
| No analytics/backend | ✅ | Same privacy stance. |
| Public-IP provider disclosed | ✅ | Settings URL + README. |
| 16 KB pages / Android platform | ➖ | Android-only. |
| Backend (#16) and iOS (#20) | ➖ | Out of scope for both clients. |

| Windows privileged service | ✅ | `OpenVPNDesktopHelper` Windows service, LocalSystem, loopback token, TAP/OpenVPN 2.7+, SID-based ACLs, NSIS installer hook and GUI one-click install; cross-compiled/installer-built, real-device tunnel pending. |
| macOS privileged service | ✅ | launchd daemon, loopback token, root-owned OpenVPN copy, `networksetup` DNS, GUI one-click install; cross-compiled/app-built, real-device tunnel pending. |

## Parity rationale

- **Per-app split tunneling** is implemented on Linux with cgroup v2,
  nftables marks and policy routing. Unlike Android's `VpnService` app list,
  matching is by `/proc/PID/exe`: children inherit the cgroup, a one-second
  monitor catches new processes, and wrapper scripts match their interpreter.
  System-resolver DNS is kept on the VPN path in include mode; apps with their
  own DoH/DoT follow the per-app rule. Windows/macOS do not have this
  implementation.
- **Keystore vs master password.** Linux has no universal non-exportable key
  store for an unprivileged GUI. A password-derived vault is portable and
  auditable; the trade-off is a password prompt and no biometric unlock.
- **Always-on/lockdown** is enforced by Android's system VPN framework. The
  closest desktop controls are the explicit nftables kill switch and the XDG
  start-at-login/auto-connect flow; neither is silently assumed to be a
  system-wide kill switch.
- **Captive portals** use an explicit HTTP probe rather than the Android
  `NetworkMonitor` capability. The pause state, URL validation and reconnect
  gate are the same product behaviour.
- **Windows/macOS privilege.** Neither platform has a universal unprivileged
  TUN API. A privileged helper service authenticates a loopback job with a
  random token, re-sanitizes the profile and starts OpenVPN. The shared service
  core and packages are exercised on Linux; the platform wrappers/installers
  build but still need a real device tunnel, signing and notarization.
- **OpenVPN 3 Core vs OpenVPN 2.7.** The Android app uses a pinned Core with
  generated-header patches. The desktop uses the distribution's maintained
  OpenVPN, which is the right security-update story for a desktop; profile-level
  policies are enforced in Go before the helper runs.
