# ADR-015: iOS platform scope — out of this repository for now

## Status

Accepted (scope decision). No iOS code, targets, or entitlements are added.
The plan and requirements are recorded in `docs/architecture/ios-plan.md`.

## Context

Issue #20 asks whether to build a companion iOS client. This repository is an
Android app (`VpnService` + OpenVPN 3 Core via JNI + Kotlin/Compose) with an
Android-specific build, lifecycle, storage and test setup. iOS is a different
platform with different primitives:

- `NEPacketTunnelProvider` inside a Network Extension, not `VpnService`;
- Keychain/Keychain sharing instead of Android Keystore/`noBackupFilesDir`;
- `includeAllNetworks`/exclusion and on-demand rules instead of Android
  per-app lists and Always-on;
- App Store guideline 5.4 (VPN apps) requirements: organization enrollment,
  VPN disclosure, privacy policy, licensing;
- a separate OpenVPN Core integration (an iOS adapter/NE provider), a separate
  core pin/update process, and a credential-sharing design between the app and
  the extension.

The Android codebase cannot be ported mechanically, and adding an unstaffed
iOS target would create a large, untested attack surface and a false
cross-platform claim.

## Decision

1. **Scope:** iOS is **out of scope for this repository for now**. This PR does
   not add an Xcode project, Swift sources, entitlements, or App Store
   metadata.
2. **Decision documented, not assumed.** `docs/architecture/ios-plan.md`
   records the platform decision, the technical requirements for an eventual
   client (`NEPacketTunnelProvider`, Keychain sharing, `includeAllNetworks`,
   on-demand rules, captive portals), App Store 5.4 compliance
   (organization enrollment, VPN disclosure, privacy, licensing), the shared
   core pin/update process, and the credential-sharing design.
3. **Reuse the core pin process and security policy decisions.** If iOS is
   funded later, it must track the same OpenVPN Core pin/SBOM/patch process,
   the same fail-closed DNS/trust policy intent, and the same release/patch
   SLA; the ADRs and security docs here are the reference, not a new
   independent policy.
4. **No premature shared-module refactor.** The Android client is not
   restructured now to be "cross-platform"; any shared library extraction is a
   follow-up with its own design and test plan, if iOS actually proceeds.
5. **Open decision inputs are listed.** The plan enumerates what product/legal
   must answer before implementation (organization enrollment, target markets,
   App Store privacy answers, licensing/AGPL implications of shipping a
   Network Extension, credential sharing between app and extension).

## Consequences

- The Android repository keeps a single, reviewable platform and CI; iOS work
  is not half-done here.
- The iOS plan is available for a future team without pretending it exists.
- If iOS becomes in scope, it needs its own repository/target, its own CI and
  device test matrix, an independent App Store review, and a licensing review
  (the pinned OpenVPN Core 3.9 is AGPL-3.0-only; distribution through the App
  Store has to be assessed with legal).
- There is no shared credential sync between platforms; users would maintain
  separate profiles unless a future backend (#16) provides account-based
  device enrollment.

## Alternatives considered

- **Build an iOS client now in this repo.** Rejected: no iOS toolchain, CI, or
  device test capability; would be untested and unmaintainable, and mixes
  platforms in one repository.
- **Defer without documenting.** Rejected: the issue and product-readiness
  review require an explicit scope decision and a plan.
- **Extract a shared core module first.** Rejected as speculative: the native
  core integration is platform-specific (JNI vs Network Extension), so a
  shared wrapper would be premature without a funded iOS target.

## References

- Issue: [#20](https://github.com/smhasnanmonir/openvpn/issues/20)
- `docs/architecture/ios-plan.md`
- `docs/decisions/ADR-001-openvpn3-pin.md` (core pin process)
- `docs/security/masvs-gap-assessment.md` (MASVS is Android-focused; an iOS
  client would map to MASVS for iOS separately)
