# Code security audit — 2026-11-12

Scope: app code only (`app/src/main`), plus manifest and Gradle config, read directly on
this checkout. Load assumed: single user on one phone, untrusted `.ovpn` files from
arbitrary providers, an honest but occasionally hostile network.

The codebase is genuinely hardened: fail-closed trust and DNS policies, remote-cert-tls
enforcement, AES-256-GCM Keystore storage, sanitized logging both sides of JNI, path-safe
SAF imports, `allowBackup=false`, immutable PendingIntents, private notification
visibility, full data-extraction exclusions. Findings below are the exceptions.

---

## Must fix

### 1. The app UI is screenshot- and Recents-visible; AGENTS.md believes FLAG_SECURE exists

- **File:** `app/src/main/java/com/smhasnan/openvpn/MainActivity.kt` (`onCreate`, lines 40–70).
  Also `AGENTS.md` §9 ("keep `FLAG_SECURE`/biometric UI gate untouched") — the invariant
  it claims to protect does not exist anywhere: a grep for `FLAG_SECURE`/`WindowManager`
  across all Kotlin files returns nothing.
- **What this is:** The biometric lock is a UI gate only. Before it renders — and every
  time the app is backgrounded while unlocked — Android snapshots the window into the
  Recents overview.
- **Problem:** The Home screen shows the VPN server host, public egress IP, and protocol;
  the Logs/Diagnostics screen shows server addresses and connection details. Those appear
  in the Recents thumbnail on the lock screen of any device, and any app-triggered or
  manual screenshot captures them. For a privacy product this is a real exposure the
  biometric lock does not cover.
- **Fix:** Two lines in `MainActivity.onCreate` (before `setContent`):
  ```kotlin
  if (container.settings.settingsNow().biometricLock) {
      window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
  }
  ```
  (or unconditionally, which is what most VPN clients do). Then fix the AGENTS.md §9
  sentence so the documented invariant matches reality.
- **If we skip it:** The "app lock" setting promises privacy that the Recents screen
  quietly defeats.

## Should fix

### 2. Log redaction misses `auth-user-pass`, and the marker list exists twice

- **Files:** `app/src/main/java/com/smhasnan/openvpn/util/LogSanitizer.kt` (lines 8–18)
  and `app/src/main/cpp/native_bridge.cpp` (`sanitize_log`, lines 54–76). Both hold the
  same nine-marker list ("password", "passphrase", "private key", …).
- **Problem:** "auth-user-pass" is not a marker. Providers sometimes inline credentials
  as `auth-user-pass user secret123`. When Core's merge/eval fails and quotes profile
  text (that is exactly the path `ConfigImporter.kt:91-94` surfaces into the diagnostics
  buffer via `LogSanitizer.sanitize`), a line containing the literal secret passes the
  marker check — it contains "pass" but not "password". The same gap exists on both the
  Kotlin and C++ sides, and because the list is duplicated, the two can silently drift
  apart in the future.
- **Fix:** Add `"auth-user-pass"`, `"http-proxy-user-pass"`, and `"proxy"` to both lists.
  Add one JVM unit test pinning the Kotlin markers (`LogSanitizerTest`: a line with
  `auth-user-pass u p` must become `[redacted: sensitive log line]`). Add a
  cross-reference comment in each list naming the other file so the next change updates
  both.
- **If we skip it:** A malformed provider profile can put its inline username/password
  into the in-app diagnostics log, and the copy-to-clipboard "Sanitized VPN diagnostics"
  feature then copies it out.

### 3. The challenge response travels in an Intent extra, contradicting the credential design

- **File:** `app/src/main/java/com/smhasnan/openvpn/vpn/MyVpnService.kt`
  (`respondToChallenge` helper, lines 96–102; handler line 212–216) versus
  `vpn/CredentialHandoff.kt` (lines 6–9: "Credentials are never put in Intent extras").
- **Problem:** Static challenge responses are frequently one-time codes / 2FA values.
  This one is passed as `EXTRA_CHALLENGE_RESPONSE` through `startService`, breaking the
  app's own stated rule and creating a second path a future refactor could turn into a
  leak (e.g. someone re-exporting the action or logging the intent). Exploitability today
  is low: the service is `exported="false"` and same-process.
- **Fix:** Reuse `CredentialHandoff`: add a `takeResponse(profileId)` alongside
  `take(profileId)`, put the response there from the UI, and drop the intent extra. ~10
  lines, deletes an inconsistency instead of adding a mechanism.
- **If we skip it:** The stated invariant is false in one place; the next person copying
  this pattern for a password repeats the mistake.

### 4. Split-tunnel app list and fallback DNS sit in plaintext SharedPreferences

- **File:** `app/src/main/java/com/smhasnan/openvpn/settings/SettingsRepository.kt`
  (lines 106–134; `splitTunnelPackages`, `fallbackDnsServers`).
- **Problem:** Which apps a user tunnels around the VPN, and which resolvers they use,
  are privacy-sensitive metadata. The repo's own rule (`AGENTS.md` §10.5) says the
  package list "must stay local-only" — it is local, but it is plaintext at rest in
  `shared_prefs/settings.xml`, unlike profiles and credentials which are AES-GCM
  encrypted in `noBackupFilesDir`. `allowBackup=false` and the data-extraction rules
  keep it out of backups, so the exposure needs a rooted/intrusive-access device — but
  the threat model in `KeystoreSecretBox.kt` (lines 15–18) explicitly names "an offline
  filesystem copy" as in scope, and this data is not covered by that protection.
- **Fix:** Smallest honest option: keep plaintext and document the exception in
  `docs/security.md` next to the threat model. Better: encrypt the two sensitive values
  through the existing `SecretBox` (it is already an interface built for this;
  `AppContainer` already owns an instance). Do not invent a new crypto path.
- **If we skip it:** The package list is the single class of user data that the
  Keystore story does not cover.

## Nice to have

### 5. Hardcoded stale User-Agent on the third-party IP check

- **File:** `app/src/main/java/com/smhasnan/openvpn/net/PublicIpRepository.kt` (line 24).
- **Problem:** `User-Agent: OpenVPNClient-Android/1.0` is a stale, app-version-identifying
  string sent to `api.ipify.org` on every automatic post-connect check. It is not a
  vulnerability; it is needless fingerprinting to a third party from a privacy app.
- **Fix:** Either send a neutral UA (`User-Agent: Mozilla/5.0 (Android)`) or use
  `BuildConfig.VERSION_NAME`. One line.

---

## Verified clean (read, no finding)

These were read end-to-end and are correct; listed so the next audit does not redo them:

- `AndroidManifest.xml`: `allowBackup=false`, `usesCleartextTraffic=false`, service
  `exported=false` + `BIND_VPN_SERVICE`, tile bound by system permission,
  `QUERY_ALL_PACKAGES` justified in-line, no debuggable release.
- `res/xml/data_extraction_rules.xml`: cloud-backup and device-transfer exclude every
  domain.
- `security/KeystoreSecretBox.kt`: AES-256-GCM, non-exportable Keystore key, randomized
  IV, versioned payload, strict parse checks on decrypt.
- `config/ProfileStore.kt` / `CredentialStore`: temp-file + `fd.sync()` + atomic rename;
  single-profile decrypt path; id match enforced on load.
- `config/ConfigImporter.kt` + `ImportFilePolicy.kt`: 4 MB bounded reads, path-separator
  and control-char rejection, temp dir deleted in `finally`, error text sanitized before
  display.
- `vpn/MyVpnService.establishTun`: DNS fail-closed (`DnsLeakPolicy`), split-tunnel
  fail-closed refusal in INCLUDE mode, resolver addresses and package names never logged.
- `vpn/VpnController.kt`: every log line crosses `LogSanitizer`; error text sanitized
  before it reaches the snapshot.
- `native_bridge.cpp`: `sanitize_log` applied before JNI for engine logs; `to_std`
  credential strings are copied per connect (see note below).
- `ui/LogsScreen.kt`: "Sanitized VPN diagnostics" clipboard export only, no share intent.
- `VpnNotification.kt`: `VISIBILITY_PRIVATE`; no credentials or addresses in notifications.
- `net/PublicIpRepository.kt`: HTTPS only, no redirects, bounded response, strict IP
  character validation; auto-check fires only on the CONNECTED transition and therefore
  through the tunnel.

## Accepted risks (documented, do not "fix" silently)

- **Biometric lock is UI-only by design** (`MainActivity` class comment): notifications
  and the Quick Settings tile keep controlling an active tunnel while locked. Binding
  profile decryption to `setUserAuthenticationRequired(true)` would break Always-on
  boot restore (Keystore is unavailable before first unlock — `AGENTS.md` §10.3), so
  this is a real product trade-off, not an oversight.
- **Credentials live in native `std::string` for the session lifetime**
  (`native_bridge.cpp` ~lines 448–460, 573–575): Core's API requires it; `std::string`
  cannot be reliably zeroed. Standard for OpenVPN 3 clients.
- **No CRL/OCSP, no enforced `peer-fingerprint`** (pinned mbedTLS ignores pins): already
  fail-closed at import/connect instead (`ProfileTrustPolicy`), documented in ADR-009.

## Verdict

One must-fix (FLAG_SECURE — tiny diff, fixes a documented-but-nonexistent invariant),
two small consistency fixes (#2, #3), one metadata-encryption decision (#4). Nothing
structural is wrong.

## Not checked

`third_party/openvpn3` (pinned submodule, out of audit scope per repo rules), the
mbedTLS/asio dependency trees (`app/src/main/cpp/deps/`), UI screens beyond the ones
grepped for exposure paths, CI workflow YAML, and no device/runtime testing was
performed.
