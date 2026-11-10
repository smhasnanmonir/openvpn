# Changelog

All notable changes to this project are documented here.

## [1.1.0] - 2026-11-10

### Changed

- Redesigned the interface on a new design system: calmer type scale, tonal
  surface layers instead of outlined cards, a gradient connection hero with a
  single primary action, a floating pill bottom navigation bar, grouped Settings
  sections with a segmented theme control, compact profile cards with
  authentication chips, and a cleaner diagnostics list. Screenshots in the
  README and `docs/screenshots/` were re-captured.

### Fixed

- Fixed the Settings diagnostics row where the long OpenVPN "Platform" value
  collided with its label. Key/value rows now reserve the label width and let a
  long value wrap right-aligned instead of overlapping.
- Fixed import rejection of username/password-only `.ovpn` profiles (for
  example commercial provider exports such as NCVPN) that carry an inline CA
  but no client certificate or key. OpenVPN Core 3.9 reports those valid
  profiles as `externalPki`, so the importer refused them with "External PKI
  profiles are not supported". The importer now appends Core's supported
  `setenv CLIENT_CERT 0` directive to the stored merged profile, re-validates
  it, records `clientCertNotRequired` metadata, and shows "Sign-in · no client
  certificate" in the profile list. Profiles that genuinely request an external
  PKI provider, and certificate-less profiles with no username/password sign-in,
  are still rejected. Server certificate verification is unchanged. See
  `docs/decisions/ADR-004-certificate-less-userpass-profiles.md`.

## [1.0.2] - 2026-11-10

### Fixed

- Fixed an Activity field initialization crash (`getMainExecutor()` before
  `attachBaseContext`) that prevented the app from launching.
- Added an explicit `androidx.fragment` dependency so Compose activity-result
  launchers work with `FragmentActivity`/BiometricPrompt (SAF import crash).
- Fixed the critical TunBuilder wiring: `OvpnClient` now delegates the
  `TunBuilderBase` virtuals to `AndroidTunBuilder`, allowing the Android TUN to
  be created and packets to flow.
- Hardened service connect/disconnect races and duplicate connection requests.
- Enabled and parsed the real mbedTLS self-test, and fixed the success/failure
  result parsing.

### Verified

- Real tunnel on Android 11 x86_64 emulator against OpenVPN 2.7:
  TLS 1.3, AES-256-GCM, TUN 10.8.0.2, ICMP through tunnel, clean disconnect.
- 5 instrumentation tests pass on the emulator.
- Published v1.0.0/v1.0.1 are superseded.

## [1.0.1] - 2026-11-10

### Changed

- Added full third-party license texts under `licenses/`.
- Recorded published artifact verification and CI results.

## [1.0.0] - 2026-11-10

### Added

- Kotlin/Compose Android OpenVPN client using Android `VpnService`.
- Official OpenVPN 3 Core 3.9 integration (`10c6a56`) via a narrow JNI bridge.
- Multiple `.ovpn` import via Storage Access Framework, including external
  certificate/key companion files.
- AES-256-GCM encrypted profile and credential storage backed by Android
  Keystore, excluded from backups.
- Profile selection, rename (display name only), delete, emoji avatars,
  duplicate detection, and search.
- Real engine state machine, connection details, traffic counters, and
  sanitized in-memory diagnostics.
- Foreground service notification with Disconnect/Reconnect actions and a
  Quick Settings tile.
- Public IP lookup through the active network.
- Fingerprint/strong-biometric app lock.
- IPv4/IPv6 family policy based on Android's default VPN routing, with explicit
  opt-out warning.
- Unit tests, instrumentation-test APK, Android lint, R8 keep rules, signed
  release build, and GitHub Actions release publishing.
- Documentation for architecture, security, core integration, import, testing,
  and release.

### Known limitations

- Live tunnel/device tests are not yet performed; see `docs/testing.md`.
- External PKI, TAP, WINS, HTTPS/PAC proxy, and DoH/DoT DNS are unsupported.
- Android cannot block a configured address family that the tunnel carries;
  the app logs this instead of claiming enforcement.
- No application-level guaranteed kill switch.
