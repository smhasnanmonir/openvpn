# iOS client — platform scope and plan

> **Status: scope decision and plan, not implemented.** No iOS code, Xcode
> project, Swift sources, entitlements, or App Store metadata exist in this
> repository. This document records the decision (ADR-015) and what an eventual
> iOS client would require, so the work can be planned and reviewed before it is
> started.

## 1. Decision

iOS is **out of scope for this repository for now**. Reasons:

- This repository is an Android app built on `VpnService`, Kotlin/Compose,
  Android Keystore, and a JNI integration of OpenVPN 3 Core. iOS uses a
  Network Extension (`NEPacketTunnelProvider`) with a separate lifecycle,
  storage, and distribution model; the Android code cannot be ported
  mechanically.
- There is no iOS toolchain, CI runner, signing setup, or device test matrix in
  this repository, and an untested iOS target would be a large, unmaintained
  attack surface.
- App Store guideline 5.4 (VPN apps) adds organizational, disclosure, privacy,
  and licensing requirements that need product/legal decisions before code.

The plan below is the baseline if the decision changes. Any iOS work starts in
its own repository/target with its own threat model, CI, and release process,
reusing this repo's security decisions where they apply.

## 2. Platform architecture (target)

```
iOS app (SwiftUI/UIKit)                 ← Keychain, UI, profile import, settings
  └── Network Extension target
        └── NEPacketTunnelProvider     ← owns the tunnel, packet I/O
              ├── NEPacketTunnelFlow (read/write packets)
              ├── NENetworkSettings (routes, DNS, MTU, included/excluded networks)
              └── OpenVPN engine integration (C++ core via an adapter)
```

Key differences from Android:

| Concern | Android (this repo) | iOS (future) |
|---|---|---|
| Tunnel | `VpnService` + TUN fd handed to OpenVPN Core | `NEPacketTunnelProvider` with packet-flow read/write |
| Core integration | JNI bridge (`native_bridge.cpp`) | Objective-C++/Swift adapter + Network Extension APIs; OpenVPN 3 Core needs a provider implementation |
| Per-app routing | `addAllowedApplication`/`addDisallowedApplication` | Not available; `includedNetworkRules`/`excludedNetworkRules` and on-demand rules |
| Always-on | Android Always-on VPN/Lockdown | On-demand rules + `includeAllNetworks`; MDM supervision for managed devices |
| Secrets | Android Keystore + `noBackupFilesDir` | Keychain with access group shared between app and extension |
| Background lifetime | Foreground service | Extension process managed by the system; `NEProvider` lifecycle |
| Distribution | Play / APK | App Store, TestFlight; guideline 5.4 |

## 3. Technical requirements

### 3.1 Network Extension

- `NEPacketTunnelProvider` subclass; network settings must be applied through
  `NEPacketTunnelNetworkSettings` (DNS servers, search domains, IPv4/IPv6
  routes, MTU).
- Packet flow: the OpenVPN engine's TUN abstraction must be adapted to
  `NEPacketTunnelFlow` (read/write packets) instead of a file descriptor. This
  is a non-trivial adapter; the pinned core's `TunBuilderBase` fd model does
  not map 1:1.
- `NEPacketTunnelProvider` must handle `startTunnel`, `stopTunnel`,
  `sleep`/`wake`, network-path changes, and `onDemand` activation.
- Full-tunnel behaviour: `includeAllNetworks = true` (iOS 14+) for a true
  full tunnel; document that excluded domains/routes are deliberate and
  visible. Use `excludedNetworkRules` only with an explicit warning, matching
  the Android split-tunnel warning policy (ADR-011).
- On-demand rules: `NEOnDemandRuleConnect` with SSID/DNS/interface matching
  where appropriate; always keep a manual disconnect path.
- Captive portals: iOS handles portal sign-in at the system level; the
  extension must not claim protection while the tunnel is down. If the VPN is
  on-demand, portal sign-in can be blocked; provide a documented pause path or
  on-demand rule that allows the portal (ADR-012 equivalent).
- Crash/restart behaviour: iOS may restart the extension; persist only
  *desired state* (profile id), never a packet-flow handle (ADR-013 equivalent).

### 3.2 Keychain and credential sharing

- App and extension are separate processes; secrets must be shared through a
  Keychain access group (`keychain-access-groups` entitlement) with
  `kSecAttrAccessibleAfterFirstUnlock` (needed for background extension
  start) and, for stronger secrets, `...ThisDeviceOnly`.
- Store profile material and credentials in the shared Keychain (or an
  encrypted file protected by a Keychain key); do not duplicate secrets in
  plaintext app-group containers.
- The extension must be able to access the selected profile/credentials after
  the device unlocks, including after a restart; document the pre-first-unlock
  limitation (same as Android Keystore).
- Never log credentials or profile contents; the extension's logs are visible
  to the OS/tools and must stay sanitized.

### 3.3 OpenVPN engine integration

- Decide between maintaining an Objective-C++ adapter around the pinned
  OpenVPN 3 Core (AGPL-3.0-only with OpenSSL exception) and using an existing
  iOS OpenVPN 3 adapter if its licence and pin are acceptable.
- Reuse the same core pin/review process as Android: pinned commit, build-time
  patches only with fail-fast checks, SBOM per release, advisory monitoring,
  patch SLA. Do not diverge silently from this repo's security decisions.
- The mbedTLS/dynamic-tls-crypt and strict-compression decisions
  (ADR-006/ADR-010) apply to the same core; re-evaluate with the iOS TLS backend
  (Apple's Network.framework/TLS or mbedTLS) rather than copying flags blindly.
- Fail closed: no "accept all certificates", CA/fingerprint required
  (ADR-009), DNS fail-closed equivalent where the platform allows (ADR-008).

### 3.4 Platform security

- App/extension entitlements: Network Extension, Keychain sharing, App Groups
  only where required; no unnecessary capabilities.
- No analytics/ad SDKs, no backend by default; keep the privacy posture
  identical to the Android client (privacy policy must be shared or adapted).
- App Transport Security on; no `NSAllowsArbitraryLoads`; the VPN data path is
  not ATS traffic but the control/API path must be TLS.
- Store app metadata: privacy nutrition labels must match actual behaviour.

## 4. App Store guideline 5.4 (VPN apps)

Apple requires, for apps that provide VPN services:

- **Organization enrollment** for the Apple Developer Program (not an
  individual account) for apps that provide VPN services; this is the first
  gating item.
- **Disclosure** in the app/listing that the app provides a VPN service, what
  it does, and that it does not collect user data without consent.
- **Privacy policy** and accurate App Store privacy answers.
- **Licensing**: the OpenVPN core is AGPL-3.0-only at the pinned revision;
  distributing through the App Store has licensing implications (source
  availability, any static linking/exception nuances) that must be reviewed
  with legal before submission.
- No misleading claims, no hidden traffic manipulation, no ad injection; the
  VPN must not be used to bypass Apple/platform rules.
- On-demand/VPN configurations must be transparent and removable by the user.

## 5. Credential- and profile-sharing design (target)

- Same profile format (`.ovpn`) so users can move profiles between platforms,
  but the storage/encryption layer differs (Keychain vs Android Keystore).
- No automatic cross-device sync in the profile-only model. If a backend (#16)
  is introduced, profiles become server-signed and device-scoped; the iOS app
  enrolls through the same API contract and stores the device key in the Secure
  Enclave/Keychain.
- Import flow: Files app document picker → parse with the core → store
  encrypted in the shared Keychain/container → extension reads the selected
  profile.
- Do not invent a "QR code sync" or iCloud sync mechanism without a privacy
  review.

## 6. Build, CI, and release plan (if approved)

1. New repository/target (or a clearly separated `ios/` workspace) with Xcode
   project, Swift Package/CocoaPods dependency pinning, and the core pin as a
   submodule or vendored source with a documented build script.
2. CI on macOS runners: build app + extension for simulator and device,
   unit tests, UI smoke test, static analysis (SwiftLint), licence/SBOM check,
   secret scan, dependency advisory scan.
3. Device test matrix: iPhone models, iOS versions, Wi-Fi↔cellular, captive
   portal, on-demand reboot, extension crash, low-power mode, 5G/dual-stack,
   MDM-managed `includeAllNetworks` behaviour.
4. TestFlight beta, then App Store review with the guideline 5.4 evidence.
5. Release notes and privacy policy update; SBOM and patch SLA same as
   Android.

## 7. Open decisions (blocking before implementation)

Each decision needs a named owner outside this repository; none can be resolved
by the Android codebase. The current answers are the documented status quo, not
assumptions.

| Decision | Owner role | Needed by | Current status |
|---|---|---|---|
| Is iOS a funded product requirement? | Product owner | Before any iOS work | **Documentation-only** (ADR-015) |
| Which organization owns the Apple Developer account; is organization enrollment feasible? | Engineering manager + legal | Before TestFlight | Not started — organization enrollment is a guideline 5.4 gate |
| Which App Store markets are targeted, and are VPN apps permitted there? | Product + legal | Before submission | Not assessed |
| Is AGPL-3.0 compatible with the intended App Store distribution model? | Legal (with maintainer) | Before implementation | Not reviewed |
| Is the backend (#16) the credential/profile-sharing mechanism, or are profiles per platform? | Product + security | Before Phase 1 | Unresolved; backend is not funded (#16) |
| Who owns iOS maintenance, the OpenVPN Core pin and the security response SLA? | Engineering manager | Before implementation | Unassigned |

## 8. What this repository does instead

- Keeps the Android client and its security decisions as the reference
  implementation (fail-closed DNS/trust, explicit compression/cipher modes,
  split-tunnel warnings, captive-portal honesty, desired-state lifecycle).
- Documents the iOS decision and plan (this file, ADR-015) so the work does not
  start from zero if it is funded.
- Does **not** add iOS code, entitlements, or store metadata, and does not
  claim cross-platform support.

## 9. Submission checklist and acceptance status

If iOS is approved, these items must be evidenced before App Store submission.
None of them is satisfied today because there is no iOS target.

- [ ] Organization Apple Developer Program enrollment confirmed (not individual).
- [ ] `NEPacketTunnelProvider` target builds for device and simulator; extension
      entitlements reviewed.
- [ ] Keychain access groups shared between app and extension; profiles
      encrypted at rest; no plaintext credentials in logs or defaults.
- [ ] `includeAllNetworks`/exclusions and on-demand rules defined and documented
      for users; a manual disconnect path always works.
- [ ] Captive-portal, Wi-Fi↔cellular, reboot and extension-crash behaviour tested
      on physical devices.
- [ ] Privacy policy and App Store privacy answers match the shipped behaviour
      (reuse or adapt `docs/legal/privacy-policy.md`).
- [ ] Guideline 5.4 disclosure text in the listing; no misleading VPN claims.
- [ ] AGPL-3.0 distribution review completed and recorded.
- [ ] OpenVPN Core pin, SBOM/advisory scanning and patch SLA shared with the
      Android process.

### Status of issue #20

| Acceptance criterion | Repository deliverable | Status |
|---|---|---|
| Platform scope documented | Section 1 and ADR-015 | Done (documentation-only decision) |
| iOS architecture and entitlement plan exist if in scope | Sections 2–5 | Done (plan only; no code) |
| Keychain/credential-sharing design reviewed | Section 5 | Design written; **not security-reviewed** (no iOS implementation) |
| App Store compliance checklist is complete | Sections 4 and 9 | Done (checklist ready; items unverified) |

Issue #20 stays open: closing it requires an approved iOS project and external
Apple/legal evidence, not more Android code.

## References

- Issue: [#20](https://github.com/smhasnanmonir/openvpn/issues/20)
- ADR-015 (scope decision)
- `docs/architecture.md`, `docs/security.md`
- `docs/security/masvs-gap-assessment.md` (MASVS is Android-focused; an iOS
  client maps to MASVS for iOS separately)
- `docs/architecture/backend-control-plane.md` (future shared identity/PKI)
