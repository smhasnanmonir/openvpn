# Security and product readiness checklist

This is a living audit of the Android client against the 11 November 2026
security/product-readiness review. It is not a certification and it does not
claim that the app is a complete commercial VPN service. Backend, PKI, server
infrastructure, iOS, legal and store work are tracked separately.

Legend:

- ✅ present and covered by code/tests/docs
- 🟡 partial, configuration-dependent, or compatibility trade-off
- ❌ not implemented here; tracked by an issue
- N/A outside this Android client repository

## Protocol and encryption

| Item | Status | Notes |
|---|---|---|
| Official OpenVPN implementation | ✅ | OpenVPN 3 Core 3.9 is pinned as a submodule; no protocol/crypto code is reimplemented. |
| AEAD data ciphers | 🟡 | Core negotiates AES-GCM/ChaCha20-Poly1305; profiles can still pin CBC or fall back to BF-CBC. Strict mode is tracked in #19. |
| Modern TLS | ✅ | mbedTLS defaults to TLS 1.2 minimum and negotiates TLS 1.3; legacy-only `tls-cipher` pins are dropped at import/connect. |
| Server certificate verification | ✅ | CA, `remote-cert-tls server`, `verify-x509-name`, and `peer-fingerprint` are handled by Core; verification is never disabled. |
| `remote-cert-tls server` enforcement | 🟡 | Added at import and connect for CA-based profiles that lack an explicit server-usage policy (PR #5). Deliberate compatibility break: a server certificate without `serverAuth` EKU now fails `CERT_VERIFY_FAIL`; documented in the changelog and the failure message. |
| `verify-x509-name` | 🟡 | Honored when the profile supplies it. The app cannot safely infer the certificate name from the remote host because providers use different names (for example `sin-a09.vpn.wlvpn.com` vs `sin-a09.wlvpn.com`). |
| tls-crypt / tls-crypt-v2 | ✅ | Supported by Core when the profile contains the key; dynamic tls-crypt is not advertised on the mbedTLS build (ADR-006). |
| Compression | 🟡 | Symmetric compression is refused; server-to-client LZO-ASYM is accepted for provider compatibility (ADR-005). Strict mode is tracked in #19. |
| Certificate revocation | 🟡 | Revocation is enforced by the VPN server/PKI (CRL/OCSP), not the client. Client-side strict validation is tracked in #11. |
| Dependency update process | ❌ | The core is pinned and documented, but SBOM/advisory monitoring/patch deadlines are tracked in #13. |

## Kill switch and leak protection

| Item | Status | Notes |
|---|---|---|
| IPv4/IPv6 leak baseline | ✅ | Android blocks an address family when the VPN has no address/route/DNS; the app keeps that default. `allowFamily()` is only used when the user opts out. |
| DNS leak protection | 🟡 | Pushed DNS is applied; if no usable DNS is pushed the app logs a warning but may fall back to the underlying resolver. Fail-closed mode is tracked in #8. |
| System kill switch | 🟡 | Android Always-on VPN / lockdown is the supported mechanism. The app links to system settings and now handles the Always-on service start (PR #5). Onboarding/verification is tracked in #9. |
| Local-network / TunnelCrack behavior | 🟡 | Routes come from Core and the app does not enable local LAN access by default. A dedicated route-conflict/leak test matrix is tracked in #21. |
| Split tunneling | ❌ | Not exposed in the UI. Tracked in #17. |
| Captive portals | ❌ | No temporary pause/sign-in flow. Tracked in #18. |

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
| Strict profile validation | 🟡 | Core validates the merged profile. A policy requiring CA/peer-fingerprint and warning on CA changes is tracked in #11. |
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
| Process death / reboot | 🟡 | Stale sessions are not restored; Always-on starts return `START_STICKY` and a null-intent redelivery is honoured only when `isAlwaysOn`. Before the first unlock the service cannot restore the tunnel because the app is not direct-boot aware. A full lifecycle/backoff policy is tracked in #14. |
| Metered network inheritance | ✅ | `Builder.setMetered()` follows the underlying network on Android 10+ (PR #5). |
| 16 KB page size | ✅ | Native library is linked with 16 KB ELF alignment (PR #5); device verification/CI check is tracked in #12. |
| App hardening | 🟡 | No backup, no cleartext, restricted exports, R8, hidden native symbols. `FLAG_SECURE`, integrity/tamper checks, and a device matrix are not implemented (see #21). |
| Split tunneling | ❌ | Tracked in #17. |
| Captive portals | ❌ | Tracked in #18. |

## Reliability and user experience

| Item | Status | Notes |
|---|---|---|
| Explicit connection state machine | ✅ | `VpnState` with PREPARING/CONNECTING/CONNECTED/RECONNECTING/PAUSED-equivalent/ERROR/AUTH_FAILED; UI only shows CONNECTED from engine events. |
| UDP/TCP transport | ✅ | Core selects the remote/transport from the profile; the app does not force a protocol. |
| Reconnection | 🟡 | Post-connect timeouts pause and resume with exponential backoff (5 s doubling to 60 s, max 6 attempts per connected session), then fail visibly because traffic is unprotected while paused. Jitter and a full lifecycle policy are tracked in #14. |
| MTU/fragmentation | 🟡 | The app uses the pushed MTU and warns on `Message too long`; configurable MTU/PMTU handling is tracked in #10. |
| Traffic counters | ✅ | Download/upload direction fixed and tested (PR #5). |
| Connection health | 🟡 | Engine state, TUN, DNS, stats and public-IP check exist; no continuous gateway/DNS health probe. |
| Server selection/health | N/A | Profiles come from the user/provider; no discovery service in this repo. |
| Battery/background work | ✅ | State-driven stats, coalesced logs, deduplicated notifications/tile, lifecycle-aware timers. |

## Privacy and compliance

| Item | Status | Notes |
|---|---|---|
| No analytics/crash reporter/backend | ✅ | The app has no Firebase/analytics/crash SDK. |
| Public-IP check disclosure | ✅ | HTTPS to `api.ipify.org`; no credentials/profile data; can be disabled. |
| Privacy policy and Play VpnService disclosure | ❌ | Tracked in #15. |
| Store/legal requirements | ❌ | Tracked in #15. |

## Testing and assurance

| Item | Status | Notes |
|---|---|---|
| Unit tests | ✅ | 110 JVM tests covering state, import policy, stats, logs, reconnect mapping and the bounded pause-retry policy (PR #5). |
| Instrumentation tests | ✅ | Import, native lifecycle, Keystore, user/password profile tests. |
| Controlled-server tunnel | ✅ | Emulator test against OpenVPN 2.7 (TLS 1.3/AES-256-GCM, TUN, ICMP). |
| Leak/kill-switch matrix | ❌ | Tracked in #21. |
| MASVS/ASVS review and penetration test | ❌ | Tracked in #21. |
| Physical-device matrix | ❌ | Tracked in #21. |

## Out of scope for this repository

- iOS client: #20.
- Backend/account/PKI/server infrastructure: #16.
- Commercial privacy policy, store listing and legal review: #15.

## Highest-priority next steps

1. DNS leak protection (#8) and Always-on/lockdown onboarding (#9).
2. Configurable MTU (#10) and strict certificate/profile validation (#11).
3. Process-death/backoff lifecycle (#14) and split tunneling/captive portal if
   they are product requirements (#17, #18).
4. Supply-chain monitoring (#13), 16 KB device verification (#12), privacy/store
   compliance (#15), and the leak-test matrix (#21).
