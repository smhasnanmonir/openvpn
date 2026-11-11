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

The v1.3.0 release certificate is the project's release identity; its SHA-256
digest is pinned in `android.yml` (`RELEASE_CERT_SHA256`) and the tag build
fails if a different key signed the APK:

```bash
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs \
  app/build/outputs/apk/release/app-release.apk | grep 'certificate SHA-256'
# expected: 834d2a58261a6c4113a3170953c1eea4a30d65ebc5ba4386408f09039dd00c9c
```

The same step also asserts the published artifact's identity with
`aapt2 dump badging`: `versionName` must equal the tag without its leading `v`,
`versionCode` must be greater than 45 (v1.3.0's version code), the APK must not
be debuggable, and `build/sbom/sbom.cdx.json` must exist. Rotate the pinned
digest only when the release key itself is rotated.

## GitHub Actions

`.github/workflows/android.yml` runs on every push/PR:

1. checkout with the pinned OpenVPN Core submodule;
2. install JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1;
3. restore/build checksum-verified native dependencies;
4. run unit tests, lint, debug APK build, instrumentation-test APK build through
   the **project Gradle wrapper** (`./gradlew`); CI fails if the wrapper
   distribution in `gradle/wrapper/gradle-wrapper.properties` is not the Gradle
   version that `./gradlew` reports, so a wrapper bump is really exercised
   (the workflow sets `JAVA_OPTS=-Djava.net.preferIPv4Stack=true` because
   hosted runners have no IPv6 route while `services.gradle.org` resolves to
   IPv6 first; the wrapper bootstraps with a plain HTTPS connection that does
   not fall back to IPv4);
5. verify 16 KB page-size compatibility on the debug APK
   (`bash scripts/check-16kb-alignment.sh`: every packaged `.so` `LOAD` segment
   `p_align >= 0x4000`, plus `zipalign -c -P 16 4`) and assert that the default
   build packaged both `lib/arm64-v8a/libnativevpn.so` and
   `lib/x86_64/libnativevpn.so`;
6. generate the CycloneDX SBOM (`bash scripts/generate-sbom.sh`) and upload it
   as an artifact;
7. upload debug artifacts.

On a `v*` tag it additionally:

1. decodes the release keystore from secrets;
2. builds a minified signed release APK (`:app:assembleRelease`,
   `:app:testReleaseUnitTest`, `:app:lintRelease`);
3. runs the same 16 KB alignment gate and default-ABI assertion on the
   **release** APK (`app/build/outputs/apk/release/app-release.apk`) — the
   released artifact is not allowed to rely on debug-only checks;
4. verifies the APK signature and asserts artifact identity: the signer
   certificate SHA-256 must be the v1.3.0 release key
   (`834d2a58261a6c4113a3170953c1eea4a30d65ebc5ba4386408f09039dd00c9c`),
   `aapt2 dump badging` must report `versionName` equal to the tag without its
   leading `v` and `versionCode` greater than 45 (v1.3.0), the APK must not be
   debuggable, and `build/sbom/sbom.cdx.json` must exist;
5. creates a GitHub Release and uploads the signed APK **and the SBOM**
   (`sbom.cdx.json`).

`.github/workflows/security.yml` is **not** a required PR check. It runs on
pushes to `main`, on a weekly schedule, and by manual dispatch, with
`permissions: contents: read`:

- generates the SBOM and scans it with `osv-scanner` for known advisories;
- runs `gitleaks` secret scanning over the repository history using the
  repository `.gitleaks.toml`, which keeps the default rules and allowlists only
  the documented throwaway instrumentation-test fixture
  (`app/src/androidTest/assets/test-profile.ovpn`).

`.github/dependabot.yml` opens weekly update PRs for Gradle dependencies and
GitHub Actions.

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
GitHub run number. The workflow validates the code before exporting it (a
non-numeric or non-positive value fails the build instead of silently
defaulting) and the release gate requires `versionCode > 45` (v1.3.0).
For a reproducible local build set both explicitly:

```bash
APP_VERSION=1.3.0 APP_VERSION_CODE=46 ./gradlew :app:assembleRelease
```

For a release, use a code greater than the last published `versionCode`.

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
- A CycloneDX SBOM is generated per build/release (`scripts/generate-sbom.sh`),
  but it is not digitally signed; artifact signing/provenance (for example
  Sigstore/cosign or GitHub artifact attestations) is a follow-up. Gradle
  dependency verification metadata is also still a follow-up.

## Release checklist

The full compliance checklist (signing, no debug logging, privacy policy URL,
Play declarations, SBOM, advisories) is
[`docs/compliance/release-checklist.md`](compliance/release-checklist.md). The
build-specific minimum is:

- [ ] `./scripts/build-native-deps.sh arm64-v8a x86_64` passes.
- [ ] `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` passes.
- [ ] 16 KB + ABI gates pass on the debug APK:
      `bash scripts/check-16kb-alignment.sh app/build/outputs/apk/debug/app-debug.apk`
      and `unzip -l app-debug.apk | grep -E 'lib/(arm64-v8a|x86_64)/libnativevpn\.so'`
      both present.
- [ ] `bash scripts/generate-sbom.sh` produces valid CycloneDX JSON and fails if
      the `third_party/openvpn3` submodule is missing or not at the pinned
      commit.
- [ ] `./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease` passes.
- [ ] Release APK gates pass: `bash scripts/check-16kb-alignment.sh
      app/build/outputs/apk/release/app-release.apk`; both default ABIs packaged
      (`unzip -l`); `apksigner verify --print-certs` reports certificate SHA-256
      `834d2a58261a6c4113a3170953c1eea4a30d65ebc5ba4386408f09039dd00c9c`;
      `aapt2 dump badging` reports the expected `versionName`, `versionCode > 45`,
      and no `application-debuggable`.
- [ ] SBOM exists (`build/sbom/sbom.cdx.json`) and is uploaded with the release.
- [ ] JNI callback names still present in R8 output (`dexdump`).
- [ ] Device test matrix in `docs/testing.md` completed, with results recorded.
- [ ] License/notices reviewed for the pinned core revision (AGPL-3.0-only at 3.9).
- [ ] Corresponding source published (the GitHub repository + submodule).
- [ ] No keystore, passwords, real profiles, or logs committed (secret scan).
- [ ] Version/tag and release notes updated (include intentional behaviour changes).
- [ ] Privacy policy published at a public URL; Play `VpnService`,
      `QUERY_ALL_PACKAGES`/specialUse declarations, and Data safety answers
      re-checked if distributing via Play.
- [ ] No untriaged critical/high advisory against the SBOM; patch SLA in
      `SECURITY.md` respected.

## Installation

```bash
adb install -r app-release.apk
```

The app asks for VPN consent on the first connection. Notifications and the
Quick Settings tile are optional but required for the full control surface.
For system-level lockdown, configure Android's Always-on VPN/Lockdown; the app
does not implement a guaranteed kill switch.
