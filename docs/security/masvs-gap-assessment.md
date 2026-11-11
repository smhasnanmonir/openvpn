# MASVS v2 gap assessment and assurance status

> **Status: desk assessment, not a certification and not an independent audit.**
> This document maps the OWASP Mobile Application Security Verification Standard
> (MASVS) v2 categories to the current Android client as of this branch. It does
> not claim compliance. Several controls are intentionally out of scope for a
> profile-based VPN client, and no external penetration test has been performed
> for this repository.

## 1. Scope and method

- Target: Android client in this repository (`com.smhasnan.openvpn`), the
  OpenVPN 3 Core 3.9 integration, the build/release pipeline, and the documented
  operational behaviour.
- Method: desk review against MASVS v2 categories, checked against the code,
  docs and existing verification records (`docs/testing.md`,
  `docs/security-readiness.md`, `docs/security/leak-test-matrix.md`).
- Out of scope: backend/API/PKI/gateways (none exist; see
  `docs/architecture/backend-control-plane.md`), iOS (none exists; see
  `docs/architecture/ios-plan.md`), upstream vulnerabilities in OpenVPN
  3/mbedTLS that are not reachable through this app (report upstream), and
  rooted/compromised-device scenarios.
- Status legend: ✅ implemented and covered by code/tests/docs; 🟡 partial or
  configuration-dependent; ❌ not implemented / not verified; N/A not applicable
  to this product or outside the repository.

The MASVS v2 categories are: STORAGE, CRYPTO, AUTH, NETWORK, PLATFORM, CODE,
RESILIENCE, PRIVACY. Control IDs are shown as `MASVS-<CATEGORY>-n`; this
document summarises the control intent rather than quoting the standard.

## 2. Summary

| Category | Status | Headline gap |
|---|---|---|
| MASVS-STORAGE | 🟡 mostly ✅ | Secret storage is strong; no device/session tamper proof; logs are in-memory but share the process. |
| MASVS-CRYPTO | ✅ / 🟡 | Core/mbedTLS does crypto; profile storage is AES-256-GCM; non-AEAD data ciphers are possible unless AEAD-only is enabled. |
| MASVS-AUTH | 🟡 / N/A | No app account; VPN auth is Core/provider; biometric gate is UI-only, not key-bound. |
| MASVS-NETWORK | ✅ / 🟡 | TLS + certificate verification enforced; fail-closed DNS; no custom crypto; pinning not implemented. |
| MASVS-PLATFORM | 🟡 | Minimal permissions/exports; missing `FLAG_SECURE`, no explicit IPC hardening review for future components. |
| MASVS-CODE | ✅ / 🟡 | Modern SDK, pinned deps, lint/R8; dependency advisory automation added; no full reproducibility/signing provenance. |
| MASVS-RESILIENCE | ❌ (accepted) | No root/tamper/repackaging detection; deliberate scope decision for a community client. |
| MASVS-PRIVACY | ✅ / 🟡 | No analytics/backend; optional public-IP check disclosed; policy added; store answers pending publication. |

## 3. MASVS-STORAGE — sensitive data storage

| Control | Status | Evidence / gap |
|---|---|---|
| STORAGE-1: sensitive data stored securely | ✅ | Profiles and credentials encrypted with AES-256-GCM under non-exportable Android Keystore keys in `noBackupFilesDir`; credentials separate; atomic writes; no plaintext profile on disk. |
| STORAGE-2: sensitive data not exposed via UI/logs | 🟡 | Logs are sanitized in Kotlin and redacted in C++ before crossing JNI; logs are memory-only and cleared on process exit. Residual: logs and secrets share the app process, so a same-UID compromise can read memory; no `FLAG_SECURE` on screens that show profile metadata (not secrets, but a privacy gap for shoulder-surfing/screenshots). |
| Backup/transfer protection | ✅ | `allowBackup=false`, `fullBackupContent=false`, `data_extraction_rules.xml` excludes all domains. |
| Biometric/key binding | 🟡 | The app lock is a UI gate (`BiometricPrompt`, BIOMETRIC_STRONG); it is **not** bound with `setUserAuthenticationRequired` to the Keystore keys. Documented; no claim of hardware-bound auth. |

**Gap/decision:** if a stronger threat model is adopted, bind the Keystore key to
user authentication and add `FLAG_SECURE` where appropriate. Not changed in this
PR.

## 4. MASVS-CRYPTO — cryptography

| Control | Status | Evidence / gap |
|---|---|---|
| CRYPTO-1: current, strong cryptography | 🟡 | TLS/certificate/data-channel crypto is entirely OpenVPN 3 Core 3.9 + mbedTLS 3.6.2; the app implements none. AEAD data ciphers are used when negotiated, but profiles/servers can still select non-AEAD ciphers unless the new AEAD-only policy is enabled (ADR-010). Compression compatibility mode remains the default (documented trade-off; strict mode added). |
| CRYPTO-2: key management | ✅ | Non-exportable Keystore keys; no key material in logs, backups, or APK; profile keys are the user's/provider's. The pinned core/mbedTLS are reviewed via SBOM/advisory process (#13). |
| No custom crypto | ✅ | The app never implements TLS/ciphers itself; generated `proto.hpp` patches only change capability advertisement/error paths, not crypto (ADR-006/ADR-010). |
| Crypto self-test | ✅ | `crypto_self_test()` from the core is exposed and run in instrumentation. |

**Gap:** pinning (certificate/public-key pinning for the profile's server) is not
implemented; the profile's CA/`peer-fingerprint` is the trust mechanism. Pinning
is not a substitute for a correct PKI, and the app does not claim it.

## 5. MASVS-AUTH — authentication and authorization

| Control | Status | Evidence / gap |
|---|---|---|
| AUTH-1: secure authentication | 🟡 / N/A | No app account/backend. VPN authentication (username/password, static/dynamic challenge, client certs) is done by OpenVPN Core against the user's provider; challenge responses are not persisted. |
| AUTH-2: secure authorization of protected functions | ✅ | No privileged in-app account features; VPN consent is enforced by Android (`VpnService.prepare()`); the service is not exported and requires `BIND_VPN_SERVICE`. |
| AUTH-3: biometric authentication | 🟡 | Optional UI lock uses `BiometricPrompt` with BIOMETRIC_STRONG; cancellation/failure does not unlock; no fallback bypass. Not key-bound; documented. |
| Device-unique credentials | N/A | Profiles are user-supplied; per-device credentials belong to the future backend (#16). |

**Gap:** MFA/short-lived tokens/device management are backend concerns and do
not exist in this repository (ADR-014).

## 6. MASVS-NETWORK — network communication

| Control | Status | Evidence / gap |
|---|---|---|
| NETWORK-1: secure traffic encryption | ✅ | TLS via OpenVPN Core/mbedTLS; legacy-only TLS 1.2 cipher pins dropped to allow TLS 1.3 (ADR-005); `usesCleartextTraffic=false`; the public-IP check uses HTTPS. The VPN data path is the OpenVPN protocol, not cleartext. |
| NETWORK-2: endpoint authenticity | ✅ / 🟡 | Certificate verification never disabled; `remote-cert-tls server` enforced for CA-based profiles; strict trust policy requires CA/peer-fingerprint/`tls-remote` (ADR-009); CA-change warning on re-import. Gap: no client-side CRL/OCSP (revocation is server/PKI-side; documented); no pinning. |
| No cleartext/fallback | ✅ / 🟡 | No cleartext traffic config; fail-closed tunnel DNS by default (ADR-008); Android family blocking kept. Residual: if the user disables the DNS requirement or a split-tunnel app is excluded, traffic outside the tunnel is expected and warned about. |

**Gaps:** revocation is not client-enforced (server/PKI responsibility,
ADR-009); DNS/leak runtime behaviour still needs the device matrix
(`docs/security/leak-test-matrix.md`).

## 7. MASVS-PLATFORM — platform interaction

| Control | Status | Evidence / gap |
|---|---|---|
| PLATFORM-1: minimal permissions | ✅ | Manifest requests only `INTERNET`, `ACCESS_NETWORK_STATE`, notifications, FGS specialUse, biometrics; no contacts/location/storage/SMS. |
| PLATFORM-2: secure IPC | ✅ / 🟡 | `MyVpnService` is `exported=false` + `BIND_VPN_SERVICE`; only the launcher activity and QS tile service are exported (tile is protected by `BIND_QUICK_SETTINGS_TILE`); no broadcast receivers/providers. No deep links. Future components must be reviewed. |
| PLATFORM-3: secure use of platform APIs | 🟡 | `VpnService`, `VpnService.protect()`, Keystore, biometric APIs used as intended; `allowFamily` limitations documented; no `FLAG_SECURE`, no clipboard secrets (logs only, sanitized). Screenshots of profile metadata are possible (not credentials). |
| Always-on/lockdown integration | 🟡 | Handles `VpnService.SERVICE_INTERFACE`, `START_STICKY` for system start; best-effort readiness detection via hidden `Settings.Secure` keys, UNKNOWN when unreadable; never claims a guaranteed kill switch. |

**Gap:** `FLAG_SECURE` on sensitive screens and a formal exported-component
review for future features.

## 8. MASVS-CODE — code quality and build settings

| Control | Status | Evidence / gap |
|---|---|---|
| CODE-1: current platform/SDK | ✅ | `compileSdk 37`/`targetSdk 35`, `minSdk 26`, JDK 17, AGP 9.5.1, Gradle 9.9.1, Kotlin 2.4.21, NDK 27.2.12479018. |
| CODE-2: up-to-date components | 🟡 | OpenVPN Core/mbedTLS/asio/fmt/jsoncpp/lz4 are pinned and documented; SBOM + osv-scanner + Dependabot + advisory SLA added (#13). Residual: core updates are manual and can lag advisories; the SLA (critical 7 / high 30 / medium 90 days) now makes that explicit. |
| CODE-3: safe APIs / memory safety | 🟡 | Native code is a narrow JNI bridge around the pinned core; no unsafe deserialization; JSON parsing via jsoncpp/org.json. Known pre-existing native handle race on `connect()`/stats vs close is tracked under #14/lifecycle; the resume path is locked. |
| CODE-4: error handling | ✅ / 🟡 | Fatal engine events surface actionable errors; a `DISCONNECTED` event no longer erases errors; bounded retry ends with an explicit unprotected-traffic error. Residual: third-party log text sanitisation is defence-in-depth, not a guarantee. |
| Build hardening | ✅ / 🟡 | R8/minify/release signing; JNI keeps; `-fstack-protector-strong`, `-D_FORTIFY_SOURCE=2`, RELRO/NOW and 16 KB max-page-size (CI gate); hidden visibility. Gap: no full reproducible-build provenance or Gradle dependency verification metadata (documented in `docs/release.md`). |
| Tests | ✅ / 🟡 | JVM tests for policies/state/import/stats, instrumentation tests for import/native/Keystore; CI runs unit tests/lint/build. Gap: no device-farm leak tests (see matrix). |

## 9. MASVS-RESILIENCE — anti-tampering/resilience

Deliberately **not implemented** for this community client; documented as a
scope decision rather than a silent gap.

| Control | Status | Note |
|---|---|---|
| RESILIENCE-1..4 (root/jailbreak, tamper, debugger, repackaging) | ❌ accepted | No root detection, integrity attestation, anti-debug, or repackaging response. A rooted/compromised device is outside the threat model (`docs/security.md`). Play Integrity/attestation would add a backend dependency and is out of scope (#16). |

If the product later requires resistance against a local attacker, that needs its
own design (and a backend for attestation decisions), not a client-only check.

## 10. MASVS-PRIVACY — privacy

| Control | Status | Evidence / gap |
|---|---|---|
| PRIVACY-1: data minimisation | ✅ | No accounts, no backend, no analytics/ads/crash SDK; the app stores only what the user imported plus settings; optional public-IP check can be disabled. |
| PRIVACY-2: no user identification/tracking | ✅ | No advertising ID, device fingerprinting, or tracking; no cookies; no developer backend. |
| PRIVACY-3: third-party data protection | 🟡 | Only disclosed third party is `api.ipify.org` (public-IP check, ephemeral); the VPN provider is the user's choice. Play Data-safety answers and privacy policy are drafted (`docs/legal/`); publication URL and store submission are still manual. |
| PRIVACY-4: user control/transparency | ✅ / 🟡 | In-app profile/credential deletion, IP-check opt-out, documented policy. Gap: the policy must be published at a public URL and the Play answers submitted before distribution. |
| Privacy policy / legal basis | 🟡 | `docs/legal/privacy-policy.md` is complete and truthful; publication + Play submission pending (manual, out of app code). |

## 11. ASVS note for a future backend

The client is an Android app; a future backend (issue #16, ADR-014) must be
assessed against the **OWASP ASVS** (Application Security Verification
Standard), not MASVS:

- Target ASVS level 2 for the public API/control plane, with level 3 for PKI
  issuance/admin paths if the product requires it.
- Required evidence: authenticated/authorization tests, token rotation and
  revocation tests, session/MFA flows, input validation, cryptography and key
  management (HSM/KMS), logging/monitoring without secret leakage, and
  configuration/hardening review.
- The backend has its own disclosure/patch SLA and incident-response plan; the
  client `SECURITY.md` SLA does not transfer automatically.
- No backend exists today; nothing above is claimed as implemented.

## 12. Independent penetration test

An independent penetration test is **required before any commercial launch** and
has **not** been performed for this repository. The commissionable scope, rules
of engagement, required cases and release gate are in
[`pen-test-scope.md`](pen-test-scope.md). Minimum scope:

1. Android app: local storage, Keystore usage, profile import paths, JNI/native
   surface, service/component exposure, intent handling, app-lock bypass,
   log/secret exposure, split tunneling/captive portal state honesty.
2. Network behaviour: TLS/certificate validation against a hostile server
   (wrong CA, expired cert, client-cert-as-server, hostname mismatch, non-AEAD
   cipher/server push, compression push, dynamic tls-crypt), DNS fail-closed
   behaviour, leak/kill-switch behaviour on physical devices.
3. Supply chain/release: CI pipeline, signing/secrets, SBOM accuracy,
   dependency advisories, R8 keep rules, artifact integrity.
4. If a backend exists by then: ASVS-scoped API/PKI/gateway test with rules of
   engagement, test accounts/devices, and a retest of findings.

Rules of engagement: no testing against real users' servers or third-party
providers; use synthetic profiles/CA and a controlled server; agree on
window/contacts and data handling before the test. Deliverables: findings with
reproduction, severity, remediation, and a retest; track them like security
bugs (SLA in `SECURITY.md`).

## 13. Prioritized gaps from this assessment

1. **Runtime leak verification is unverified** — device matrix pending
   (`docs/security/leak-test-matrix.md`). This is the largest assurance gap.
2. **Release provenance** — no reproducible build/Gradle dependency
   verification metadata; SBOM/advisory monitoring now exists (#13).
3. **`FLAG_SECURE` / key-bound app lock** — accepted gap; document instead of
   overclaiming.
4. **Revocation is server/PKI-side** — documented; no client CRL/OCSP
   (ADR-009).
5. **Backend ASVS/pen test** — future work if #16 proceeds.
6. **Dependency patch cadence** — now has an SLA and owner (SECURITY.md), but
   core updates are still manual and need periodic drills.

### Status of issue #21

| Acceptance criterion | Repository deliverable | Status |
|---|---|---|
| Documented leak-test matrix passes on physical devices | [`leak-test-matrix.md`](leak-test-matrix.md) (rows, method, capture commands, pass criteria) | Matrix ready; **no physical-device run yet** |
| MASVS/ASVS gap assessment complete | This document (MASVS v2 + ASVS note) | Done (desk assessment) |
| Independent penetration-test findings triaged | [`pen-test-scope.md`](pen-test-scope.md) (scope, rules, gate, triage process) | Scope ready; **test not commissioned** |
| CI covers the automated subset | `leak-test-matrix.md` §6: JVM policy tests, 16 KB alignment gate, SBOM/advisory scan, release APK gates | Done for the listed subset; instrumentation and packet capture are not in CI |

Issue #21 stays open until the device rows are executed and a penetration-test
report has been triaged; the repository deliverables are complete.

## 14. Statement of limitations

This assessment is a desk exercise; it is not a MASVS certification, not an
independent audit, and not a guarantee that the app is free of vulnerabilities.
Rows marked ✅ mean "implemented and documented/tested to the extent described",
not "verified by a third party". The app handles user-supplied VPN profiles to
user-chosen servers, so server/provider behaviour and physical network
conditions remain outside the app's control.

## References

- OWASP MASVS: <https://mas.owasp.org/MASVS/>
- OWASP ASVS: <https://owasp.org/www-project-application-security-verification-standard/>
- `docs/security.md` (threat model), `docs/security-readiness.md` (status map)
- `docs/security/leak-test-matrix.md`
- `docs/architecture/backend-control-plane.md`, `docs/architecture/ios-plan.md`
- `SECURITY.md` (disclosure, SLA, emergency release)
