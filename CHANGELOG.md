# Changelog

All notable changes to this project are documented here.

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
