# Security and threat model

This app is a VPN client, not a security boundary by itself. Its job is to use
the official OpenVPN 3 Core implementation correctly, keep local secrets
private, and avoid overstating what Android and the server guarantee.

## What the app protects

- **Profiles at rest.** Unified profile content (including inline certificates,
  keys, and any profile-embedded secrets) is encrypted with AES-256-GCM using a
  non-exportable Android Keystore key, stored in
  `noBackupFilesDir/profiles`.
- **Stored credentials.** Optional username/password/private-key password are
  encrypted with a separate Keystore key in
  `noBackupFilesDir/credentials`. Files are private to the app UID.
- **Backups.** `android:allowBackup="false"` plus a `data_extraction_rules.xml`
  that excludes every domain from cloud backup and device transfer.
- **Logs.** Kotlin logs are sanitized and kept in memory only. Native log lines
  are redacted in C++ before crossing JNI. Passwords, private keys, complete
  profiles, auth payloads, and session tokens are never intentionally logged.
- **Transport sockets.** OpenVPN transport fds are passed to
  `VpnService.protect()` so they do not route into the VPN's own TUN.
- **Server certificate usage.** For CA-based profiles that do not already
  specify a server-certificate policy, the app appends `remote-cert-tls server`
  at import and connect. This prevents a client certificate signed by the same
  CA from being accepted as the server (see `ProfileImportPolicy`). A
  consequence is a deliberate compatibility break: a server certificate without
  the `serverAuth` extended key usage (or with incompatible key usage) now fails
  `CERT_VERIFY_FAIL` instead of being accepted on CA trust alone.
- **Paused reconnect window.** After a post-connect connection timeout Core
  pauses the session and closes the TUN; Android restores the underlying
  network, so traffic is not protected until a resume attempt succeeds. The
  service retries with exponential backoff and gives up with an explicit
  "traffic is no longer protected" error after a bounded number of attempts.
  Always-on lockdown keeps its system-level block during this window.
- **System-level kill switch support.** The service handles Android's
  Always-on/lockdown start action (`VpnService.SERVICE_INTERFACE`) and connects
  the persisted selected profile with stored credentials. Settings exposes a
  best-effort readiness card that reads the hidden
  `always_on_vpn_app`/`always_on_vpn_lockdown` settings where readable
  (UNKNOWN otherwise), explains the trade-offs, and links to system VPN
  settings. The app **does not** claim a guaranteed kill switch: when lockdown
  is off, Android restores the underlying network whenever the tunnel is down
  (including the pause/reconnect window).
- **Tunnel DNS fail-closed by default.** `Require tunnel DNS` defaults to ON: a
  connection that would have no usable pushed resolver (and no validated
  user-configured fallback) is refused instead of silently falling back to the
  underlying network's DNS (ADR-008). Resolvers whose address family is not
  routed through the tunnel are dropped too (an IPv6 resolver on an IPv4-only
  tunnel cannot be used without restoring the underlying IPv6 route), so
  "usable" means reachable through the tunnel.
- **Strict server-trust policy.** Profiles must carry a CA or the legacy
  `tls-remote` directive; profiles with no trust material are rejected at
  import and refused at connect. A `peer-fingerprint` is recorded and reported,
  but it is **not** an accepted anchor: the pinned mbedTLS backend does not
  enforce pins, so a fingerprint-only profile is rejected with an explicit
  "cannot be enforced" message (CA + pin is allowed with a warning). A same-name
  re-import with different CA material produces an explicit warning (ADR-009).
- **Explicit compression/cipher modes.** Compatibility compression
  (LZO-ASYM, ADR-005) and profile-driven ciphers remain the defaults; users can
  opt into Strict compression (profile compression directives are stripped and
  every server-pushed `comp-lzo`/`compress` option is refused — including
  stubs — so a compression-requiring provider must use Compatibility) and
  AEAD-only ciphers (connect-time `pull-filter` accepts AES-GCM
  variants/ChaCha20-Poly1305 and rejects a non-AEAD pushed cipher)
  (ADR-010). The active policy is shown in diagnostics; hardening is never
  silent.
- **Split tunneling is explicit and warned.** Off by default; when enabled the
  Home/Settings screens state how many apps bypass the tunnel and their traffic
  is not protected (ADR-011).
- **Captive-portal pause is honest.** The portal flow never shows
  "Protected" while the tunnel is down; it says traffic is not protected until
  the tunnel auto-resumes (ADR-012).
- **Desired-state lifecycle.** The app persists the profile the user asked to
  connect, never a native pointer; `START_STICKY` restores a fresh session for
  Always-on starts, retries use bounded exponential backoff with bounded jitter,
  and force-stop/reboot/other-VPN behaviour is documented (ADR-013).
- **UI lock.** Optional fingerprint/strong-biometric gate implemented with
  `BiometricPrompt`. The gate protects the UI; it does not stop an active VPN.

The full item-by-item audit against the product-readiness review, including
known gaps and tracking issues, is in
[`docs/security-readiness.md`](security-readiness.md).

## What the app does not protect against

- A rooted device, compromised OS, malicious accessibility service, or attacker
  with the app process while unlocked.
- A malicious OpenVPN server or a profile that intentionally disables/weakens
  security. The app keeps certificate verification enabled and does not add
  "insecure" overrides, but server/profile policy still matters.
- A user who deliberately installs a CA, disables the system lock, or grants
  broad permissions to other apps.
- Full traffic-leak guarantees during every possible Android transition. The
  app provides deliberate routing defaults and logs warnings, but a guaranteed
  system kill switch is an Android Always-on VPN / Lockdown feature.
- Keystore extraction on a fully compromised OS. Android Keystore raises the bar
  and keeps keys non-exportable under normal operation; it is not an absolute
  guarantee.

## TLS and certificate validation

All OpenVPN TLS, certificate parsing/validation, cipher negotiation, and data
channel crypto are performed by OpenVPN 3 Core 3.9 with mbedTLS 3.6.2.

The app:

- never disables certificate verification;
- never sets an "accept all certificates" option;
- never sets `tls-cert-profile insecure`;
- never sets `enableLegacyAlgorithms` unless the profile itself demands it
  (currently the app does not expose that override);
- does not copy the CA out of the profile into logs or UI state;
- rejects profiles that explicitly request an external PKI certificate/key
  provider instead of silently bypassing them;
- appends `remote-cert-tls server` for CA-based profiles that do not already
  specify an explicit server-certificate policy (`remote-cert-*` or
  `tls-remote`), both at import and connect time; a server certificate without
  the serverAuth extended key usage now fails verification by design and is
  documented as a breaking compatibility change;
- enforces a strict trust policy: a profile is accepted only when it carries a
  CA or the legacy `tls-remote` directive. Profiles with no trust material are
  rejected at import with an actionable message and refused at connect time
  even if an older app version stored them; profiles whose only anchor is a
  `peer-fingerprint` are rejected with an explicit "pin cannot be enforced by
  this client" message (ADR-009);
- warns when a same-name profile is re-imported with different CA material, so
  a silent trust-anchor replacement is visible;
- for certificate-less username/password profiles only, adds Core's
  `setenv CLIENT_CERT 0` directive (no client certificate is sent); the server
  certificate, CA, `remote-cert-tls server`, and `verify-x509-name` checks are
  unaffected (ADR-004);
- lets core reject invalid server certificates and reports the engine error.

The app supports profiles that use standard CA-based (or legacy `tls-remote`)
validation according to what OpenVPN Core 3.9 supports. It does not add custom
pinning or custom crypto, and the pinned mbedTLS build does not enforce
`peer-fingerprint`, so pins are reported rather than trusted.

**Revocation is server/PKI-side.** The pinned client has no CRL/OCSP
integration. A certificate revoked by the server/PKI operator cannot complete
the server side of the handshake; the app does not claim to check revocation
locally, and there is no "revocation verified" indicator. See ADR-009.

Two compatibility accommodations are documented in ADR-005, and both are now
explicit policy choices (ADR-010):

- Server-pushed **asymmetric** (server-to-client) compression is accepted in
  the default `Compatibility` mode (`compressionMode = "asym"`), because some
  providers require it. Symmetric compression remains refused, and legacy
  `comp-list`/`comp-lzo` negotiation that would compress client-to-server data
  is not enabled. The `Strict` policy switches to `compressionMode = "no"` and
  a generated Core patch fails a server-pushed compressor loudly instead of
  silently degrading.
- At import, a `tls-cipher` pin whose suites are all legacy non-ECDHE (TLS 1.2)
  suites is dropped so the pinned core can negotiate TLS 1.3; defaults from the
  bundled mbedTLS are used instead. Certificate and server-name verification are
  unchanged. Data ciphers are profile-driven by default; the `AEAD-only` policy
  adds connect-time `pull-filter accept` entries for AES-GCM variants/ChaCha20-Poly1305
  plus `pull-filter reject cipher`, so a non-AEAD server push fails visibly.
  Neither mode weakens certificate verification. The active policies are shown
  in diagnostics; hardening is never silent.

## Secret handling

- Credentials entered in the UI are handed to the service through an in-process
  `ConcurrentHashMap` (`CredentialHandoff`), not Intent extras, notification
  text, logs, or command-line arguments. The entry is removed as soon as the
  service starts.
- Native JNI receives secret strings as JVM strings and copies them into the
  OpenVPN `Config`/`ProvideCreds` structures. They are not written to disk by
  native code.
- Static/dynamic challenge responses are never persisted.
- Profile content is decrypted only when needed by the service/importer and is
  not part of UI state; the UI receives `ProfileSummary` metadata only.
- Clipboard is used only when the user taps "Copy" on sanitized logs.
- No analytics, crash reporter, backend, or Firebase is present.

## Biometric app lock

The lock is implemented with `androidx.biometric` and
`BiometricManager.Authenticators.BIOMETRIC_STRONG`. It is a UI gate:

- On process start, the app shows the lock screen if the setting is enabled.
- After 20 seconds in the background, the UI re-locks.
- `AppLockState` survives Activity recreation but not process death.
- Cancelling or failing authentication does not unlock the app.
- If biometrics become unavailable, the lock screen explains the problem and
  links to system security settings; it does not silently bypass the lock.
- The VPN foreground service and notification Continue to work while locked.
  A notification Disconnect action is intentionally available without
  unlocking the UI.

The lock is not bound to the Keystore keys with `setUserAuthenticationRequired`.
Profiles are already encrypted at rest; the lock protects casual access to the
unlocked app UI. Do not describe it as full-disk or hardware-backed user
authentication for the VPN tunnel.

## Routing, DNS, IPv4, and IPv6

- Addresses, routes, DNS servers, search domains, MTU, and proxy settings come
  from OpenVPN Core's TunBuilder callbacks. No production values are hardcoded.
- Android's `VpnService` blocks an address family by default when the VPN has
  no address, route, or DNS server of that family. The app keeps this baseline
  and sets `blockUnusedFamilies` accordingly.
- Turning off "Block untunneled address families" calls `allowFamily()` for a
  family with no tunnel address, which can intentionally let that family fall
  back to the underlying network. The app logs a warning when this happens.
- `allowFamily()` cannot **block** a family that has an address. If a profile
  requests `block-ipv6`/`block-ipv4` while that family has an address, the app
  logs that Android cannot enforce it safely rather than claiming success.
- **Tunnel DNS fails closed by default.** With "Require tunnel DNS" on
  (default), the service evaluates the DNS the server pushed for both address
  families at TUN establishment. If there is no usable resolver for a family
  and no validated user fallback, the connection is refused with an explicit
  error instead of letting Android fall back to the underlying resolver. The
  optional `fallbackDnsServers` setting accepts only validated IPv4/IPv6
  literals and is installed through the tunnel; diagnostics state when the
  fallback is used. Turning the setting off restores the old behaviour and is
  explicitly a leak risk (ADR-008). The app never silently injects a public DNS
  resolver and never enables Core's `googleDnsFallback`.
- IPv6 support depends on the profile, server, and Android version. IPv6 route
  emulation is enabled in OpenVPN Core for older Android versions.
- DNS behavior is not a complete leak test. Use a controlled server and
  external leak tests on a physical device; see
  `docs/security/leak-test-matrix.md`.

### Split tunneling and package visibility

Split tunneling is off by default. When enabled (`Include selected` or
`Exclude selected`), the per-app lists are applied when the TUN is created and
the UI warns how many apps bypass the tunnel; bypassing apps are **not**
protected. The connect-time plan is validated fail-closed (an include list with
no usable app is refused), stale/uninstalled packages are filtered, and the
service logs the mode and effective app count plus the explicit leak warning.

Android 11+ package-visibility rules require the Play-restricted
`QUERY_ALL_PACKAGES` permission for the app to enumerate installed apps and
pass arbitrary package names to `addAllowedApplication`/
`addDisallowedApplication`; without it split tunneling silently degrades.
The permission is used only for the local picker and connect-time validation:
the package list is not transmitted, logged, or used for analytics/advertising,
and the app does not access other apps' data. Play declaration wording and Data
safety consistency are documented in
`docs/legal/play-store-vpn-disclosure.md` and the privacy policy.

## Kill switch

There is no application-level guaranteed kill switch. Android's system
Always-on VPN and Lockdown features are the supported mechanism. Settings shows
a best-effort readiness card that detects whether this app is the Always-on VPN
app and whether lockdown is enabled (hidden `Settings.Secure` keys
`always_on_vpn_app`/`always_on_vpn_lockdown`; UNKNOWN when the platform does not
expose them), explains the trade-offs, and links to system VPN settings. The app
never labels a session "protected" merely because the service runs, and never
claims a guaranteed kill switch when lockdown is off. Closing the app, killing
the process, a force-stop, or a network transition are not claimed to block all
traffic.

## Captive portals

When Android reports a captive-portal/unvalidated network, the app can offer an
explicit "Pause VPN to sign in" action. Pausing deliberately tears the tunnel
down so the portal can complete on the underlying network; the UI then says
traffic is **not** protected and never shows Protected/Connected while paused.
Once the network validates, the app reconnects the same profile with a fresh
session. Portal detection is best-effort and OEM-dependent; the manual sign-in
URL `http://connectivitycheck.gstatic.com/generate_204` is offered as a
fallback. See ADR-012.

## MTU and path-MTU black holes

The TUN MTU comes from the profile/server (`Auto`) unless the user picks a fixed
value (1500/1400/1280). A fixed value is applied at connect time by appending
`pull-filter ignore tun-mtu` and `tun-mtu N` to the configuration so a
server-pushed value cannot override it, and the chosen MTU is shown in
connection details. This is a diagnostics/workaround control, not automatic
PMTU discovery; a fixed MTU that is larger than the real path MTU can still
black-hole large packets. The app logs one `Message too long`/EMSGSIZE hint per
connection and does not auto-reconnect on MTU errors.

## Lifecycle, process death, and reconnect exposure

The app persists the profile the user asked to connect
(`desiredConnectedProfileId`) separately from the native session. An Always-on
system start / handled null-intent redelivery returns `START_STICKY` and
restores a **fresh** native session from the encrypted profile and stored
credentials; a stale fd or native pointer is never restored. A bounded
exponential backoff with bounded jitter (5 s base, 60 s cap, 6 attempts) is used
for post-connect pauses, then an explicit "traffic is no longer protected"
error (ADR-007/ADR-013). There is no boot receiver: boot recovery depends on
Android Always-on, and before the first unlock the app cannot decrypt its
Keystore material. Force-stop disables background starts; another VPN app taking
over stops this app's service. While reconnecting or paused there is no tunnel
and only Android lockdown protects traffic.

## Supply chain and release gates

- The OpenVPN Core submodule is pinned and unmodified; compatibility changes
  are generated-header patches with fail-fast checks (ADR-006/ADR-010).
- CI checks every packaged `.so` `LOAD` segment `p_align >= 0x4000` and
  `zipalign -c -P 16 4` on the built APK; native hardening flags include
  `-fstack-protector-strong`, `-D_FORTIFY_SOURCE=2`, `-Wl,-z,relro`,
  `-Wl,-z,now` and `-Wl,-z,max-page-size=16384`.
- A CycloneDX SBOM (`scripts/generate-sbom.sh`) is generated in CI, attached to
  GitHub releases, and scanned by the scheduled `security.yml` workflow
  (osv-scanner + gitleaks). Dependabot tracks Gradle and GitHub Actions.
- `SECURITY.md` defines disclosure, patch deadlines (critical 7 / high 30 /
  medium 90 days), the advisory-monitoring owner, and the emergency-release
  process. License deltas are reviewed when the core changes.

## Public IP check

The "Public IP" card makes an HTTPS request to `https://api.ipify.org` using the
active network. When the VPN is connected, Android routes that request through
the VPN. The app validates the response shape and displays the address. It is
one signal, not a complete leak test, and the app labels it that way.

The service is third-party and sees the request/IP. No credentials or profile
data are sent. Users can disable automatic checks in Settings.

## Log redaction

`LogSanitizer.kt` and `native_bridge.cpp` redact lines containing markers such
as password, passphrase, private key, certificate begin markers, auth tokens,
and session tokens. The native bridge also truncates oversized errors and never
passes the profile content into a log callback. This is defense in depth, not a
guarantee that arbitrary third-party log text is safe; logs are kept in memory
and can be cleared.

## Reporting

See [SECURITY.md](../SECURITY.md). Do not include real credentials, private
keys, or full profiles in a report.
