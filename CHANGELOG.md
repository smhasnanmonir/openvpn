# Changelog

All notable changes to this project are documented here.

## [Unreleased]

## [1.5.1] - 2026-11-12

### Security

- **FLAG_SECURE window protection** (`MainActivity`): the Recents thumbnail and
  screenshots no longer expose server details when the biometric app lock is
  armed (2026-11-12 audit, finding 1).
- **Log redaction covers inline credential directives.** `auth-user-pass`,
  `proxy-user-pass` and `proxy-password` are now redaction markers on both the
  Kotlin and C++ sides, so credentials quoted in a merge/eval error can no
  longer reach the diagnostics log or its clipboard export (finding 2).
- **The static-challenge response travels in-process** through
  `CredentialHandoff` instead of an intent extra, matching the rule that
  credentials never cross component boundaries in intents (finding 3).
- **Split-tunnel app list and fallback DNS are encrypted at rest** with the
  Keystore-backed `SecretBox` (one-time migration from the old plaintext
  preferences; corrupt values fail soft to empty) (finding 4).
- 2026-11-12 code security audit and remaining-work reports added under
  `docs/security/code-audit-2026-11-12.md` and
  `docs/remaining-work-audit-2026-11-12.md`.

### UI

- **Visual refresh across every screen**: brighter spring-green palette with
  warm amber and coral support tones, rounder cards (26dp), pill-shaped
  buttons, segmented controls and dialogs, an extra-bold session timer, and
  softer profile avatars. All functional behavior is unchanged.

### CI

- **Fixed the signed-release gate for build-tools 37.** `apksigner` changed its
  output from `Signer #1 certificate SHA-256 digest:` to
  `V3.0 Signer: certificate SHA-256 digest:`, so the v1.5.0 tag job failed with
  "could not read the release certificate SHA-256 digest". The gate now matches
  the digest field for both formats and fails explicitly if a key rotation
  produces more than one distinct signer digest.

### Documentation and compliance

- Privacy policy publication/Play submission runbook (`docs/legal/README.md`)
  and an in-app **Settings → About → Privacy policy** link
  (`BuildConfig.PRIVACY_POLICY_URL`).
- Backend and iOS plans now record role owners, runbook requirements, decision
  owners and acceptance status (#16/#20) instead of unnamed placeholders.
- Commissionable independent penetration-test scope and release gate
  (`docs/security/pen-test-scope.md`), linked from the MASVS assessment and
  leak-test matrix (#21).
- Corrected stale split-tunneling/captive-portal rows in
  `docs/security-readiness.md` (both shipped in v1.4.0; device verification
  still pending).

## [1.5.0] - 2026-11-11

### Build and toolchain

- **Coordinated Android toolchain upgrade.** AGP 8.7.3 → 9.5.1, Gradle wrapper
  8.11.1 → 9.9.1, Kotlin 2.0.21 → 2.4.21, compileSdk 35 → 37 (`targetSdk` and
  `minSdk` are unchanged at 35 / 26). AGP 9 cannot run on Gradle 8 and requires
  Kotlin ≥ 2.2.10, so the upgrades only work as one unit — that is why the four
  Dependabot PRs could not be merged individually, and why CI had to be fixed
  first.
- **AGP 9 built-in Kotlin.** `org.jetbrains.kotlin.android` is no longer applied
  (it is incompatible with AGP 9); the Compose compiler plugin remains and
  selects Kotlin 2.4.21 for the built-in Kotlin compiler.
- **Gradle 9.9.1, not 9.9.0.** 9.9.1 contains the fix for the critical issue
  where version-catalog values could run as code in generated accessors
  (introduced in Gradle 9.3); this project uses a version catalog.
- **CI now runs the project wrapper.** Both workflows use `./gradlew` with
  `setup-gradle gradle-version: wrapper` and fail if the wrapper distribution
  and `./gradlew --version` disagree, so wrapper bumps are really tested.
  Previously CI ran a preinstalled Gradle, which is why the Gradle 9 bump
  looked green while a local `./gradlew` build would have broken.
- **CI SDK/runner fixes.** Installs `platforms;android-37.0` plus Build Tools
  36.0.0 and 37.0.0; drops `:app:testReleaseUnitTest` (AGP 9 enables the
  unit-test component only for the tested build type by default); sets
  `JAVA_OPTS=-Djava.net.preferIPv4Stack=true` so the wrapper can bootstrap on
  runners without an IPv6 route.

### Dependencies

- Android Gradle Plugin 9.5.1, Gradle wrapper 9.9.1, Kotlin 2.4.21.
- Compose BOM 2026.09.00 (Compose UI 1.12.1, Material3 1.4.0).
- androidx.core 1.19.1 (the `core-ktx` artifact is an empty compatibility shim
  since core 1.19.0), kotlinx-coroutines 1.11.0, androidx.fragment 1.9.1.
- Compose 1.12 adds the transitively packaged `libandroidx.graphics.path.so`,
  which passes the same 16 KB alignment gate as `libnativevpn.so`.
- `.github/release.yml` excludes `dependabot[bot]` from auto-generated release
  notes.

### Compatibility notes

- No app behavior, security policy or profile-format change. Certificate
  verification, DNS fail-closed, compression/cipher policy, split tunneling and
  the rest of the v1.4.0 security work are unchanged.
- The Compose/Material3 upgrade may change minor visuals; unit, lint, native,
  instrumentation-test-APK and R8 release gates all pass.
- Kotlin 2.4.21's official support matrix lists Gradle ≤ 9.7.0 / AGP ≤ 9.3.1;
  AGP 9.5.1 + Gradle 9.9.1 is outside that window but builds and passes every
  gate (recorded as a residual risk in `docs/testing.md`).

## [1.4.0] - 2026-11-11

### Security

- **Fail-closed tunnel DNS (#8).** New "Require tunnel DNS" setting, default
  **on**: if the server pushes no usable resolver (IPv4 or IPv6) and no
  validated fallback is configured, the connection is refused with an explicit
  error instead of silently falling back to the underlying network's DNS. An
  optional fallback DNS list (IPv4/IPv6 literals, validated and normalised) is
  installed through the tunnel when the server pushes none; diagnostics state
  when the fallback is used (ADR-008).
- **Strict server-trust policy (#11).** Profiles must contain a CA or a legacy
  `tls-remote` directive; profiles with no server-trust material are rejected
  at import with an actionable message and refused at connect time (including
  profiles stored by older app versions). A `peer-fingerprint` is recorded and
  reported, but is **not** accepted as an anchor: the pinned mbedTLS backend
  does not enforce pins, so fingerprint-only profiles are now rejected with an
  explicit "pin cannot be enforced" message (CA + pin profiles are allowed with
  an import warning). This is an intentional breaking change for
  fingerprint-only profiles. Re-importing a same-name profile with different CA
  material now produces an explicit CA-change warning. Revocation is documented
  as server/PKI-side; the client does not claim CRL/OCSP support (ADR-009).
- **Compression and cipher policy modes (#19).** New explicit settings:
  Compression = Compatibility (default, accepts server→client LZO-ASYM per
  ADR-005) or Strict (local compression directives stripped, every
  server-pushed `comp-lzo`/`compress` option refused through quoted
  `pull-filter` lines, with a generated Core header patch as defence in depth;
  a compression-requiring provider must use Compatibility);
  Cipher = Profile (default) or AEAD-only (connect-time quoted `pull-filter`
  accepts the AEAD ciphers and rejects a non-AEAD pushed cipher with a clear
  error; profiles with their own cipher filters are stripped, and an AEAD
  fallback cipher is added when the profile pins none so Core's BF-CBC default
  cannot be negotiated). The active policy is reported in diagnostics
  (ADR-010). Both modes were exercised end to end against a live OpenVPN 2.4
  server.
- **MTU control (#10).** New TUN MTU setting: Auto (server-pushed; default),
  1500, 1400, or 1280. A fixed value appends `pull-filter ignore tun-mtu` and
  `tun-mtu N` to the connect-time configuration so the server-pushed value
  cannot override it; the chosen MTU is shown in connection details. No
  automatic reconnect loop.
- **Always-on/lockdown readiness (#9).** Settings shows a best-effort
  readiness card based on hidden `Settings.Secure` keys
  (`always_on_vpn_app`/`always_on_vpn_lockdown`; UNKNOWN when unreadable), with
  explanations and a link to system VPN settings. The app never claims a
  guaranteed kill switch when lockdown is off.
- **Security documentation and assurance (#15, #21).** Complete privacy policy,
  Play `VpnService`/Data-safety/`QUERY_ALL_PACKAGES`/specialUse disclosure
  answers, release compliance checklist, leak-test matrix, and a MASVS v2
  gap assessment with an ASVS note for a future backend; the independent
  penetration-test requirement is recorded. These are documentation/process
  deliverables, not claims of device verification.

### Fixed

- **AEAD-only cipher policy could not connect.** The generated `pull-filter`
  lines had four tokens (`pull-filter accept cipher AES-256-GCM`) instead of
  the three-token quoting Core requires, so OpenVPN Core rejected the
  configuration as a malformed option before connecting. Lines are now
  `pull-filter accept "cipher AES-256-GCM"` / `pull-filter reject "cipher"`,
  profile cipher filters are removed so the policy cannot be overridden, and an
  AEAD fallback `cipher` is added when the profile has none. Verified live: an
  AEAD-only session negotiated AES-256-GCM against a real server.
- **Strict compression only covered the Core patch, not pushed options or
  profile-local directives.** Strict mode now strips local `compress`/`comp-lzo`
  and rejects every pushed `comp-lzo` (except `comp-lzo no`) and `compress`
  option, including stubs. Verified live: strict mode shows an explicit policy
  error against an LZO-pushing server, while compatibility still connects.
- **Peer-fingerprint-only profiles were accepted although the mbedTLS backend
  ignores pins.** They are now rejected with an explicit message (see the
  Security section); CA + pin profiles keep working with a warning.
- **DNS resolvers of an unrouted address family were accepted.** A pushed IPv6
  resolver on an IPv4-only tunnel, or an IPv4 resolver on an IPv6-only tunnel,
  is now dropped; if that leaves no usable resolver the connection fails closed
  instead of restoring the underlying family's DNS.
- **Split-tunnel allow-list failures could fail open.** If adding an allowed
  application to the TUN builder fails, the connection is now aborted (error
  state, no TUN) instead of silently letting that app bypass the tunnel.
  Excluded-app failures are logged as a count only (no package names).
- **Native session teardown race.** All short-lived JNI session calls
  (`resume`, `reconnect`, `disconnect`, stats/connection-info, challenge
  response, `isRunning`) and `close()` now share the same handle lock, so a
  late call cannot use a session handle after `close()` destroys it. `connect()`
  remains unlocked by design (it creates the session).
- **Portal-pause state could stick after an explicit disconnect.** An explicit
  Disconnect now clears `pausedForPortal`; only the portal-pause teardown keeps
  it. The UI no longer pre-marks the pause before the service confirms it.

### Reliability

- **Legacy `topology net30` servers are supported.** The TUN adapter previously
  rejected the net30 address passed by OpenVPN servers that keep the legacy
  default topology, so the connection failed with
  `tun_builder_add_address IPv4 failed (topology net30)`. The client now applies
  the /30 client address to the Android TUN (routes are installed as before);
  verified end to end against a local OpenVPN 2.4 server using net30. No change
  for `topology subnet`/full-tunnel behaviour.
- **Desired-state connection lifecycle (#14).** The profile the user asked to
  connect is persisted separately from the native session; Always-on/system
  restarts (`START_STICKY`) restore a **fresh** native session from the stored
  profile and credentials, never a stale pointer. Retries keep the PR #5
  budget (5 s base, 60 s cap, 6 attempts, then an explicit "traffic is no
  longer protected" error) and now use **bounded jitter**. Force-stop, reboot
  (no boot receiver; Always-on handles boot), pre-first-unlock, and another-VPN
  takeover behaviour are documented (ADR-013).
- **Captive-portal pause (#18).** Best-effort detection via
  `NET_CAPABILITY_CAPTIVE_PORTAL`/`NET_CAPABILITY_VALIDATED`, an explicit
  "Pause VPN to sign in" action, an honest paused state that says traffic is
  NOT protected, auto-resume once the network validates, and a manual sign-in
  URL (`http://connectivitycheck.gstatic.com/generate_204`) as the fallback.
  Detection never pauses the tunnel silently (ADR-012).

### Features

- **Per-app split tunneling (#17).** Settings mode Off (default) / Include
  selected / Exclude selected, with a per-app picker screen. Home and Settings
  show "N apps bypass the tunnel" warnings; bypassing apps are explicitly not
  protected. An include configuration with no usable app is refused
  (fail-closed), uninstalled packages are filtered safely, and changes apply on
  the next connection (the UI offers Reconnect). The app declares the
  Play-restricted `QUERY_ALL_PACKAGES` permission because Android 11+ package
  visibility otherwise prevents listing/applying arbitrary packages; the list
  is used locally only, never transmitted or logged (ADR-011).
- **Backend and iOS scope decisions (#16, #20).** ADR-014 with
  `docs/architecture/backend-control-plane.md` (control/data-plane split,
  account/MFA/token/device model, offline-root PKI, gateway/DNS/API hardening,
  monitoring/incident response, ownership/runbook placeholders) and ADR-015 with
  `docs/architecture/ios-plan.md` (NEPacketTunnelProvider, Keychain sharing,
  `includeAllNetworks`, on-demand rules, captive portals, App Store 5.4
  requirements, core pin process). Both explicitly out of scope/not
  implemented; no backend or iOS code was added.

### Changed — intentional behaviour changes

- **"Require tunnel DNS" defaults ON** (fail-closed): profiles whose server
  pushes no usable DNS can no longer connect silently; they fail with an
  explicit error unless the user configures a fallback or disables the setting
  (the latter is documented as a leak risk).
- **Strict trust rejects profiles without CA/peer-fingerprint/`tls-remote`.**
  This is a deliberate verification tightening; profiles imported by older
  versions are re-checked at connect. Re-export with the provider CA if a
  profile lacks trust material.
- **Compatibility compression remains the default** (LZO-ASYM), not silently
  hardened: strict compression and AEAD-only ciphers are explicit opt-ins for
  users who accept the provider-compatibility trade-off.
- **Split tunneling is off by default**; existing users keep full-tunnel
  routing. Enabling it is explicit and permanently warned.
- MTU Auto remains the default; a fixed MTU is an explicit diagnostic/workaround
  control. The Always-on readiness card is informational and does not make the
  app a kill switch.

### CI and supply chain (#12, #13)

- CI now fails the build if any packaged native library `.so` `LOAD` segment
  has `p_align < 0x4000` (16 KB) or if `zipalign -c -P 16 4` fails
  (`scripts/check-16kb-alignment.sh`, run against the built debug APK). Native
  hardening keeps `-fstack-protector-strong`, `-D_FORTIFY_SOURCE=2`,
  `-Wl,-z,relro`, `-Wl,-z,now` and `-Wl,-z,max-page-size=16384`.
- CycloneDX SBOM generation (`scripts/generate-sbom.sh`) for every build and
  release (app version, OpenVPN Core 3.9 `10c6a56`, mbedTLS/asio/fmt/jsoncpp/
  lz4 versions, NDK `27.2.12479018`, Gradle runtime classpath); uploaded as a CI
  artifact and attached to GitHub releases.
- New `.github/workflows/security.yml` (scheduled + push to `main` +
  `workflow_dispatch`, **not** a required PR check, `permissions: contents:
  read`) runs osv-scanner on the SBOM and gitleaks on the repository.
  `.github/dependabot.yml` tracks Gradle and GitHub Actions. `SECURITY.md` now
  defines patch deadlines (critical 7 / high 30 / medium 90 days), the advisory
  owner, monitoring cadence for OpenVPN Core/mbedTLS, and the emergency-release
  process; `docs/compliance/release-checklist.md` covers signing, no debug
  logging, privacy policy URL, policy/store disclosures, SBOM and advisories.

### Fixed

- PR #5 fixes (merged): dynamic tls-crypt rekey crash (#4), post-connect
  timeout/retry (#6), swapped traffic counters (#7), Always-on start handling,
  `remote-cert-tls server` enforcement, metered inheritance, 16 KB alignment,
  MTU/error diagnostics; plus the review-fix pass (bounded retries, rekey hint,
  Always-on lifecycle, inline `<peer-fingerprint>`). See the PR #5 entries
  below.

- Long-lived sessions no longer disconnect at the first rekey when the server
  offers dynamic tls-crypt on the mbedTLS build. OpenVPN Core 3.9 advertised
  `IV_PROTO_DYN_TLS_CRYPT` even though the pinned mbedTLS backend cannot export
  keying material; the build now gates the advertisement on
  `support_key_material_export()`. A server that pushes the flag anyway is
  rejected with a clear server-push error instead of crashing at the first
  rekey (ADR-006).
- A rekey/key-export failure or an unhandled Core exception now produces an
  actionable error message instead of the raw
  `client_connect_unhandled_exception` text. A rekey failure is identified from
  the engine's keying-material log line, so it gets the specific message rather
  than the generic "engine stopped" text; the engine detail remains in
  Diagnostics.
- A connection timeout during an automatic reconnect (for example after
  locking/unlocking the phone or a transient network transition) no longer ends
  an established session with a fatal error. Core pauses the session and the
  service retries with exponential backoff (5 s doubling to 60 s, capped at six
  attempts per connected session), then fails visibly instead of leaving the app
  in "Reconnecting" with unprotected traffic. While paused there is no tunnel,
  so traffic is not protected until a resume attempt succeeds. An initial
  connection timeout still surfaces as an error (ADR-007).
- The Home screen's Download and Upload counters were swapped because Core's
  TUN counters use the tunnel device's direction. Download now maps to
  `TUN_BYTES_OUT` and upload to `TUN_BYTES_IN`, with the transport counters kept
  in the socket's direction for the fallback.
- Android Always-on VPN / lockdown starts the service with
  `VpnService.SERVICE_INTERFACE`; the service now connects the persisted
  selected profile with stored credentials instead of ignoring the start. It
  uses the explicitly persisted profile id (no silent substitution), and the
  Always-on start returns `START_STICKY` so a killed process can be restarted.
- CA-based profiles that did not explicitly constrain server-certificate usage
  now get `remote-cert-tls server` at import and connect time, preventing a
  client certificate from being accepted as the server. Inline
  `<peer-fingerprint>` blocks are recognised as an explicit policy.
- The VPN network now inherits the underlying network's metered state on
  Android 10+, so data-saver/background restrictions are not bypassed.
- The native library is linked with 16 KB page-size alignment for Android 15+
  devices.
- `UDP send exception: send: Message too long` now produces a Diagnostics hint
  about lowering `tun-mtu`, logged once per connection instead of for every
  oversized packet; certificate-verification, proxy-credential and
  network-unavailable failures have actionable messages.

### Compatibility

- **Server-certificate usage enforcement.** The `remote-cert-tls server`
  directive added to CA-based profiles is a deliberate verification change, not
  a neutral option. A server certificate without the `serverAuth` extended key
  usage (or with incompatible key usage) now fails with `CERT_VERIFY_FAIL`
  instead of connecting on CA trust alone. Profiles imported by older app
  versions are also affected because the directive is applied at connect time.
  Re-import with an explicit server-certificate policy if a legacy server
  cannot present a serverAuth certificate.

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
