# Changelog

All notable changes to this project are documented here.

## [Unreleased]

### Fixed

- Long-lived sessions no longer disconnect at the first rekey when the server
  offers dynamic tls-crypt on the mbedTLS build. OpenVPN Core 3.9 advertised
  `IV_PROTO_DYN_TLS_CRYPT` even though the pinned mbedTLS backend cannot export
  keying material; the build now gates the advertisement on
  `support_key_material_export()` (ADR-006).
- A rekey/key-export failure or an unhandled Core exception now produces an
  actionable error message instead of the raw
  `client_connect_unhandled_exception` text. The engine detail remains in
  Diagnostics.
- A connection timeout during an automatic reconnect (for example after
  locking/unlocking the phone or a transient network transition) no longer ends
  an established session with a fatal error. Core pauses the session and the
  service retries when the network is available; an initial connection timeout
  still surfaces as an error (ADR-007).
- The Home screen's Download and Upload counters were swapped because Core's
  TUN counters use the tunnel device's direction. Download now maps to
  `TUN_BYTES_OUT` and upload to `TUN_BYTES_IN`, with the transport counters kept
  in the socket's direction for the fallback.
- Android Always-on VPN / lockdown starts the service with
  `VpnService.SERVICE_INTERFACE`; the service now connects the persisted
  selected profile with stored credentials instead of ignoring the start.
- CA-based profiles that did not explicitly constrain server-certificate usage
  now get `remote-cert-tls server` at import and connect time, preventing a
  client certificate from being accepted as the server.
- The VPN network now inherits the underlying network's metered state on
  Android 10+, so data-saver/background restrictions are not bypassed.
- The native library is linked with 16 KB page-size alignment for Android 15+
  devices.
- `UDP send exception: send: Message too long` now produces a Diagnostics hint
  about lowering `tun-mtu`; certificate-verification, proxy-credential and
  network-unavailable failures have actionable messages.

## [1.2.0] - 2026-11-10

### Added

- **Profile switching from Home.** The selected-profile row in the connection
  hero opens a profile switcher with each profile's emoji, server and protocol,
  the active profile marked, and a shortcut to profile management. Selection is
  refused while the tunnel is active, with the reason shown in the sheet.
- **Custom profile icons.** The icon picker accepts any emoji (one or two
  grapheme clusters, including ZWJ sequences, flags and keycaps) in addition to
  the built-in catalog. Input is validated with grapheme-aware rules, previewed
  live, and persisted through the existing encrypted profile store; plain text
  and oversized input are rejected.
- `docs/performance.md` records the memory/battery audit, the implemented fixes,
  the verification method, and the remaining structural hotspots.

### Changed

- Redesigned the Home "Network" section. The public-IP card now has explicit
  empty, loading, failure and result states; the result names the egress path
  (via tunnel or direct) and shows the time of the check. Loading uses a static
  skeleton rather than a shimmer, because an animation would keep the frame
  clock awake for the whole request.
- The public-IP lookup uses a 6 s connect/read timeout and no HTTP cache, and
  short-circuits with a clear message when the device has no usable network.

### Fixed

- A failed public-IP check could leave the previous address in state, so a
  retry could surface a stale IP. The check state now holds exactly one outcome;
  retries show the loading skeleton, results from a check started on a previous
  egress path are discarded, and the address is cleared when the tunnel starts
  or stops.
- Duplicate profile refresh at cold start (`AppContainer` and `AppViewModel`
  both decrypted every stored profile) removed.

### Performance

- Diagnostics are collected only by the Logs screen instead of the whole app,
  so engine log lines no longer recompose every screen.
- Diagnostics publication is coalesced (bounded 500-line buffer, one snapshot
  per 250 ms) instead of rebuilding the full list for every native line; errors
  are still published immediately.
- Traffic-stat polling is state-driven and screen-aware: only while
  `CONNECTED`/`RECONNECTING`, 1.5 s while interactive and 10 s while the screen
  is off. Connection details are no longer re-read on every poll.
- Foreground notification updates are deduplicated by rendered content and
  coalesced to one pending main-thread post; PendingIntents are cached rather
  than rebuilt per update.
- Quick Settings tile updates are skipped when the rendered
  `(state, label, subtitle)` did not change, so traffic statistics no longer
  cross into SystemUI every 1.5 s.
- Default-network callbacks are deduplicated by network identity, online state
  and transport, so capability churn no longer triggers notification/tile
  updates or underlying-network re-application.
- The profile repository retains a summary index plus at most one decrypted
  profile body instead of every profile's full config; bodies are decrypted on
  demand (`ProfileStore.load(id)`).
- The Home session chronometer runs only while the window is resumed; the
  notification's system chronometer covers the background case.
- Compose state models (`VpnSnapshot`, stats, details, profiles, settings, log
  entries) are marked `@Immutable` so unchanged state can be skipped.

## [1.1.2] - 2026-11-10

### Fixed

- Profiles imported by earlier versions keep their legacy-only `tls-cipher` pin
  in encrypted storage. The compatibility normalization is now also applied when
  connecting, so updating the app is enough to connect to TLS 1.3-only provider
  servers; re-importing the `.ovpn` is no longer required. The adaptation is
  logged to diagnostics ("Removed a legacy-only tls-cipher pin for this
  connection").

## [1.1.1] - 2026-11-10

### Fixed

- Provider profiles that pin only legacy (non-ECDHE) TLS 1.2 cipher suites now
  import and connect to servers that offer TLS 1.3 only. Such a `tls-cipher`
  pin made the TLS handshake stall until the connection timeout even with valid
  credentials, with no authentication error. The importer now drops a
  legacy-only `tls-cipher` directive, re-validates the profile, and logs the
  adaptation to diagnostics. Pins containing an ECDHE suite are kept and
  certificate verification is unchanged (ADR-005).
- Connections no longer fail right after `CONNECTED` when a server pushes
  asymmetric (server-to-client) LZO compression. The native bridge now sets
  `compressionMode = "asym"`, so providers that require it stay connected while
  symmetric compression remains refused (ADR-005).
- Connection failures are no longer swallowed. OpenVPN Core's fatal timeout
  events (`CONNECTION_TIMEOUT`, `INACTIVE_TIMEOUT`, `TLS_VERSION_MIN`) carry no
  event text, so diagnostics recorded nothing for them and the core's trailing
  `DISCONNECTED` event then cleared the transient error, leaving a clean
  "inactive" state with no explanation. Those events now map to explicit,
  actionable messages, fatal events always produce an ERROR log line, and a
  `DISCONNECTED` / `SESSION_FINISHED` event no longer downgrades a real
  `ERROR`/`AUTH_FAILED` state. A `DISCONNECTED` event during CONNECTING or
  RECONNECTING also no longer makes the UI look idle while the engine is still
  retrying. Adds `VpnController` state-machine tests and `EngineEventMapper`
  cases.

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
