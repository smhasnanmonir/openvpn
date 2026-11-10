# Release process

## Build outputs

| Build | Command | Output |
|---|---|---|
| Debug | `./gradlew :app:assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` |
| Release | `./gradlew :app:assembleRelease` | `app/build/outputs/apk/release/app-release.apk` |
| Instrumentation | `./gradlew :app:assembleDebugAndroidTest` | `app/build/outputs/apk/androidTest/debug/` |

Release builds enable R8 code/resource shrinking and keep the JNI callback
surface (see `app/proguard-rules.pro`). Both `arm64-v8a` and `x86_64` are built
by default. Use `-Pabis=arm64-v8a` for faster local iteration.

## Signing

Release signing is read from `keystore.properties` (git-ignored) or environment
variables. Never commit a keystore or passwords.

`keystore.properties`:

```properties
storeFile=/absolute/path/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Environment alternatives:

```bash
export RELEASE_KEYSTORE_PATH=/absolute/path/release.jks
export RELEASE_STORE_PASSWORD=...
export RELEASE_KEY_ALIAS=...
export RELEASE_KEY_PASSWORD=...
```

If no signing config is present, `assembleRelease` produces an unsigned APK.
The CI release job explicitly requires the secrets and fails rather than
publishing an unsigned artifact.

Generate a key once (offline, backed up securely):

```bash
keytool -genkeypair -v \
  -keystore release.jks \
  -alias openvpnclient \
  -keyalg RSA -keysize 4096 -validity 10000
```

Verify:

```bash
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose \
  app/build/outputs/apk/release/app-release.apk
```

## GitHub Actions

`.github/workflows/android.yml` runs on every push/PR:

1. checkout with the pinned OpenVPN Core submodule;
2. install JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1;
3. restore/build checksum-verified native dependencies;
4. run unit tests, lint, debug APK build, instrumentation-test APK build;
5. upload debug artifacts.

On a `v*` tag it additionally:

1. decodes the release keystore from secrets;
2. builds a minified signed release APK;
3. verifies the APK signature;
4. creates a GitHub Release and uploads the APK.

### Required repository secrets

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `RELEASE_STORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | key alias |
| `RELEASE_KEY_PASSWORD` | key password |

Set them with:

```bash
base64 -w0 release.jks | gh secret set RELEASE_KEYSTORE_BASE64
gh secret set RELEASE_STORE_PASSWORD
gh secret set RELEASE_KEY_ALIAS
gh secret set RELEASE_KEY_PASSWORD
```

### Release trigger

```bash
git tag -a v1.0.0 -m "OpenVPN Client 1.0.0"
git push origin v1.0.0
```

`APP_VERSION` derives from the tag (without `v`); `APP_VERSION_CODE` uses the
GitHub run number. For a reproducible local build set both explicitly:

```bash
APP_VERSION=1.0.0 APP_VERSION_CODE=1 ./gradlew :app:assembleRelease
```

## Reproducibility

Pinned inputs:

- OpenVPN Core submodule commit `10c6a56cb17fd015a2d24953c3c42145bbcdea58`;
- native dependencies by archive SHA-256 in `scripts/fetch-native-deps.sh`;
- asio OpenVPN patches from the core submodule;
- Gradle wrapper 8.11.1, AGP 8.7.3, Kotlin 2.0.21;
- `minSdk/targetSdk/compileSdk`, NDK, and CMake versions documented in
  `README.md`.

Caveats:

- Gradle/Maven artifacts are resolved from Google/Maven Central; use a
  dependency mirror or lockfile if byte-for-byte reproducibility is required.
- The build environment's JDK/NDK toolchain affects native binary bytes.
- GitHub `APP_VERSION_CODE` uses the workflow run number, so CI release version
  codes vary by run. Set it explicitly for store uploads.
- Signing timestamps/certificates are not reproducible; verify the same
  certificate digest rather than expecting identical APK bytes.
- A future full verification should add Gradle dependency verification
  metadata and a signed SBOM.

## Release checklist

- [ ] `./scripts/build-native-deps.sh arm64-v8a x86_64` passes.
- [ ] `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` passes.
- [ ] `./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease` passes.
- [ ] `apksigner verify` passes.
- [ ] JNI callback names still present in R8 output (`dexdump`).
- [ ] Device test matrix in `docs/testing.md` completed, with results recorded.
- [ ] License/notices reviewed for the pinned core revision (AGPL-3.0-only at 3.9).
- [ ] Corresponding source published (the GitHub repository + submodule).
- [ ] No keystore, passwords, real profiles, or logs committed.
- [ ] Version/tag and release notes updated.
- [ ] Play Console foreground-service `specialUse`/`vpn` declaration if
      distributing via Play (required for that FGS type).

## Installation

```bash
adb install -r app-release.apk
```

The app asks for VPN consent on the first connection. Notifications and the
Quick Settings tile are optional but required for the full control surface.
For system-level lockdown, configure Android's Always-on VPN/Lockdown; the app
does not implement a guaranteed kill switch.
