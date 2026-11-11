# Security and product readiness checklist

This is a living audit of the Android client against the 11 November 2026
security/product-readiness review. It is not a certification and it does not
claim that the app is a complete commercial VPN service. Backend, PKI, server
infrastructure, iOS, legal and store work are tracked separately.

**Update for the security/reliability backlog PR (#8–#21):** the rows below
reflect the shipped code and documentation for that PR. Unless a row says
"device verified", runtime behaviour on physical devices is still unverified;
the test plan is `docs/security/leak-test-matrix.md` and the honest status table
is in `docs/testing.md`. The MASVS/ASVS desk assessment is
`docs/security/masvs-gap-assessment.md` (not an audit).

Legend:

- ✅ present and covered by code/tests/docs
- 🟡 partial, configuration-dependent, or compatibility trade-off
- ❌ not implemented here; tracked by an issue
- N/A outside this Android client repository

## Protocol and encryption

| Item | Status | Notes |
|---|---|---|
| Official OpenVPN implementation | ✅ | OpenVPN 3 Core 3.9 is pinned as a submodule; no protocol/crypto code is reimplemented. |
| AEAD data ciphers | 🟡 | Core negotiates AES-GCM variants/ChaCha20-Poly1305; profiles can still pin CBC or fall back to BF-CBC by default. New opt-in **AEAD-only** cipher policy adds connect-time `pull-filter` accept/reject rules and fails a non-AEAD push visibly (ADR-010). Runtime device verification pending. |
| Modern TLS | ✅ | mbedTLS defaults to TLS 1.2 minimum and negotiates TLS 1.3; legacy-only `tls-cipher` pins are dropped at import/connect. |
| Server certificate verification | ✅ | CA, `remote-cert-tls server`, `verify-x509-name`, and `peer-fingerprint` are handled by Core; verification is never disabled. |
| `remote-cert-tls server` enforcement | 🟡 | Added at import and connect for CA-based profiles that lack an explicit server-usage policy (PR #5). Deliberate compatibility break: a server certificate without `serverAuth` EKU now fails `CERT_VERIFY_FAIL`; documented in the changelog and the failure message. |
| Strict server-trust policy | ✅ | Profiles require a CA, `peer-fingerprint`, or legacy `tls-remote`; no-trust profiles are rejected at import and refused at connect; CA-change warning on same-name re-import (ADR-009). Device negative tests pending. |
| `verify-x509-name` | 🟡 | Honored when the profile supplies it. The app cannot safely infer the certificate name from the remote host because providers use different names (for example `sin-a09.vpn.wlvpn.com` vs `sin-a09.wlvpn.com`). |
| tls-crypt / tls-crypt-v2 | ✅ | Supported by Core when the profile contains the key; dynamic tls-crypt is not advertised on the mbedTLS build (ADR-006). |
| Compression | 🟡 | Symmetric compression is refused; server-to-client LZO-ASYM is accepted only in the default Compatibility mode. New opt-in **Strict** mode uses `compressionMode="no"` + a generated core patch that fails a server-pushed compressor loudly (ADR-010). Runtime device verification pending. |
| Certificate revocation | 🟡 | Revocation is enforced by the VPN server/PKI (CRL/OCSP), not the client; documented explicitly (ADR-009). No client CRL/OCSP. |
| Dependency update process | ✅ | CycloneDX SBOM per build/release, scheduled osv-scanner advisory scan + gitleaks, Dependabot, patch-deadline SLA and advisory owner in `SECURITY.md` (#13). Residual: core updates remain manual. |

## Kill switch and leak protection

| Item | Status | Notes |
|---|---|---|
| IPv4/IPv6 leak baseline | ✅ | Android blocks an address family when the VPN has no address/route/DNS; the app keeps that default. `allowFamily()` is only used when the user opts out. |
| DNS leak protection | ✅ (code) / device pending | "Require tunnel DNS" defaults ON: no usable pushed resolver (and no validated user fallback) refuses the connection instead of falling back to the underlying network. Fallback IPv4/IPv6 literals are validated and routed through the tunnel; diagnostics state fallback use (ADR-008). Runtime leak verification pending. |
| System kill switch | 🟡 | Android Always-on VPN / lockdown is the supported mechanism. The app handles the Always-on service start and shows a best-effort readiness card (detects `always_on_vpn_app`/`always_on_vpn_lockdown`, UNKNOWN when unreadable); it never claims a guaranteed kill switch (ADR-013). Device verification pending (#9). |
| Local-network / TunnelCrack behavior | 🟡 | Routes come from Core and the app does not enable local LAN access by default. A dedicated route-conflict/leak test matrix is documented but not executed (`docs/security/leak-test-matrix.md`). |
| Split tunneling | ✅ (code) / device pending | Off by default; Include/Exclude modes with a per-app picker, fail-closed zero-app validation, stale-package filtering, and explicit "N apps bypass the tunnel" warnings. Requires Play `QUERY_ALL_PACKAGES` (declared/justified). Packet-capture verification pending (ADR-011). |
| Captive portals | ✅ (code) / device pending | Best-effort `CAPTIVE_PORTAL`/`VALIDATED` detection, explicit "Pause VPN to sign in", honest not-protected paused state, auto-resume on validation, manual sign-in URL; no automatic silent pause (ADR-012). Real-portal verification pending. |
| 16 KB pages | ✅ CI gate | Native library linked with `-Wl,-z,max-page-size=16384`; CI now fails unless every packaged `.so` LOAD segment has `p_align >= 0x4000` and `zipalign -c -P 16 4` passes. Device/emulator launch pending (#12). |

## Credentials and profile security

| Item | Status | Notes |
|---|---|---|
| Profile encryption at rest | ✅ | AES-256-GCM with a non-exportable Android Keystore key in `noBackupFilesDir/profiles`. |
| Credential encryption | ✅ | Separate Keystore key in `noBackupFilesDir/credentials`; username/password/key password are optional and encrypted. |
| Backups | ✅ | `allowBackup=false`, `fullBackupContent=false`, and data-extraction rules exclude all domains. |
| Log redaction | ✅ | Kotlin sanitization plus native redaction for passwords, keys, certificates, auth tokens, and session tokens. |
| No secrets in APK | ✅ | No embedded CA private key, shared credential, or backend secret. Profiles are user-supplied. |
| External PKI | ✅ | Profiles that explicitly require an external certificate/key provider are rejected (ADR-004). |
| Scripts/plugins in profiles | ✅ | OpenVPN Core 3.9 ignores script/plugin features and rejects the management interface; the app does not enable them. |
| Strict profile validation | ✅ | Strict trust policy requires CA/`peer-fingerprint`/`tls-remote`, rejects no-trust profiles at import and connect, warns on CA change (ADR-009). |
| Device-unique credentials | N/A | The app imports user-supplied profiles. Device certificate issuance/revocation belongs to the provider/backend (see #16). |

## Authentication and account security

| Item | Status | Notes |
|---|---|---|
| VPN username/password, static/dynamic challenge | ✅ | Handled through Core; challenge responses are not persisted. |
| App account login, MFA, tokens, device management | N/A | There is no account/backend in this client. Tracked in #16. |

## Backend, PKI and server infrastructure

| Item | Status | Notes |
|---|---|---|
| Control-plane/data-plane separation | N/A | Out of scope for this repository. Tracked in #16. |
| Account auth, subscriptions, device management | N/A | Tracked in #16. |
| CA operations, certificate issuance/revocation | N/A | Tracked in #16. |
| VPN gateway/DNS/API hardening | N/A | Tracked in #16. |

## Android platform integration

| Item | Status | Notes |
|---|---|---|
| `VpnService`, `protect()`, TUN, routes, DNS | ✅ | Native Core performs TUN I/O; the transport socket is protected from its own tunnel. |
| Foreground service and notification | ✅ | `specialUse`/`vpn` FGS type, notification channel, Disconnect/Reconnect actions, tile. |
| Always-on VPN start | 🟡 | `VpnService.SERVICE_INTERFACE` connects the persisted selected profile and the service returns `START_STICKY` for that start; a null-intent redelivery is honoured only when `isAlwaysOn`. Device verification (boot/unlock and process death) is still pending (#9). |
| Always-on/lockdown onboarding | 🟡 | Linked in Settings; readiness detection is tracked in #9. |
| Network transitions | ✅ | NetworkMonitor re-applies the underlying network; post-connect timeouts pause and resume with bounded backoff (ADR-007). |
| Process death / reboot | 🟡 | Persisted desired-connected profile (never a native pointer); Always-on `START_STICKY` restore creates a fresh native session; no boot receiver (Always-on handles boot; Keystore is locked before first unlock). Force-stop/reboot/other-VPN behaviour documented (ADR-013). Device verification pending (#14). |
| Metered network inheritance | ✅ | `Builder.setMetered()` follows the underlying network on Android 10+ (PR #5). |
| 16 KB page size | ✅ CI gate | See the dedicated row under "Kill switch and leak protection"; CI rejects misaligned packaged libraries (#12). |
| App hardening | 🟡 | No backup, no cleartext, restricted exports, R8, hidden native symbols. `FLAG_SECURE`, integrity/tamper checks, and a device matrix are not implemented (see #21 / MASVS assessment). |
| Split tunneling | ❌ | Tracked in #17. |
| Captive portals | ❌ | Tracked in #18. |

## Reliability and user experience

| Item | Status | Notes |
|---|---|---|
| Explicit connection state machine | ✅ | `VpnState` with PREPARING/CONNECTING/CONNECTED/RECONNECTING/PAUSED-equivalent/ERROR/AUTH_FAILED; UI only shows CONNECTED from engine events. |
| UDP/TCP transport | ✅ | Core selects the remote/transport from the profile; the app does not force a protocol. |
| Reconnection | ✅ (code) / device pending | Post-connect timeouts pause and resume with bounded exponential backoff + bounded jitter (5 s base, 60 s cap, max 6 attempts), then fail visibly because traffic is unprotected while paused (ADR-007, ADR-013). |
| MTU/fragmentation | ✅ (code) / device pending | Pushed MTU by default; fixed 1500/1400/1280 override appends `pull-filter ignore tun-mtu` + `tun-mtu N` so the pushed value cannot override it; chosen MTU shown in details; one EMSGSIZE hint per connection; no auto-reconnect loop (ADR-010 / #10). Path-MTU verification pending. |
| Traffic counters | ✅ | Download/upload direction fixed and tested (PR #5). |
| Connection health | 🟡 | Engine state, TUN, DNS, stats and public-IP check exist; no continuous gateway/DNS health probe. |
| Server selection/health | N/A | Profiles come from the user/provider; no discovery service in this repo. |
| Battery/background work | ✅ | State-driven stats, coalesced logs, deduplicated notifications/tile, lifecycle-aware timers. |

## Privacy and compliance

| Item | Status | Notes |
|---|---|---|
| No analytics/crash reporter/backend | ✅ | The app has no Firebase/analytics/crash SDK. |
| Public-IP check disclosure | ✅ | HTTPS to `api.ipify.org`; no credentials/profile data; can be disabled; disclosed in the privacy policy. |
| Package visibility (split tunneling) | ✅ / store declaration pending | `QUERY_ALL_PACKAGES` declared for the per-app selector; package list used locally only, never transmitted/logged; Play restricted-permission declaration wording prepared (`docs/legal/play-store-vpn-disclosure.md`). |
| Privacy policy and Play VpnService disclosure | ✅ docs / publication pending | Complete privacy policy and Play VpnService/Data-safety/specialUse/`QUERY_ALL_PACKAGES` documents added under `docs/legal/`; publishing the policy URL and submitting the Console declarations are manual release steps (#15). |
| Store/legal requirements | 🟡 | Release compliance checklist added (`docs/compliance/release-checklist.md`); store submission, target-market/legal review, and privacy-policy hosting remain manual (#15). |
| Backend/iOS scope decisions | ✅ docs | ADR-014 + `docs/architecture/backend-control-plane.md`; ADR-015 + `docs/architecture/ios-plan.md`; explicitly not implemented (#16/#20). |

## Testing and assurance

| Item | Status | Notes |
|---|---|---|
| Unit tests | ✅ | JVM tests for state, import/trust/cipher policy, stats, logs, reconnect mapping, bounded/jittered pause retry, DNS/MTU/split-tunnel/captive-portal policy. Final count is recorded from the merge-candidate CI run in `docs/testing.md`. |
| Instrumentation tests | ✅ | Import, native lifecycle, Keystore, user/password profile tests. |
| Controlled-server tunnel | ✅ | Emulator test against OpenVPN 2.7 (TLS 1.3/AES-256-GCM, TUN, ICMP). |
| Leak/kill-switch matrix | 🟡 | Full matrix with method, expected results and adb/tcpdump commands added (`docs/security/leak-test-matrix.md`); device execution pending. |
| MASVS/ASVS review and penetration test | 🟡 | MASVS v2 gap assessment + ASVS note for a future backend added (`docs/security/masvs-gap-assessment.md`); independent pen test not performed. |
| Physical-device matrix | ❌ | Not executed for this branch. |

## Out of scope for this repository

- iOS client: #20 — scope decision + plan documented (ADR-015,
  `docs/architecture/ios-plan.md`); no iOS code.
- Backend/account/PKI/server infrastructure: #16 — scope decision + architecture
  documented (ADR-014, `docs/architecture/backend-control-plane.md`); no backend
  code.
- Commercial privacy policy, store listing and legal review: #15 — truthful
  policy/disclosure documents and a release compliance checklist are in
  `docs/legal/` and `docs/compliance/`; publication/submission and legal review
  remain manual.

## Highest-priority next steps

1. Execute the device leak-test matrix (`docs/security/leak-test-matrix.md`) on
   physical devices with a controlled server: DNS fail-closed/fallback, split
   tunneling, captive portal, Always-on/lockdown, process death/reboot, 16 KB.
2. Publish the privacy policy and submit the Play `VpnService`/
   `QUERY_ALL_PACKAGES`/Data-safety declarations; complete target-market/legal
   review (#15).
3. Commission the independent penetration test and work through the MASVS gaps
   (FLAG_SECURE/key-bound app lock, reproducibility/provenance).
4. Keep the advisory SLA and core/mbedTLS review cadence (`SECURITY.md`); drill
   the emergency-release process.
5. If the product proceeds with a backend (#16) or iOS (#20), start those in
   separate repositories with their own ASVS/threat model/CI and owners.
