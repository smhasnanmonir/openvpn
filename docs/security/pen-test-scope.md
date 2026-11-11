# Independent penetration test — scope and rules of engagement

**Status: not commissioned.** No independent test has been performed. This
document is the scope that must be agreed with the tester before a commercial
release; it is not evidence of a test.

Related: issue [#21](https://github.com/smhasnanmonir/openvpn/issues/21),
[`masvs-gap-assessment.md`](masvs-gap-assessment.md) §12,
[`leak-test-matrix.md`](leak-test-matrix.md), and the release gate in
[`../compliance/release-checklist.md`](../compliance/release-checklist.md).

## 1. Target

- **Application:** OpenVPN Client for Android, package
  `com.smhasnan.openvpn` (debug package `com.smhasnan.openvpn.debug`).
- **Build:** the signed release APK produced by the `v*` tag workflow. Record
  the commit, APK SHA-256, signing-certificate SHA-256
  (`834d2a58261a6c4113a3170953c1eea4a30d65ebc5ba4386408f09039dd00c9c` for the
  v1.3.0 key) and the SBOM with the report.
- **Source:** the matching commit of this repository, including the pinned
  OpenVPN 3 Core submodule (`10c6a56cb17fd015a2d24953c3c42145bbcdea58`).
- **Environment:** the tester's physical Android devices (at least one current
  Android major version; Android 15+/16 KB device preferred) plus a controlled
  OpenVPN server and network egress that the tester controls.

## 2. In scope

1. **Local data and credentials** — profile import (SAF/file, inline blocks),
   encrypted profile/credential storage, Keystore use, backup/exclusion rules,
   logs and diagnostics, screenshot/app-switcher exposure, `FLAG_SECURE` and
   app-lock gaps.
2. **Native and JNI surface** — malformed profile/`push` input, parser
   robustness, handle lifetime, crash/abort behavior, memory-safety findings
   reachable from the app or a hostile server.
3. **Android components and intents** — exported components, the
   `VpnService`/Always-on entry point, tile/notification actions, deep links,
   broadcast receivers, implicit intents, task hijacking.
4. **Network behaviour** — certificate validation against a hostile server
   (wrong CA, expired/not-yet-valid, client cert as server, hostname mismatch),
   `remote-cert-tls` enforcement, fingerprint-only rejection, non-AEAD cipher
   and compression server pushes, dynamic `tls-crypt` handling, TLS-version
   policy, DNS fail-closed/fallback, IPv4/IPv6 handling.
5. **Leak and lifecycle behaviour** — full-tunnel and split-tunnel leaks on
   physical devices, kill switch/Always-on/lockdown, Wi-Fi↔cellular, airplane
   mode, captive portal pause, process death, reboot/force-stop, another VPN
   taking over. Use the rows and capture method in
   [`leak-test-matrix.md`](leak-test-matrix.md); L-01–L-47 are the expected
   coverage.
6. **Supply chain and release** — CI/release workflow review (signing, secrets,
   artifact gating), SBOM accuracy for the tested APK, R8 keep rules, and
   whether the published artifact matches the reviewed source.

## 3. Out of scope

- The VPN server/provider and its infrastructure (unless the customer supplies a
  separate scope for a backend repository).
- Android OS, the OpenVPN 3 Core upstream project, mbedTLS/asio/fmt/jsoncpp/lz4
  upstream code as standalone projects (findings about the pinned integration
  are in scope; upstream-only issues are reported to their projects).
- Google Play Console account security, the maintainer's GitHub account, and
  social engineering of maintainers.
- Third-party VPN providers' servers; no traffic may be directed at systems the
  tester does not own or have written permission to test.
- Denial of service against production infrastructure; the app has no backend
  and no production service in this repository.

## 4. Rules of engagement

- Use **synthetic profiles, CAs, certificates and credentials** created for the
  test. Do not use real user profiles, real provider accounts, or production
  keys.
- Test only devices, networks and servers supplied or authorised by the project.
  Agree on the test window, contacts and an emergency stop channel in writing
  before starting.
- Do not exfiltrate personal data. If a test device contains test data only,
  state that in the report; stop immediately if real data is encountered.
- Preserve evidence: commands, captures, timestamps, device/build identifiers
  and screenshots. Redact secrets from the report.
- Report critical/high findings as soon as confirmed through the private
  channel in `SECURITY.md`; do not publish before a fix and coordinated
  disclosure window.
- The tester works against a staging copy of the repository; the maintainer
  merges any fixes.

## 5. Required test cases

| Area | Minimum cases | Reference |
|---|---|---|
| Profile/import | Malicious or malformed `.ovpn`, oversized inline blocks, path traversal/encoding, duplicate/renamed profile, CA-change warning | `docs/profile-import.md`, ADR-009 |
| Trust | Wrong CA, expired/not-yet-valid leaf, hostname mismatch, client cert as server, fingerprint-only profile, server-pushed `dyn-tls-crypt` | ADR-006, ADR-009 |
| DNS | No pushed DNS with "Require tunnel DNS" on/off, IPv4-only/IPv6-only pushes, invalid fallback resolver, reconnect/reconfiguration | ADR-008, L-06–L-12 |
| Cipher/compression | Non-AEAD push under both cipher policies, server-pushed compression under Compatibility/Strict | ADR-005, ADR-010, L-38–L-43 |
| Split tunnel | Include/exclude with zero apps, stale packages, app installed while connected, leak observation | ADR-011, L-34–L-37 |
| Captive portal | Detection, pause honesty, sign-in, auto-resume, failure path | ADR-012, L-28–L-30 |
| Lifecycle | Process death, reboot/Always-on, force-stop, another VPN, network loss/restore | ADR-007, ADR-013, L-25–L-33 |
| Local storage | Keystore availability, backup/restore, rooted-device data access, logs/screenshots | ADR-003/`docs/security.md`, L-13–L-17 |

## 6. Deliverables and acceptance

The tester delivers:

1. An executive summary with the target build identifiers and scope actually
   covered.
2. Per-finding detail: title, affected component, severity (CVSS v3.1 or v4.0),
   reproduction steps, evidence, impact, remediation advice, and references
   (MASVS/OWASP).
3. A coverage matrix showing which required cases were run, which passed, and
   which were not applicable — a case that was not run is not a pass.
4. A retest report for fixed findings against the same or a later build.

**Release gate:** no open critical or high finding before a commercial launch;
medium/low findings are triaged with owners and deadlines. Triage follows the
SLA and private-reporting process in [`SECURITY.md`](../../SECURITY.md). The
acceptance of issue #21 requires this report to exist and its findings to be
triaged — a clean desk review is not a substitute.

## 7. Commissioning checklist

- [ ] Choose an independent tester with Android/VPN/native experience; confirm
      no conflict of interest.
- [ ] Agree scope, rules of engagement, window, contacts and data handling in
      writing (this document is the starting point).
- [ ] Provide the tagged commit, signed APK + SBOM, a reproducible native-deps
      build, and synthetic profiles/CA/server.
- [ ] Run the test; handle critical/high findings immediately.
- [ ] Triage every finding into an issue with severity, owner and deadline.
- [ ] Fix, retest, and record the retest report with the release evidence.
- [ ] Update the MASVS assessment and `docs/security-readiness.md` with the
      actual status; do not describe an untested area as verified.
