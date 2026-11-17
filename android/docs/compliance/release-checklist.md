# Release compliance checklist

Use this checklist for every tagged release. It complements the build/release
steps in [`docs/release.md`](../release.md) and the store answers in
[`docs/legal/play-store-vpn-disclosure.md`](../legal/play-store-vpn-disclosure.md).
Items that cannot be verified on the build host are marked as manual.

## 1. Code and build provenance

- [ ] The release commit is on `main` (or the agreed release branch), CI
      "Build, test, lint" is green, and the tag points at that commit.
- [ ] The OpenVPN Core submodule is the documented pin
      (`10c6a56cb17fd015a2d24953c3c42145bbcdea58` for release/3.9); the
      submodule was not modified. Run `git submodule status`.
- [ ] `./scripts/build-native-deps.sh arm64-v8a x86_64` passes and the
      dependency archive SHA-256 values match
      [`docs/openvpn-core-integration.md`](../openvpn-core-integration.md).
- [ ] Debug APK builds and the **16 KB alignment gate** passes:
      `bash scripts/check-16kb-alignment.sh app/build/outputs/apk/debug/app-debug.apk`
      (every packaged `libnativevpn.so` `LOAD` segment `p_align >= 0x4000` and
      `zipalign -c -P 16 4` succeeds), and the default build packaged both
      `lib/arm64-v8a/libnativevpn.so` and `lib/x86_64/libnativevpn.so`. CI runs
      this on every build **and again on the signed release APK** for a tag.
- [ ] Debug unit tests (AGP 9 enables the unit-test component only for the
      tested build type by default), lint (0 errors), R8 build, and both ABIs
      pass.
- [ ] Signed release APK verified with
      `apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk`.
- [ ] The **release APK** passes the same 16 KB alignment gate and both default
      ABIs are packaged (`unzip -l app-release.apk | grep -E
      'lib/(arm64-v8a|x86_64)/libnativevpn\.so'`). CI runs this before
      publishing; re-run it on the downloaded artifact if the build was manual.
- [ ] JNI callback names survive R8 (`-keep` rules) — checked with `dexdump` or
      an emulator smoke test (native session creation + crypto self-test).
- [ ] `gradlew :app:testDebugUnitTest` passes.

## 2. Signing and secrets

- [ ] Release keystore is **not** committed and is backed up offline; it is
      supplied through `RELEASE_KEYSTORE_*` secrets or a git-ignored
      `keystore.properties`.
- [ ] No keystore, password, token, API key, real profile, or private key is in
      the tree or the artifact (`gitleaks` in the scheduled security workflow,
      plus a manual `git grep` for obvious markers).
- [ ] `android:debuggable` is false in the release build; `apksigner verify
      --print-certs` reports the release certificate, not the debug certificate.
- [ ] `apksigner verify --print-certs` certificate SHA-256 is
      `834d2a58261a6c4113a3170953c1eea4a30d65ebc5ba4386408f09039dd00c9c`
      (the v1.3.0 release key, pinned in `android.yml` as
      `RELEASE_CERT_SHA256`). A different digest means the wrong keystore was
      used: do not publish the tag.
- [ ] `aapt2 dump badging` on the release APK reports `versionName` equal to the
      tag without its leading `v` and `versionCode` greater than 45 (v1.3.0's
      version code); there is no `application-debuggable` entry. CI asserts
      these and fails the tag build otherwise.
- [ ] The APK does not contain a debug/test-only manifest flag, test-only
      activity, or debug `BuildConfig` value that changes security behaviour.

## 3. No debug logging / no secret exposure

- [ ] The release build is not debuggable and does not expose extra logging.
      The app has no `Log.d`/`println`/`printStackTrace` calls; diagnostics are
      sanitized and kept in memory only (verify with `git grep -nE
      'Log\\.[dviwe]\\(|println|printStackTrace' app/src/main/java`).
- [ ] Native log lines are redacted in C++ before crossing JNI; no profile
      content, password, key, or token reaches a log buffer.
- [ ] `android:allowBackup="false"`, `fullBackupContent=false`, and
      `data_extraction_rules.xml` exclude all domains (check the merged
      manifest).
- [ ] `usesCleartextTraffic="false"`; security-relevant components are not
      exported (`MyVpnService` has `exported=false`,
      `BIND_VPN_SERVICE`).
- [ ] No analytics/crash/ads SDK was added; dependency diff reviewed. If one is
      added, update the privacy policy, Data safety answers, and this checklist
      first.
- [ ] Public-IP provider is still `api.ipify.org` and the privacy policy still
      discloses it; if it changed, update both.

## 4. Policy / legal / store

- [ ] `docs/legal/privacy-policy.md` is up to date and **published at the
      canonical public HTTPS URL**
      <https://github.com/smhasnanmonir/openvpn/blob/main/docs/legal/privacy-policy.md>
      (or a verified custom domain); that URL is set in Play Console and the
      store listing, is reachable without login, and matches
      `BuildConfig.PRIVACY_POLICY_URL` (checked in-app via Settings → About →
      Privacy policy). Publication steps: `docs/legal/README.md` §2.
- [ ] The Play Console submission items in `docs/legal/README.md` §3 are
      complete and still match the shipped manifest/behaviour.
- [ ] Play Console "VPN service" declaration reflects the current
      `VpnService` usage and privacy behaviour.
- [ ] Foreground-service `specialUse`/`vpn` declaration and justification are
      present; `FOREGROUND_SERVICE_SPECIAL_USE` is in the manifest.
- [ ] Data safety answers were re-checked against the shipped manifest and the
      app's network calls (profiles, optional `api.ipify.org` check; no new
      SDK/backend).
- [ ] Content rating, target audience (not child-directed), and ads/in-app
      purchase answers are unchanged or re-declared.
- [ ] Target-market/export restrictions reviewed if distribution scope changes;
      listing does not advertise circumvention of lawful controls.
- [ ] Licences/NOTICE/`THIRD_PARTY_NOTICES.md` reviewed for the pinned core and
      dependencies; AGPL source availability satisfied (public repo + pinned
      submodule).

## 5. Supply chain and advisories

- [ ] SBOM generated for the release
      (`bash scripts/generate-sbom.sh`), JSON is valid, and it lists the app
      version, OpenVPN Core 3.9 `10c6a56` (read from the submodule gitlink; the
      script fails if the submodule is missing or not at the pin),
      mbedTLS/asio/fmt/jsoncpp/lz4 versions, NDK `27.2.12479018`, and the Gradle
      runtime classpath. Native upstream dependencies are recorded as
      `pkg:github/<org>/<repo>` purls so the advisory scan can map them; asio is
      recorded as `1.24.0` with the OpenVPN patch count in the
      `openvpn:patches` property.
- [ ] SBOM attached to the GitHub Release (CI does this on `v*` tags) and
      archived with the release records.
- [ ] The scheduled security workflow (`.github/workflows/security.yml`) has
      run recently: no un-triaged critical/high advisory against the SBOM, no
      secret finding. Triage any finding against the SLA in `SECURITY.md`
      (critical 7 days / high 30 / medium 90).
- [ ] OpenVPN 3 Core / mbedTLS advisory review completed for the release (see
      `SECURITY.md` cadence); any core patch follows ADR-006/ADR-010
      fail-fast rules and the submodule pin is updated deliberately with a
      licence delta review.
- [ ] Dependency updates in the release are within the patch SLA or have a
      documented exception.

## 6. Verification evidence

- [ ] Automated subset recorded: JVM unit tests (`:app:testDebugUnitTest`),
      lint, debug/release builds, 16 KB + ABI gate on both the debug and
      signed release APKs, release certificate/version/badging assertions, SBOM,
      scheduled scan status.
- [ ] Device/manual checks that are required for the release scope are listed
      with results in [`docs/testing.md`](../testing.md); anything not run is
      explicitly marked "not verified" rather than assumed.
- [ ] The leak-test matrix status (`docs/security/leak-test-matrix.md`) is
      updated if a physical-device run was performed.
- [ ] **Commercial launch only:** the independent penetration test in
      `docs/security/pen-test-scope.md` covers this build, has no open
      critical/high finding, and all findings are triaged. A release without
      this evidence must be labelled as a non-commercial/preview build.
- [ ] Release notes state the intentional behaviour changes for this release
      (see `CHANGELOG.md`): Require tunnel DNS default ON, strict trust
      rejection, compatibility compression default, split tunneling off by
      default, MTU/security controls.

## 7. Post-release

- [ ] Tag pushed and GitHub Release published with the signed APK and SBOM
      (`app-release.apk`, `sbom.cdx.json`; `fail_on_unmatched_files` makes a
      missing asset fail the release job).
- [ ] Download the **published** APK and re-run the release gates on that exact
      artifact (CI gates the built file, not the uploaded copy):

      ```bash
      gh release download v1.3.0 --pattern app-release.apk
      bash scripts/check-16kb-alignment.sh app-release.apk
      unzip -l app-release.apk | grep -E 'lib/(arm64-v8a|x86_64)/libnativevpn\.so'
      "$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --print-certs app-release.apk
      "$ANDROID_HOME/build-tools/37.0.0/aapt2" dump badging app-release.apk | head -n 1
      ```

      Confirm certificate SHA-256
      `834d2a58261a6c4113a3170953c1eea4a30d65ebc5ba4386408f09039dd00c9c`,
      the tag's `versionName`, `versionCode > 45`, both ABIs, and that the
      release assets include the SBOM.
- [ ] Release certificate digest and APK `sha256` recorded (the APK hash is not
      reproducible; compare the certificate digest and `aapt2` badging).
- [ ] Store listing and privacy policy URL re-checked after publishing.
- [ ] Open follow-up issues for any advisory or residual risk found during the
      release review.
