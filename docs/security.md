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
- **UI lock.** Optional fingerprint/strong-biometric gate implemented with
  `BiometricPrompt`. The gate protects the UI; it does not stop an active VPN.

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
- for certificate-less username/password profiles only, adds Core's
  `setenv CLIENT_CERT 0` directive (no client certificate is sent); the server
  certificate, CA, `remote-cert-tls server`, and `verify-x509-name` checks are
  unaffected (ADR-004);
- lets core reject invalid server certificates and reports the engine error.

The app supports profiles that use standard CA/`peer-fingerprint` validation
according to what OpenVPN Core 3.9 supports. It does not add custom pinning or
custom crypto.

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
- If the server pushes no DNS servers, Android may use the underlying network's
  DNS. The app logs this condition. It does not silently inject a public DNS
  resolver.
- IPv6 support depends on the profile, server, and Android version. IPv6 route
  emulation is enabled in OpenVPN Core for older Android versions.
- DNS behavior is not a complete leak test. Use a controlled server and
  external leak tests on a physical device.

## Kill switch

There is no application-level guaranteed kill switch. Android's system
Always-on VPN and Lockdown features are the supported mechanism. The app links
to system VPN settings and documents the distinction. It does not claim that
closing the app, killing the process, or a network transition always blocks all
traffic.

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
