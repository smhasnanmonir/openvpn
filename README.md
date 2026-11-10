# OpenVPN Client for Android

[![Android CI](https://github.com/smhasnanmonir/openvpn/actions/workflows/android.yml/badge.svg)](https://github.com/smhasnanmonir/openvpn/actions/workflows/android.yml)
[![Latest release](https://img.shields.io/github/v/release/smhasnanmonir/openvpn)](https://github.com/smhasnanmonir/openvpn/releases/latest)

A production-minded Android OpenVPN client built with Kotlin, Jetpack Compose,
Android `VpnService`, and the official **OpenVPN 3 Core** engine.

The app imports `.ovpn` profiles, stores profile material encrypted at rest,
authenticates with the engine, creates a real Android TUN interface, protects
transport sockets with `VpnService.protect()`, and reports only engine-derived
connection state. It is not a mock UI and does not implement its own VPN
protocol or cryptography.

> **Verification status:** GitHub Actions passes on `main`; debug and signed
> release APKs build successfully; JVM tests, Android lint, R8, and the
> instrumentation-test APK build pass.
> A live tunnel has **not** been verified in this development environment
> because no physical Android device or controlled OpenVPN server was
> available. See [docs/testing.md](docs/testing.md) for the exact verification
> matrix and the remaining device tests. Do not treat a UI "Connected" state in
> an untested build as proof that traffic is tunneled.

## Features

- **Multiple profile import** through the Storage Access Framework, including
  selecting a `.ovpn` together with externally referenced certificate/key
  files.
- **Official OpenVPN 3 Core** parsing, TLS, certificate validation, credential
  handling, and packet framing. Pinned to release/3.9 (`10c6a56`).
- **System-wide VPN** using Android `VpnService` and a TUN interface owned by
  the engine after fd handoff.
- **Transport socket protection** through a native → Kotlin callback into
  `VpnService.protect()`.
- **Notification controls** with state, live connection chronometer, and
  Disconnect / Reconnect actions.
- **Quick Settings tile** for connect/disconnect control.
- **Encrypted storage** for profiles and credentials using AES-256-GCM keys in
  Android Keystore; storage is app-private and excluded from backups.
- **Fingerprint app lock** via `BiometricPrompt`.
- **Real traffic counters** from OpenVPN Core stats (TUN counters when
  available, otherwise transport byte counters) and an engine-derived
  connection timer.
- **Public IP check** through the active network (not presented as a complete
  leak test).
- **Profile management**: rename display name only, emoji avatar selection,
  delete profile + credentials, duplicate detection, search.
- **Sanitized diagnostics** with filtering, copy, and clear. Passwords, keys,
  profile contents, and auth payloads are never logged.
- **Validation / success / error / empty states** across import, connection,
  IP check, self-test, profiles, and logs.
- **Two ABIs** built from source: `arm64-v8a` and `x86_64`.

## Architecture at a glance

```
Compose UI
  └── AppViewModel / VpnController (StateFlow)
        ├── ProfileRepository ── Encrypted ProfileStore + CredentialStore
        │                         └── Android Keystore AES-256-GCM
        ├── ConfigImporter ──── SAF → private temp dir → OpenVPN Core merge/eval
        └── MyVpnService (foreground, VpnService)
              ├── Android TUN (VpnService.Builder.establish)
              ├── NativeSession → JNI → OpenVPN 3 Core OpenVPNClient
              │     ├── TunBuilderBase implementation
              │     ├── socket_protect() → VpnService.protect()
              │     ├── event/log callbacks → JVM
              │     └── provide_creds() / dynamic challenge
              └── Notification + Quick Settings tile
```

Only `MyVpnService` and native callbacks write VPN state. UI state is a
projection of those events; pressing Connect does not set `CONNECTED`.

More detail:
- [Architecture](docs/architecture.md)
- [OpenVPN Core integration](docs/openvpn-core-integration.md)
- [Security and threat model](docs/security.md)
- [Profile import](docs/profile-import.md)
- [Testing](docs/testing.md)
- [Release process](docs/release.md)

## Repository layout

```
app/src/main/java/com/smhasnan/openvpn/
  config/        profiles, encrypted storage, SAF import
  crypto/        (security/) Keystore-backed secret box
  nativevpn/     Kotlin JNI facade + session ownership
  net/           public IP lookup
  settings/      preferences and theme/lock/network policy
  ui/            Compose screens, theme, dialogs
  vpn/           VpnService, controller, state model, notification, QS tile
app/src/main/cpp/
  native_bridge.cpp          JNI, session lifetime, callback marshaling
  engine_adapter/            OpenVPNClient + TunBuilderBase adapter
  CMakeLists.txt             native build entry point
  deps/                      generated per-ABI dependency installs (ignored)
third_party/openvpn3/        pinned git submodule (OpenVPN Core 3.9)
scripts/                     reproducible dependency fetch/build scripts
docs/                        architecture, security, integration, testing
.github/workflows/           CI and signed release workflow
```

## Install a release

Download the signed APK from the
[latest GitHub Release](https://github.com/smhasnanmonir/openvpn/releases/latest),
then:

```bash
adb install -r app-release.apk
```

The app requires Android 8.0+ (API 26). Verify the release signature against the
certificate digest documented in [docs/testing.md](docs/testing.md).

## Build

### Requirements

- JDK 17
- Android SDK platform 35 and Build Tools 35.0.0
- Android NDK `27.2.12479018`
- CMake `3.22.1` (from Android SDK)
- Ninja
- Network access for the first dependency fetch

Pinned native dependencies are downloaded by
`scripts/fetch-native-deps.sh` and verified with SHA-256. OpenVPN Core is a git
submodule pinned to an immutable commit.

### Commands

```bash
git clone --recurse-submodules https://github.com/smhasnanmonir/openvpn.git
cd openvpn

export ANDROID_SDK_ROOT=/path/to/android-sdk
./scripts/build-native-deps.sh arm64-v8a x86_64

./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

For a single ABI during development:

```bash
./gradlew -Pabis=arm64-v8a :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Signed release

Create `keystore.properties` (ignored by git), or provide the equivalent
environment variables:

```properties
storeFile=/absolute/path/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Then:

```bash
./gradlew :app:assembleRelease :app:lintRelease :app:testReleaseUnitTest
```

See [docs/release.md](docs/release.md) for CI signing secrets and GitHub
Release publishing.

## Android compatibility and policy notes

- `minSdk 26`, `targetSdk 35`, `compileSdk 35`.
- The foreground service declares
  `android:foregroundServiceType="specialUse"` with the required
  `FOREGROUND_SERVICE_SPECIAL_USE` permission and
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE="vpn"`. It does **not** claim
  `systemExempted`.
- VPN consent is obtained through `VpnService.prepare()`; the app never
  auto-consents.
- The service is `START_NOT_STICKY` and always creates a fresh TUN/native
  session. No stale fd or pointer is restored after process death.
- Android blocks an address family by default when the VPN has no address,
  route, or DNS server for it. The app keeps that default leak-prevention
  policy. The "Block untunneled address families" setting is on by default;
  disabling it intentionally allows fallback. See [security](docs/security.md).
- A kill switch is **not** implemented as an app-controlled promise. Use
  Android's system Always-on VPN / Lockdown if system-level lockdown is
  required.

## License

This application is licensed under **AGPL-3.0-only**, matching the license of
the pinned OpenVPN 3 Core 3.9 revision. See [LICENSE](LICENSE),
[NOTICE](NOTICE), and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

The OpenVPN 3 Core 3.9 revision is AGPL-3.0-only with an OpenSSL linking
exception. Later upstream revisions are dual MPL-2.0/AGPL-3.0; that later
license does not apply to the pinned revision.

## Security

Never paste real credentials or private keys into bug reports. See
[SECURITY.md](SECURITY.md) for reporting and [docs/security.md](docs/security.md)
for the threat model and known limitations.
