# Testing and verification

## Important limitation

This repository was developed in a Linux container without a physical Android
device or a controlled OpenVPN server. The app builds, unit tests pass, lint
passes, and the instrumentation-test APK compiles. **A real tunnel was not
exercised.** The checkboxes below distinguish verified build/test results from
device tests that still must be performed before claiming production readiness.

Never use a UI "Connected" state as proof that traffic is tunneled. Verify
actual routing on a device with a controlled server.

## Environment used

| Item | Value |
|---|---|
| Host | Linux x86_64 |
| JDK | OpenJDK 17.0.21 |
| Gradle | 8.11.1 |
| Android Gradle Plugin | 8.7.3 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |
| NDK | 27.2.12479018 |
| CMake | 3.22.1 |
| OpenVPN Core | release/3.9 `10c6a56` |
| ABIs built | arm64-v8a, x86_64 |
| Signing | RSA-4096 release key (not committed) |
| CI | main run `42736092481` (commit `96cf898`) and tag run `42736907921` (`v1.0.0`), **success** |

## Commands run and results

### Native dependencies

```bash
export ANDROID_SDK_ROOT=/opt/android-sdk
./scripts/build-native-deps.sh arm64-v8a
./scripts/build-native-deps.sh x86_64
```

Result: **pass**, both dependency prefixes installed under
`app/src/main/cpp/deps/`. Static libraries: mbedTLS/mbedX509/mbedcrypto,
jsoncpp, lz4; patched asio and fmt headers. Dependencies were checksum-verified
from the pinned archives.

### Debug build

```bash
/opt/gradle/bin/gradle --no-daemon -Pabis=arm64-v8a :app:assembleDebug
/opt/gradle/bin/gradle --no-daemon :app:assembleDebug
```

Result: **pass** for arm64-v8a and for both arm64-v8a + x86_64.
Output: `app/build/outputs/apk/debug/app-debug.apk` (about 16 MB with both ABIs;
debug APK is not minified).

### Unit tests

```bash
/opt/gradle/bin/gradle --no-daemon -Pabis=arm64-v8a :app:testDebugUnitTest
```

Result: **pass** — 26 tests, 0 failures, 0 errors.

Coverage includes:

- encrypted profile save/load/delete and corrupt-blob isolation;
- credentials do not persist challenge responses;
- import file-name policy and path-traversal rejection;
- profile name validation;
- log redaction;
- engine event → UI state mapping;
- byte/duration formatting;
- emoji normalization.

### CI

GitHub Actions run `42736092481` (commit `96cf898`) completed successfully:
SDK/NDK provisioning, checksum-verified native dependency build, JVM tests,
lint, debug APK, and instrumentation-test APK. The tag release path additionally
builds and publishes the signed APK; see `docs/release.md`.

### Lint

```bash
/opt/gradle/bin/gradle --no-daemon -Pabis=arm64-v8a :app:lintDebug
/opt/gradle/bin/gradle --no-daemon :app:lintRelease
```

Result: **pass** (0 errors; dependency-version and cosmetic warnings remain).

### Signed release / R8

```bash
/opt/gradle/bin/gradle --no-daemon :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
```

Result: **pass**. `app/build/outputs/apk/release/app-release.apk` is minified,
resource-shrunk, and signed. APK signature verified with `apksigner` (v2 + v3).
JNI callback method names were checked in the R8 output with `dexdump` and
remain unobfuscated.

Local final artifact (commit `96cf898`, both ABIs):

```
size:    6.6 MB
sha256:  ef6c62b0fa9f35c27ff9d844e3dde5f7a6218ed3b1de3c1fb33896a694a4c5e0
```

A CI-built APK for a tag is rebuilt with the tag's `APP_VERSION_CODE` and a new
signing timestamp, so its byte hash can differ. Compare the signing certificate
digest and build metadata instead of expecting the same bytes.

Published `v1.0.0` GitHub Release asset (built by GitHub Actions, verified with
`apksigner` and `aapt2`):

```
name:         app-release.apk
versionName:  1.0.0
versionCode:  7
size:         6,869,197 bytes
sha256:       9fcf60d1cb599e606f6e8ee1f9d7dbb7492b080055b99aa3ac6eab392e650df1
cert sha256:  834d2a58261a6c4113a3170953c1eea4a30d65ebc5ba4386408f09039dd00c9c
```

### Instrumentation test APK

```bash
/opt/gradle/bin/gradle --no-daemon :app:assembleDebugAndroidTest
```

Result: **pass** (compiles and links the test APK). The tests have **not been
executed** because no device/emulator was attached.

Tests included:

- native engine loads, core version is 3.9, crypto self-test passes (mbedTLS is
  built with `MBEDTLS_SELF_TEST`);
- malformed profile is rejected by core;
- native session can be created and destroyed;
- Android Keystore AES-GCM encrypt/decrypt round trip.

## What is verified vs. not verified

| Check | Status |
|---|---|
| Debug APK builds | ✅ verified |
| Both ABI native libraries build | ✅ verified |
| Unit tests (26) | ✅ verified |
| Android lint | ✅ verified |
| Signed, minified release APK | ✅ verified (local test key) |
| R8 keeps JNI callbacks | ✅ verified by dexdump |
| Instrumentation tests compile | ✅ verified |
| Instrumentation tests execute on device | ❌ not run |
| App launches on device | ❌ not run |
| Real OpenVPN handshake | ❌ not run |
| Valid/invalid credentials against server | ❌ not run |
| Invalid server certificate rejection | ❌ not run |
| Packets traverse tunnel | ❌ not run |
| IPv4/IPv6/DNS leak behavior | ❌ not run |
| Wi-Fi ↔ mobile transition | ❌ not run |
| VPN revocation / process death | ❌ not run |
| Notification/QS controls on device | ❌ not run |
| Fingerprint lock on device | ❌ not run |

## Required device test plan

Record for every run: device model, Android build, app version/ABI, core
revision `10c6a56`, server software/version, server profile, and test date.

### Setup

1. Install a controlled OpenVPN 2.6+ server with easy-rsa, `tls-crypt` or
   `tls-auth`, `data-ciphers AES-256-GCM`, and no compression.
2. Use a client profile with inline CA/cert/key or a self-contained unified
   profile.
3. Install the APK:
   ```bash
   adb install -r app-release.apk
   ```
4. Watch sanitized diagnostics and `adb logcat` for crashes (never paste real
   credentials into logs).

### Functional matrix

- [ ] Import a valid self-contained profile: listed, selectable, survives app
      restart.
- [ ] Import a malformed profile: clear validation error, no partial profile.
- [ ] Import a profile referencing a missing `ca.crt`: clear error, no silent
      omission.
- [ ] Import `.ovpn` + `ca.crt` + `client.key` together: success.
- [ ] Rename profile: display name changes; connection still works.
- [ ] Change emoji: avatar changes and persists.
- [ ] Delete profile: profile and saved credentials gone.
- [ ] Cancel the system VPN consent dialog: state remains disconnected.
- [ ] Connect with valid credentials: engine emits CONNECTED only after tunnel
      setup.
- [ ] Connect with invalid credentials: AUTH_FAILED; no false connected state.
- [ ] Connect to a server with a bad/expired certificate: connection rejected.
- [ ] Repeated rapid Connect taps: only one session.
- [ ] Cancel during CONNECTING: native session and TUN close.
- [ ] Disconnect: foreground notification removed, TUN removed, socket closed.
- [ ] Reconnect from notification and QS tile.
- [ ] Revoke VPN permission in system settings: tunnel closes, error is shown.
- [ ] Force-stop the app: no stale notification/state; next launch is clean.
- [ ] Toggle Wi-Fi ↔ mobile data while connected: observe core reconnection;
      no stale fd.
- [ ] Kill/restart the app process while connected: no stale native pointer.

### Traffic and leak tests

With a controlled server and known destinations:

- [ ] IPv4 public egress IP changes to the server egress (use the in-app IP
      check and `ipleak.net` / `browserleaks.com/ip`).
- [ ] DNS queries resolve through the pushed DNS servers (controlled resolver
      logs), not the ISP resolver.
- [ ] IPv4-only profile on a dual-stack device: IPv6 traffic is blocked by
      Android's default family policy (and logged).
- [ ] IPv6-enabled profile: IPv6 egress follows the tunnel.
- [ ] Split-tunnel profile: only included routes use the VPN; excluded routes
      behave as configured.
- [ ] Turn off "Block untunneled address families" and confirm the documented
      fallback behavior (this can leak by design).
- [ ] Check `VpnService.protect()` by confirming the OpenVPN transport does not
      recurse into its own tunnel (server sees the handshake; local routes do
      not capture it).

### UI / accessibility

- [ ] Light and dark theme.
- [ ] Large font and display scaling; content remains readable and not clipped.
- [ ] TalkBack labels for connect/disconnect, profile actions, IP refresh.
- [ ] Empty states for no profiles, no logs, no search results, IP error.
- [ ] Success/error import report with multiple files.
- [ ] App lock: enable with fingerprint, background >20 s, unlock; cancel does
      not unlock; disabling requires authentication.
- [ ] Notification permission denied: app still starts FGS and Settings offers
      a shortcut to notification settings.

## Known untested risks

- Native Core / mbedTLS behavior on specific vendor Android builds.
- Vendor-specific foreground-service restrictions and battery optimizations.
- IPv6/DNS behavior under real carrier networks.
- App-lock lifecycle behavior on multi-window/foldable devices.
- Proxy profiles (HTTP proxy path only; HTTPS/PAC unsupported).
- Compression profiles (legacy compression may be rejected by design).
