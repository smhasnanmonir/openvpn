# Testing and verification

## Verification summary

A real tunnel **was** exercised in an Android 11 (API 30, x86_64) emulator
against a controlled OpenVPN 2.7 server on the host. The debug APK at the
v1.0.2 code level:

- launched the Compose UI;
- imported a self-contained test profile through the production import pipeline;
- loaded OpenVPN Core 3.9 and passed the native/Keystore instrumentation tests;
- negotiated TLS 1.3 with `AES-256-GCM` data channel;
- created an Android TUN with `10.8.0.2`, MTU 1500;
- passed ICMP packets to `10.8.0.1` through the tunnel (`3/3` received);
- displayed a real engine-derived CONNECTED state, timer, and traffic counters;
- removed the TUN route and returned to DISCONNECTED on disconnect.

Not yet tested: physical devices, IPv6 routing, DNS leak behavior, invalid
certificate rejection, TCP profiles, Wi-Fi/cellular transitions, VPN permission
revocation, process death, biometric lock on hardware, and notification action
taps. Do not generalize the emulator result into a full leak-protection claim.

> The published `v1.0.0` and `v1.0.1` artifacts are superseded: they contained
> an Activity initialization crash and (for v1.0.1) the TUN delegation bug was
> still being fixed. Use **v1.0.2 or newer**.

## Change-set verification: Home profiles, network card, power pass (PR #2)

Verification run for the `feat/home-network-profiles-performance` change set
(issue #1, PR #2) on a Windows host with JDK 17 and Gradle 8.11.1, using the
x86_64 native dependency build:

```bash
./gradlew -Pabis=x86_64 \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug \
  :app:assembleDebugAndroidTest
```

Result: **pass**.

- 85 unit tests, 0 failures across 13 suites. New coverage:
  `EmojiCatalogTest` (custom emoji grapheme/emoji-base validation),
  `StatsRefreshPolicyTest` (connected/screen-off polling cadence),
  `VpnControllerLogBufferTest` (coalesced bounded diagnostics, duplicate
  suppression, thread safety), `ProfileStoreLoadTest` (single-profile decrypt
  path).
- `lintDebug`: 0 errors.
- Debug APK and instrumentation-test APK built.
- The arm64-v8a native dependency build was not exercised on this host;
  `Android CI` builds both ABIs from the pinned sources for the branch/PR.

Manual checks that still require an emulator or device (not run for this change
set):

- [ ] Fail a public-IP check, then retry: the card shows the loading skeleton,
      never the previous address; a successful check shows the egress path and
      check time.
- [ ] Switch profiles from Home; switching is blocked while connected; the
      notification and Quick Settings tile use the newly selected profile.
- [ ] Assign a custom emoji icon (e.g. `👨‍👩‍👧‍👦`, `🇧🇩`, `1️⃣`), restart the
      app, and confirm it persists; confirm plain text is rejected.
- [ ] Connect, background the app and turn the screen off: stats polling drops
      to the 10 s cadence, and no notification/tile update happens for
      stats-only changes.
- [ ] Wi-Fi ↔ cellular transition logs one "Network changed" line, re-applies
      the underlying network, and refreshes connection details once.

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
| CI | main run `42736092481`; tag runs `42736907921` (`v1.0.0`), `42737674467` (`v1.0.1`), and `42745278800` (`v1.0.2`), **success** |

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

Result: **pass** — 26 tests at v1.0.2 (36 after the ADR-004 import fix), 0
failures, 0 errors.

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

Published `v1.0.2` GitHub Release asset (built by GitHub Actions, verified with
`apksigner` and `aapt2`, and exercised on the Android 11 emulator):

```
name:         app-release.apk
versionName:  1.0.2
versionCode:  14
size:         6,904,443 bytes
sha256:       38e599042e9b3277ba15c797f14d22c22cdbda6cd3e1c23b56736561700fc5e2
cert sha256:  834d2a58261a6c4113a3170953c1eea4a30d65ebc5ba4386408f09039dd00c9c
```

The signed release APK was installed on the emulator, launched, and ran the
mbedTLS crypto self-test successfully under R8. A CI-built APK for a tag uses
the tag's `APP_VERSION_CODE` and a new signing timestamp, so its byte hash can
differ from a local build. Compare the signing certificate digest and `aapt2`
badging.

`v1.0.0` and `v1.0.1` are marked superseded/pre-release: they contained an
Activity initialization crash; v1.0.1 also lacked the final TUN delegation fix.

### Instrumentation test APK

```bash
/opt/gradle/bin/gradle --no-daemon :app:assembleDebugAndroidTest
```

Result: **pass**. Five tests were executed at v1.0.2 (nine after the ADR-004
import fix) on an Android 11 (API 30) x86_64 emulator with `am instrument`; all
five passed:

- native engine loads, core version is 3.9, crypto self-test passes (mbedTLS is
  built with `MBEDTLS_SELF_TEST`);
- malformed profile is rejected by core;
- native session can be created and destroyed;
- Android Keystore AES-GCM encrypt/decrypt round trip;
- self-contained profile import through `ConfigImporter`/Core merge+eval,
  encrypted persistence, selection, rename, emoji update, and deletion.

## Username/password-only profile import fix (2026-11-10)

Context: importing provider profiles that sign in with a username/password and
have no client certificate/key failed with "External PKI profiles are not
supported" because OpenVPN Core 3.9 reports such profiles as `externalPki`. The
fix and its rationale are in ADR-004 and `docs/profile-import.md`.

Environment: Windows host, JDK 17.0.20, Gradle 8.11.1, NDK 27.2.12479018
(official r27c archive), CMake 3.22.1, ABI `x86_64`, Android 16 (API 36) x86_64
emulator. The pinned Core submodule was checked out at `10c6a56` and the native
dependencies were rebuilt for `x86_64`.

Commands and results:

- `.\gradlew.bat :app:testDebugUnitTest` → **pass**, 36 tests, 0 failures, 0
  errors (10 new `ProfileImportPolicyTest` tests).
- `.\gradlew.bat :app:compileDebugKotlin` → **pass**.
- `.\gradlew.bat :app:lintDebug` → **pass** (0 errors; pre-existing warnings
  only).
- `.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -Pabis=x86_64`
  → **pass** (`libnativevpn.so` for `x86_64`).
- `adb shell am instrument -w …AndroidJUnitRunner` → **OK (9 tests)**, including
  the new `UserPasswordProfileInstrumentedTest` (4 tests):
  - the real `NCVPN-SG-Singapore-UDP.ovpn` is evaluated by Core 3.9 with
    `error=false`, `externalPki=true`, `autologin=false`;
  - after `ProfileImportPolicy.withoutClientCertificate` the same profile
    evaluates with `error=false` and `externalPki=false`;
  - the production `ConfigImporter` pipeline (merge → eval → adapt → store)
    accepts the profile, stores exactly one `setenv CLIENT_CERT 0` directive and
    `clientCertNotRequired=true` metadata, and a re-import dedupes to the same
    profile id;
  - all 151 provider profiles in the reported set classify as certificate-less
    sign-in profiles (`auth-user-pass`, no `EXTERNAL_PKI` marker).
- Negative control: restoring the old unconditional `externalPki` rejection
  makes the on-device importer test fail; the JVM unit tests still pass, so that
  branch is guarded only by the instrumentation test.

Not verified in that run: a live TLS/auth handshake against the provider (no
credentials were available for that session). A later session verified provider
connectivity with real credentials once the provider-compatibility fixes in
ADR-005 were applied; see "Provider connection compatibility (2026-11-10)" below.

## Provider connection compatibility (2026-11-10)

Context: real provider profiles (an NCVPN `groupedServerList` export with the
user's real credentials) still did not connect after the import and UI fixes.
Two independent causes were found and fixed (ADR-005): legacy-only `tls-cipher`
pins that prevent TLS 1.3 negotiation, and OpenVPN Core's default compression
policy rejecting the provider's server-to-client LZO-ASYM push right after
`CONNECTED`. The failure-reporting defect (silent timeouts) was fixed as well.

Environment: Windows host, JDK 17.0.20, Gradle 8.11.1, NDK 27.2.12479018,
`x86_64`, Android 16 (API 36) x86_64 emulator, real provider credentials.

Results on the fixed debug build:

- **Import normalization:** importing the unmodified original profile logged
  `Profile import: removed a legacy-only tls-cipher pin so the server can
  negotiate TLS 1.3.` The stored profile was re-validated by Core.
- **TLS:** the same profile then negotiated `TLS1-3-AES-256-GCM-SHA384`,
  authenticated, and brought up the tunnel:
  `CONNECTED: …@fra-a42.vpn.wlvpn.com:1194 (69.16.145.14) via /UDP on
  tun/172.21.90.116/ gw=[172.21.90.1/] mtu=1500`.
- **Compression:** `LZO-ASYM … asym=1` from the server is now accepted; no
  `COMPRESS_ERROR` / transport error followed `CONNECTED`, and the Errors filter
  stayed empty.
- **Stability and traffic:** two independent nodes (US and DE) stayed
  `PROTECTED` beyond two minutes with the timer and byte counters increasing;
  `tun0` carried the pushed address, default route, and pushed DNS; a TCP
  connection through the tunnel succeeded; the in-app public-IP check reported
  the provider egress address (`69.16.145.16` vs local `103.175.51.184`).
  Disconnect removed `tun0`, stopped the service, and returned to `INACTIVE`.
- **Failure reporting:** before the logging fix a failing attempt ended as
  `INACTIVE` with empty diagnostics. Now fatal engine events produce an ERROR
  banner and ERROR log row (e.g. "Connection timed out before the TLS handshake
  completed…", "server pushed compression settings that are not allowed…"), and
  a following `DISCONNECTED` event no longer erases the error.
- **Upgrade path:** a profile imported by an earlier app version (stored with
  the legacy `tls-cipher` pin) was connected after the update without
  re-importing: the handshake completed and the (deliberately invalid) test
  credentials produced `AUTH_FAILED` ("Sign-in was rejected") instead of the old
  silent TLS timeout, confirming connect-time normalization.
- **Automated:** 52 JVM unit tests pass (new `VpnController` state-machine tests
  and `tls-cipher` policy tests); lint reports 0 errors.

Residual notes: the in-app public-IP check timed out once through one US node
(transient provider/DNS quirk; it worked on the DE node and tunnel traffic was
independently confirmed); provider byte counters were small (KB) but tunnel data
flow and egress-IP change were confirmed.

## What is verified vs. not verified

| Check | Status |
|---|---|
| Debug APK builds | ✅ verified |
| Both ABI native libraries build | ✅ verified |
| Unit tests (52) | ✅ verified |
| Android lint | ✅ verified |
| Signed, minified release APK | ✅ verified |
| R8 keeps JNI callbacks | ✅ verified by dexdump |
| Instrumentation tests compile | ✅ verified |
| Instrumentation tests execute (9) | ✅ verified on API 30 and API 36 x86_64 emulators |
| App launches on device | ✅ verified on API 30 x86_64 emulator |
| Profile import pipeline | ✅ verified on emulator |
| Real OpenVPN TLS handshake | ✅ verified against controlled OpenVPN 2.7 server |
| Provider connect with real credentials (TLS 1.3 + LZO-ASYM) | ✅ verified on API 36 x86_64 emulator (US + DE nodes, egress IP change) |
| TUN establishment (`10.8.0.2`, MTU 1500) | ✅ verified |
| Packets traverse tunnel (ICMP to `10.8.0.1`) | ✅ verified (3/3) |
| Disconnect cleanup (TUN route removed) | ✅ verified |
| Invalid credentials | ❌ not run |
| Invalid server certificate | ❌ not run |
| Physical device (arm64-v8a) | ❌ not run |
| IPv6 / DNS leak behavior | ❌ not run |
| Wi-Fi ↔ mobile transition | ❌ not run |
| VPN revocation / process death | ❌ not run |
| Notification action tap / QS tile | ❌ not run (notification actions rendered) |
| Fingerprint lock on real hardware | ❌ not run |
| TCP profile | ❌ not run |

## Emulator/server evidence

- Emulator: Android 11 (API 30), x86_64, Google APIs image.
- App: debug build, package `com.smhasnan.openvpn.debug`.
- Server: OpenVPN 2.7.0, UDP 1194, TLS 1.3, `AES-256-GCM`, `topology subnet`,
  server pushed `10.8.0.2`, MTU 1500.
- Client Core: 3.9, platform log `android x86_64 64-bit`.
- Server status showed `testclient` with virtual address `10.8.0.2` and byte
  counters; emulator `ip route` showed `10.8.0.0/24 dev tun0 src 10.8.0.2`.
- `adb shell ping -c 3 10.8.0.1` returned 3/3 replies.
- After disconnect, the `tun0` route disappeared.
- The Android DocumentsUI picker opened after the fragment fix. Automated
  selection through the system picker was not scripted; the production
  `ConfigImporter` pipeline was exercised by instrumentation with a private
  `file://` URI, covering merge, eval, encrypted persistence, and selection.
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
- Symmetric compression profiles remain unsupported by policy; asymmetric
  server-to-client LZO (as pushed by some providers) is accepted.
