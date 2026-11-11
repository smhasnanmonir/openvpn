# AGENTS.md — handoff context for AI agents

> **Read this file first.** It is the single source of context for this repository:
> what the product is, what has already been built, what is in flight, what is
> left, and how to verify work. Keep it up to date when you change the project.
>
> **Status snapshot: 2026-11-11 ~14:10 Asia/Dhaka (backlog PR ready for review,
> local gates green).** Do not trust this snapshot blindly: re-run the
> verification commands and check GitHub before acting (see §12). This file is
> committed with the backlog PR; keep it current at merge time.

---

## 0. TL;DR — current state

| Item | Value |
|---|---|
| Platform | Android OpenVPN client (Kotlin + Jetpack Compose + `VpnService` + OpenVPN 3 Core 3.9 via JNI) |
| `main` | `eb532ce` “Merge pull request #5 …”; last release tag `v1.2.0` |
| Active PR | **#22** security/reliability backlog (#8–#21), branch `feat/backlog-security-reliability`; CI “Build, test, lint” **green** (both ABIs, 16 KB gate, SBOM) |
| PR #5 | **merged** (`eb532ce`); closed #4, #6, #7 |
| Unit tests | **225 tests / 24 suites / 0 failures** (`:app:testDebugUnitTest`, merge-candidate branch; was 110/16 on `main`): +115 tests, +8 suites. CI run on PR #22 is green. |
| Lint | **0 errors, 45 warnings** (`:app:lintDebug`, unchanged warning count); `abortOnError=true` |
| Native | Local `x86_64` build verified: three generated-core-header patches compiled in, LOAD `p_align 0x4000`, RELRO + BIND_NOW, stack canary/FORTIFY symbols. `arm64-v8a` is built and gated in CI only (host has no arm64 deps tree). |
| 16 KB pages | Fresh debug APK passes `scripts/check-16kb-alignment.sh` (every packaged `.so` LOAD `p_align 0x4000`, `zipalign -c -P 16 4` OK); CI runs the same gate for both ABIs |
| Open issues | The PR closes #8/#9/#10/#11/#12/#13/#14/#17/#18/#19 and keeps #15 (publish/Play submission), #16 (backend), #20 (iOS), #21 (physical-device leak matrix/pen test) open with the repo deliverables documented |
| Next action | Land the backlog PR, then execute the physical-device leak-test matrix (`docs/security/leak-test-matrix.md`) and publish the privacy policy / Play declarations. |

**What “done” looks like for this milestone:** the backlog PR merged; #8–#21
closed or explicitly tracked (device-only items remain open); privacy policy
published and Play declarations submitted; an independent penetration test
scheduled. See §8.

---

## 1. Product goal and scope

The repo is a **production-minded Android OpenVPN client**: user imports `.ovpn`
profiles, the app creates an Android TUN via `VpnService`, and OpenVPN 3 Core
does the protocol/TLS/packet work in native code. There is no backend, no
account system, no analytics, and no server infrastructure in this repo.

Engineering goals, in priority order:

1. **Never silently weaken VPN security** (no disabled verification, no silent
   fallback that leaks traffic/DNS, fail visibly when protection is lost).
2. **Reliable sessions** (rekey, device sleep/unlock, network transitions,
   process death) with honest UI state.
3. **Android platform correctness** (Always-on/lockdown, foreground service,
   16 KB pages, targetSdk 35).
4. **Provider compatibility** only when explicitly justified and documented
   (see ADR-005: LZO-ASYM + TLS 1.3 cipher normalisation).
5. **Readiness evidence**: tests, lint, native builds, docs, issue tracking.

Out of scope for this repo's *code*: backend/control plane/PKI/gateway (#16),
iOS (#20). Both are now **documented scope decisions/plans** (ADR-014/015 and
`docs/architecture/`), not implementations. Store/legal deliverables (#15) are
documented in `docs/legal/` + `docs/compliance/`; publishing and Console
submission are manual release steps, not app code.

---

## 2. Repository map

```
app/src/main/java/com/smhasnan/openvpn/
  MainActivity.kt, OpenVpnApplication.kt, AppContainer.kt
  config/        ConfigImporter, ProfileImportPolicy, ProfileTrustPolicy,
                 ProfileSecurityPolicy, ProfileRepository, ProfileStore,
                 VpnProfile, ImportFilePolicy, ProfileNameValidator, EmojiCatalog
  nativevpn/     OpenVpnNative (JNI bindings), NativeSession, NativeEngine, NativeCallbacks
  vpn/           MyVpnService, VpnController, VpnState, EngineEventMapper,
                 PauseRetryPolicy, DnsLeakPolicy, MtuPolicy, SplitTunnelPolicy,
                 CaptivePortalPolicy, NetworkMonitor, VpnNotification,
                 VpnTileService, CredentialHandoff, StatsRefreshPolicy
  security/      KeystoreSecretBox, SecretBox
  settings/      SettingsRepository, AlwaysOnVpnStatus
  net/           PublicIpRepository
  ui/            Compose screens (Home, Profiles, Logs, Settings, SplitTunnel) + theme/components
  util/          ByteFormat, DurationFormat, LogSanitizer

app/src/main/cpp/
  CMakeLists.txt              # builds ovpn3 + nativevpn, THREE generated core-header patches
  native_bridge.cpp           # JNI entry points, JniSession, stats/connection JSON, strict-compression flag
  engine_adapter/ovpn_client.{hpp,cpp}   # OvpnClient (OpenVPNClient subclass) + AndroidTunBuilder
  deps/<abi>/                 # prebuilt static deps (mbedtls/jsoncpp/lz4) — gitignored/generated
third_party/openvpn3/         # pinned submodule (OpenVPN 3 Core 3.9 @ 10c6a56)

app/src/test/java/...          # JVM unit tests (policies, state, import, stats; no Robolectric)
app/src/androidTest/java/...   # instrumentation tests (import, native lifecycle, Keystore, user/pass)
docs/                          # ADRs, security/ (+ leak matrix, MASVS), legal/, compliance/,
                               # architecture/ (backend/iOS plans), testing, integration, release
scripts/                       # build/fetch native deps, check-16kb-alignment.sh, generate-sbom.sh
.github/workflows/android.yml  # CI: tests, lint, build, 16 KB gate, SBOM, signed release
.github/workflows/security.yml # scheduled advisory (osv-scanner) + secret (gitleaks) scans
.github/dependabot.yml         # Gradle + GitHub Actions updates

Key facts:

- `minSdk=26`, `compileSdk=35`, `targetSdk=35`, version from `APP_VERSION` env
  (default 1.0.0 locally).
- ABIs default `arm64-v8a,x86_64`; override with `-Pabis=...`.
- NDK `27.2.12479018`, CMake `3.22.1`, Gradle `8.11.1`, AGP `8.7.3`, JVM 17.
- OpenVPN Core is a **pinned submodule** `third_party/openvpn3` @
  `10c6a56cb17fd015a2d24953c3c42145bbcdea58` (release/3.9). **Never modify the
  submodule**; compatibility fixes are build-time patches (§4.1).
- License AGPL-3.0; profiles/credentials are user-supplied/encrypted; no secrets
  or analytics in the app.

---

## 3. Build and verify

### 3.1 Environment (this Windows host)

| Tool | Location / note |
|---|---|
| JDK 17 | `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot` — **`JAVA_HOME` env var may point to a missing `jdk-17.0.18` dir; set it explicitly per shell** |
| Android SDK | `ANDROID_HOME=C:\Users\monir\AppData\Local\Android\Sdk` |
| NDK | `<SDK>\ndk\27.2.12479018` |
| CMake | `<SDK>\cmake\3.22.1` |
| Gradle | wrapper `.\gradlew.bat` (8.11.1); user Gradle cache is warm |
| Submodule | `git submodule update --init --recursive` (needed for native builds) |
| Native deps | CMake needs `app/src/main/cpp/deps/<abi>/lib/libmbedtls.a`. On this host **x86_64 may be prebuilt; arm64 may need `scripts/build-native-deps.sh arm64-v8a`**. CI builds both. |

PowerShell example:

```powershell
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
cd "E:\Code Backup\openvpn"
.\gradlew.bat --no-daemon :app:testDebugUnitTest
.\gradlew.bat --no-daemon :app:lintDebug :app:assembleDebug -Pabis=x86_64
.\gradlew.bat --no-daemon :app:externalNativeBuildDebug -Pabis=x86_64
```

Full local CI equivalent (both ABIs; requires both `deps/` trees):

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

### 3.2 Verifying the generated native patches

```powershell
# after a native build:
Get-ChildItem app\.cxx -Recurse -Filter proto.hpp |
  Where-Object FullName -match 'openvpn3-patched' | Select-Object -First 1
# must contain ALL THREE:
#  1) if (SSLLib::SSLAPI::support_key_material_export() && ... ) iv_proto |= IV_PROTO_DYN_TLS_CRYPT;
#  2) OPENVPN_THROW(process_server_push_error, "server requires dynamic tls-crypt ...")
#  3) the strict-compression server-push rejection (ADR-010) — search for
#     process_server_push_error in parse_pushed_compression / the compression push path.
```

### 3.3 Verifying 16 KB alignment

Preferred (packaged APK, same as CI):

```powershell
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
& 'C:\Program Files\Git\bin\bash.exe' scripts/check-16kb-alignment.sh app/build/outputs/apk/debug/app-debug.apk
# checks every packaged lib/*/*.so LOAD p_align >= 0x4000 and zipalign -c -P 16 4
```

Direct library check (after `:app:externalNativeBuildDebug`):

```powershell
& 'C:\Program Files\Git\bin\bash.exe' scripts/check-16kb-alignment.sh --elf app/build/intermediates/cmake/debug/obj/x86_64/libnativevpn.so
# or the raw readelf equivalent below; expect p_align 0x4000 for every LOAD segment
$ndk = "$env:ANDROID_HOME\ndk\27.2.12479018\toolchains\llvm\prebuilt\windows-x86_64\bin"
& "$ndk\llvm-readelf.exe" -lW app\build\intermediates\cmake\debug\obj\arm64-v8a\libnativevpn.so |
  Select-String LOAD
```

### 3.4 Working on a PR without touching the user's checkout (recommended)

The user's checkout is usually on `main` and must stay clean. Use a temp worktree
or clone:

```powershell
$wt = "C:\Users\monir\AppData\Local\Temp\openvpn-work"
git worktree add --track -b <branch> $wt origin/<branch>
# submodule is empty in a worktree; link the checked-out one:
cmd /c rmdir "$wt\third_party\openvpn3" 2>$null
cmd /c mklink /J "$wt\third_party\openvpn3" "E:\Code Backup\openvpn\third_party\openvpn3"
# for native builds also link deps if prebuilt:
cmd /c mklink /J "$wt\app\src\main\cpp\deps" "E:\Code Backup\openvpn\app\src\main\cpp\deps"
```

Notes/gotchas:

- Removing a junction with `Remove-Item` prompts under non-interactive shells;
  use `cmd /c rmdir <junction>`.
- Long build paths can break `git worktree remove`; remove the directory with
  `\\?\C:\...` prefix, then `git worktree prune`.
- `git status` in a worktree fails if the submodule junction is missing from the
  gitdir resolution; remove/restore the junction around git operations if needed.
- Never run Gradle from the user's `main` checkout for PR work (it can mutate
  tracked build metadata only in rare cases, but keep it clean anyway).

### 3.5 CI

`.github/workflows/android.yml`, job “Build, test, lint” on every push/PR:
checkout with submodules, JDK 17, Gradle 8.11.1, install SDK/NDK/CMake, build
native deps for both ABIs, then:

```
:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Then it runs the **16 KB gate**
(`scripts/check-16kb-alignment.sh app/build/outputs/apk/debug/app-debug.apk`)
and generates/uploads the **CycloneDX SBOM**
(`scripts/generate-sbom.sh`). It uploads the debug APK, androidTest APK, and
SBOM. On a `v*` tag the release job builds/signs the APK and publishes the APK
**and SBOM** to the GitHub Release.

`.github/workflows/security.yml` (push to `main`, weekly schedule,
`workflow_dispatch`, **not** a PR check, `permissions: contents: read`) runs
osv-scanner on the SBOM and gitleaks on the repo. `.github/dependabot.yml`
tracks Gradle/Actions.

**It does not run instrumentation tests on a device** and device leak tests are
still manual (`docs/security/leak-test-matrix.md`).

---

## 4. PR #5 — what has been built (MERGED at `eb532ce`; historical context)

> PR #5 is merged. This section is kept for background; the current work is the
> security/reliability backlog PR described in §4A and §7.

PR: <https://github.com/smhasnanmonir/openvpn/pull/5>
Branch: `fix/dyn-tls-crypt-rekey` (local branch of same name exists; head `4d58b1c`).
Base: `main` @ `6f3f59f`. 18 commits. Merge closes **#4, #6, #7 only**.

### 4.1 Bug fixes (original PR)

1. **Dynamic tls-crypt rekey crash (#4).** Core 3.9 advertises
   `IV_PROTO_DYN_TLS_CRYPT` without checking whether the TLS backend can export
   keying material; mbedTLS cannot, so the first rekey throws
   `KEY_EXPANSION_ERROR` → `client_connect_unhandled_exception`.
   Fix: `app/src/main/cpp/CMakeLists.txt` generates a patched copy of the pinned
   `openvpn/ssl/proto.hpp` at configure time:
   - gate the advertisement on `SSLLib::SSLAPI::support_key_material_export()`;
   - reject a **server-pushed** `dyn-tls-crypt` with
     `OPENVPN_THROW(process_server_push_error, …)` instead of crashing later;
   - fail the build loudly if either upstream snippet changes;
   - normalise CRLF and only rewrite the generated header when its content
     changed (avoids needless full-core rebuilds).
   The submodule stays pinned; do **not** edit `third_party/openvpn3`.
2. **Post-connect timeout should pause, not die (#6).**
   `OvpnClient::pause_on_connection_timeout()` returns `reached_connected_`;
   `EngineEventMapper` maps `PAUSE`/`RESUME` to `RECONNECTING`; a JNI
   `resume()` exists; service retries. See ADR-007.
3. **Swapped traffic counters (#7).** Core TUN counters are device-perspective:
   download = `TUN_BYTES_OUT`, upload = `TUN_BYTES_IN`; transport fallback
   (`BYTES_IN/OUT`) keeps socket direction and is only used when both TUN byte
   counters are zero. `NativeEngine.stats()` implements this;
   `NativeEngineStatsTest` pins it.

### 4.2 Security hardening (original PR)

- **Always-on VPN / lockdown start**: `MyVpnService` handles
  `VpnService.SERVICE_INTERFACE` and connects the persisted selected profile
  with stored credentials.
- **Server-certificate usage**: CA-based profiles without an explicit
  `remote-cert-*`/`tls-remote`/`peer-fingerprint` policy get
  `remote-cert-tls server` at import **and** connect time (legacy profiles).
  This is a deliberate verification tightening — see §10.4.
- **Metered networks**: `VpnService.Builder.setMetered()` on Android 10+.
- **16 KB pages**: `-Wl,-z,max-page-size=16384` on `libnativevpn.so`.
- **MTU diagnostics**: one warning per connection on
  `Message too long`/`EMSGSIZE`.
- **Clearer failures**: CERT_VERIFY_FAIL, proxy credentials, Network Unavailable.

### 4.3 Review-fix pass (added on the same branch after an independent review)

Commits: `778cd5a` (code), `6bba94c` (docs), `4d58b1c` (verification findings).

- **Bounded pause retries** — new `vpn/PauseRetryPolicy.kt`: 5 s initial delay,
  doubling to 60 s, max 6 resume attempts per connected session, then fail
  visibly with “traffic is no longer protected”. The loop no longer trusts the
  JNI `resume()` boolean (Core’s `resume()` is `void` and silently no-ops when
  not paused); it exits on observed `RESUME`/`CONNECTED`/failure. Attempts reset
  only on `CONNECTED`.
- **PAUSE/disconnect race**: `PAUSE` ignored while `finishing`/`DISCONNECTING`;
  `CancellationException` rethrown instead of a spurious ERROR; retry job is
  joined before the native session is closed; narrow `NativeSession`
  resume/close lock avoids resume-after-free; late connect taps call
  `stopSelfSafely()`.
- **Rekey diagnosis reachable**: the service records the engine’s
  `TLS Keying material export error` log line and passes a `rekeyHint` through
  `VpnController.engineEvent(...)` to `EngineEventMapper`, because Core’s event
  payload is only the generic exception name.
- **Inline `<peer-fingerprint>`** block recognised as explicit server policy.
- **Re-import dedupe** accepts the pre-hardening hash (profiles imported before
  `remote-cert-tls server` was appended are not duplicated on upgrade).
- **Import failure** carries the sanitized engine detail instead of a generic
  message.
- **Always-on lifecycle**: explicit persisted profile id only (no silent
  substitution), `START_STICKY` for the system start and for a handled
  null-intent redelivery (`isAlwaysOn`, API 29+), ignore starts during teardown,
  `stopSelfSafely()` on late taps.
- **MTU warning** once per connection.
- **Docs**: ADR-006/ADR-007 corrected; changelog “Compatibility” section added;
  readiness statuses downgraded where unverified.

### 4.4 Decisions that are settled (do not silently reverse)

1. **Pause policy = cap + backoff + explicit error.** Do not restore the
   unbounded 5 s retry loop. `tunPersist=false` remains (a persistent-TUN design
   needs its own ADR; see ADR-007 §4). While paused the TUN is closed and
   Android restores the underlying network, so the UI must not imply protection.
2. **`remote-cert-tls server` stays enforced** for CA-based profiles, both at
   import and connect. The resulting compatibility break (server certs without
   `serverAuth` EKU) is documented and must stay in the changelog.
3. **Core stays pinned and unmodified**; all core changes are generated-header
   patches with loud failure if upstream text moves. If upstream fixes the same
   bug, remove the corresponding patch and update ADR-006.
4. **Provider compatibility is explicit**: `compressionMode="asym"` (ADR-005)
   and `tls-cipher` normalisation stay unless a product decision (#19) changes
   them; do not silently “harden” and break providers.
5. **Issue #8–#21 are follow-ups**, not secretly included in PR #5. (Their
   closing links to PR #5 were removed; only #4/#6/#7 close on merge.)

### 4.5 PR #5 residual items (historical; device checks still open)

These were the merge-time caveats. The merge happened; the **device manual
checks remain open** and are part of the backlog PR's device matrix. The stale
PR-description notes are retained for history only.

- **Device manual checks still required** (unit tests do not cover runtime):
  live rekey (`reneg-sec 60`), lock/unlock past `connTimeout`, Disconnect while
  reconnecting, bounded give-up error against unreachable server, counter
  directions, first-connect timeout error, Always-on/lockdown recovery
  (`am kill`, reboot after first unlock), 16 KB Android 15 device, MTU hint.
  Full lists in `docs/testing.md` (“Review-fix pass” and earlier sections).
- **PR description is stale**: says 103 tests/15 suites, “retries every 5
  seconds”, “No TLS or certificate verification setting is changed”, and the
  impossible “force-stop and confirm the tunnel comes back” check. Update it
  when merging (suggested corrected wording is in §8.2).
- **Known non-blocking residuals**:
  - counters can briefly go backwards when TUN counters first appear (cosmetic);
  - `remote-cert-ku`-only profiles are exempted from the EKU hardening;
  - pre-existing unlocked native-handle reads for
    `connect()/statsJson()/disconnect()` (not introduced by #5; tracked under
    #14/lifecycle);
  - issue bodies for #14 and #21 still describe the pre-review state
    (“fixed 5 s loop”, “103 tests”) — update them after merge.
- **Closing references**: #4/#6/#7 only. Do not re-link #8–#15 as closing.

### 4A. Backlog PR (#8–#21) — what this branch ships

Branch `feat/backlog-security-reliability`, base `main` @ `eb532ce` (PR #5
merged). Design is frozen; all 14 issues are addressed, but only some have
runtime/device verification.

| Issue | Delivered in this PR | ADR / docs |
|---|---|---|
| #8 DNS fail-closed | `DnsLeakPolicy`; "Require tunnel DNS" default ON + validated IPv4/IPv6 fallback DNS; refusal before TUN creation; logs fallback usage; Settings UI. | ADR-008, `docs/security.md` |
| #9 Always-on/lockdown | Settings readiness card via `AlwaysOnVpnPolicy`/`AlwaysOnVpnStatus` (hidden `Settings.Secure` keys, UNKNOWN when unreadable), explanations, link; never claims a kill switch. | `docs/security.md` |
| #10 MTU | `MtuPolicy`; Auto/1500/1400/1280 setting; fixed value appends `pull-filter ignore tun-mtu` + `tun-mtu N`; MTU in connection details; no auto-reconnect loop. | `docs/openvpn-core-integration.md` |
| #11 strict trust | `ProfileTrustPolicy`; import + connect-time requirement for CA/`peer-fingerprint`/`tls-remote`; CA-change warning; revocation documented server-side; no CRL/OCSP. | ADR-009, `docs/profile-import.md` |
| #12 16 KB + hardening | `scripts/check-16kb-alignment.sh` (packaged `.so` LOAD `p_align >= 0x4000`, `zipalign -c -P 16 4`) wired into CI; hardening flags kept/documented. | `docs/testing.md`, `docs/release.md` |
| #13 supply chain | `scripts/generate-sbom.sh` (CycloneDX, native + Gradle runtime), SBOM artifact + release asset, `.github/workflows/security.yml` (osv-scanner + gitleaks, scheduled/main, read-only), `.github/dependabot.yml`, `SECURITY.md` SLA/emergency process, `docs/compliance/release-checklist.md`. | `docs/release.md` |
| #14 lifecycle | `desiredConnectedProfileId` persisted separately from native session; `START_STICKY` restore with fresh native session; bounded backoff + bounded jitter; no boot receiver; force-stop/reboot/other-VPN documented. | ADR-013 |
| #15 compliance | `docs/legal/privacy-policy.md`, `docs/legal/play-store-vpn-disclosure.md` (VpnService/Data-safety/specialUse/`QUERY_ALL_PACKAGES`), `docs/compliance/release-checklist.md`. Publication/submission manual. | ADR-014 scope note |
| #16 backend | Scope decision + control/data-plane architecture/PKI/device/runbook outline; **no backend code**. | ADR-014, `docs/architecture/backend-control-plane.md` |
| #17 split tunneling | `SplitTunnelPolicy`; Off/Include/Exclude + per-app picker; Home/Settings leak warnings; fail-closed zero-app validation; stale-package filtering; `QUERY_ALL_PACKAGES` declared/justified; changes next connection. | ADR-011, `docs/security.md` |
| #18 captive portal | `CaptivePortalPolicy`; capability detection, "Pause VPN to sign in", honest `pausedForPortal` state, auto-resume on validated, manual sign-in URL. | ADR-012 |
| #19 compression/cipher | `CompressionPolicy` (Compatibility default vs Strict) + generated `proto.hpp` patch #3; `CipherPolicy` (Profile default vs AEAD-only `pull-filter` transform); diagnostics state policy. | ADR-010 |
| #20 iOS | Scope decision + platform/App Store plan; **no iOS code**. | ADR-015, `docs/architecture/ios-plan.md` |
| #21 assurance | `docs/security/leak-test-matrix.md`, `docs/security/masvs-gap-assessment.md` (MASVS v2 + ASVS note + independent pen-test requirement); automated subset documented. | — |

Verification status for this PR (honest): new policies are JVM-tested; CI runs
tests/lint/native builds + 16 KB gate + SBOM; **physical-device leak tests and
store/pen-test work are not done**. See `docs/testing.md` "backlog PR" section
and `docs/security/leak-test-matrix.md` §7.

---

## 5. Project history (brief)

- v1.0.x–v1.1.x: core client, profile import, Keystore storage, logs, tile.
- PR #2 / v1.2.0 (`6f3f59f`): Home network redesign, profile switching, custom
  emoji, performance/battery work, public-IP card; ADR-004 (certificate-less
  user/pass profiles), ADR-005 (TLS 1.3 + LZO-ASYM provider compatibility).
- PR #5 (merged, `eb532ce`): reliability fixes (#4/#6/#7), security hardening,
  readiness audit, review-fix pass in §4.
- Backlog PR (`feat/backlog-security-reliability`): the #8–#21 security/
  reliability/compliance/CI work in §4A. This is where the project is now.

---

## 6. Architecture quick tour (where to change what)

| Concern | Files |
|---|---|
| Service lifecycle, connect/disconnect, TUN creation | `vpn/MyVpnService.kt` |
| Session state machine, log buffer, single source of truth | `vpn/VpnController.kt`, `vpn/VpnState.kt` |
| Core event → UI state/message mapping | `vpn/EngineEventMapper.kt` |
| Pause retry policy constants | `vpn/PauseRetryPolicy.kt` (change constants/tests together) |
| DNS fail-closed policy + fallback | `vpn/DnsLeakPolicy.kt`, `MyVpnService.establishTun` |
| MTU override | `vpn/MtuPolicy.kt`, `SettingsRepository.mtuMode` |
| Split tunneling validation/warnings | `vpn/SplitTunnelPolicy.kt`, `ui/SplitTunnelScreen.kt` |
| Captive portal detection/pause | `vpn/CaptivePortalPolicy.kt`, `NetworkMonitor.kt`, `VpnState.pausedForPortal` |
| Strict profile trust + import warnings | `config/ProfileTrustPolicy.kt`, `ConfigImporter.kt` |
| Compression/cipher policy transforms | `config/ProfileSecurityPolicy.kt`, `native_bridge.cpp` (`compressionMode`) |
| Always-on readiness card | `settings/AlwaysOnVpnStatus.kt`, `ui/SettingsScreen.kt` |
| Network change/metered detection | `vpn/NetworkMonitor.kt` |
| Notification/tile/Quick Settings | `vpn/VpnNotification.kt`, `vpn/VpnTileService.kt` |
| Profile import/normalisation/policy | `config/ConfigImporter.kt`, `config/ProfileImportPolicy.kt` |
| Profile/credential persistence (encrypted) | `config/ProfileRepository.kt`, `ProfileStore.kt`, `security/KeystoreSecretBox.kt` |
| JNI surface + native session | `nativevpn/OpenVpnNative.kt`, `NativeSession.kt`, `NativeEngine.kt` |
| JNI implementation, callbacks, stats JSON | `app/src/main/cpp/native_bridge.cpp` |
| Core client subclass, TUN builder, pause hook, reachable-connect flag | `app/src/main/cpp/engine_adapter/ovpn_client.{hpp,cpp}` |
| Generated core patches + 16 KB link flags | `app/src/main/cpp/CMakeLists.txt` |
| Settings (selected/desired profile, DNS, MTU, split tunnel, policies) | `settings/SettingsRepository.kt` |
| UI | `ui/HomeScreen.kt`, `ui/ProfilesScreen.kt`, `ui/SettingsScreen.kt`, `ui/SplitTunnelScreen.kt`, `ui/LogsScreen.kt`, `ui/AppViewModel.kt` |

Data-flow invariants:

- `VpnController.snapshot` is the only UI state source; engine callbacks write it
  from arbitrary threads. Logs are a coalesced 500-line ring buffer.
- The service never marks `CONNECTED` itself; only Core's `CONNECTED` event does.
- The selected profile is persisted in `SettingsRepository`; profile bodies are
  AES-256-GCM encrypted with a non-exportable Keystore key in
  `noBackupFilesDir/profiles`, credentials separately in `…/credentials`.
- Transport sockets are protected with `VpnService.protect()`.
- Native session handles are opaque `jlong`s; `NativeSession.close()` destroys
  the session. There is a known pre-existing race on `connect()`/stats calls vs
  close (see §4.5); do not make it worse, and consider a native registry with
  refcounts if touching this area.

---

## 7. Issue backlog status (#8–#21)

**At this snapshot all 14 are addressed by the backlog PR (§4A), pending review
and merge.** #8/#9/#10/#11/#12/#13/#14/#17/#18/#19 have code + tests/policy
objects; #15/#16/#20/#21 are documentation/assurance deliverables (no
store/backend/iOS code). Runtime device verification for the leak/kill-switch/
portal/lifecycle rows remains **open** and must not be claimed. The per-issue
descriptions below are the original issue text (useful context; several "Current"
lines are now stale) — use §4A for what shipped and `docs/security-readiness.md`
for the current honest status.

Issues: #8–#21. PR #5 (merged) advanced some; do not close anything solely
because PR #5 merged.

### #8 — [security: block DNS leaks when no resolver is pushed](https://github.com/smhasnanmonir/openvpn/issues/8)
- **Why:** a VPN must not silently fall back to the ISP/underlying DNS.
- **Current:** `establishTun` adds pushed DNS and logs a warning when none are
  usable, then connects; docs say no public resolver is injected and DNS is not
  blocked.
- **Work:** add a “Require tunnel DNS” / leak-protection setting; when enabled
  and no usable resolver is pushed, fail the connection with a clear error;
  optionally allow a user-configured fallback resolver sent through the tunnel;
  cover IPv4/IPv6, search domains, reconnect/DNS reconfiguration; tests.
- **Acceptance:** profile with no pushed DNS cannot silently connect while the
  setting is on; UI explains refusal; IPv4/IPv6 covered; docs updated.
- **Touch:** `MyVpnService.establishTun`, `SettingsRepository`, UI, `docs/security.md`,
  `docs/testing.md`.

### #9 — [security: guide/verify Android Always-on VPN + lockdown](https://github.com/smhasnanmonir/openvpn/issues/9)
- **Why:** Always-on + “Block connections without VPN” is the only reliable
  system-level kill switch.
- **Current:** PR #5 handles the `SERVICE_INTERFACE` start and Settings links to
  `Settings.ACTION_VPN_SETTINGS`; no readiness check, no explanation, no
  verification that Always-on is enabled for this app.
- **Work:** settings card that detects Always-on/lockdown where the platform
  allows and links to the right screen; explain trade-offs; reboot/process-death/
  revocation/other-VPN test checklist; never claim a guaranteed kill switch
  when lockdown is off.
- **Acceptance:** users can see whether system protection is enabled; behavior
  documented/tested; app never labels connection “protected” merely because the
  service runs.
- **Touch:** `ui/SettingsScreen.kt`, `MyVpnService`, `docs/security.md`, tests.
- **Note:** null-intent `isAlwaysOn` path only works on API 29+; before that the
  app stops the service on a redelivered null intent.

### #10 — [reliability: configurable TUN MTU / path-MTU black holes](https://github.com/smhasnanmonir/openvpn/issues/10)
- **Why:** wrong MTU causes stalled downloads and `Message too long`; provider
  log already had an EMSGSIZE event.
- **Current:** uses pushed `tun-mtu` (1500); Core clamps TCP MSS but large UDP
  can exceed path MTU; PR #5 only adds a diagnostics warning.
- **Work:** advanced MTU setting (Auto/1500/1400/1280) overriding pushed value;
  investigate PMTU discovery and IPv6 overhead; test Wi-Fi/cellular/IPv4/IPv6/
  dual-stack with large transfers; consider one-time automatic fallback after
  repeated EMSGSIZE without reconnect loops.
- **Acceptance:** user can lower TUN MTU without editing `.ovpn`; large
  down/upload works at 1400-byte path; value shown in details/diagnostics; no
  unexpected reconnect loop.
- **Touch:** `MyVpnService.establishTun`, `SettingsRepository`, UI, tests/manual
  on a controlled MTU path.

### #11 — [security: strict certificate trust validation + revocation docs](https://github.com/smhasnanmonir/openvpn/issues/11)
- **Why:** verification is only as strong as profile directives; PR #5 adds
  `remote-cert-tls server` but profile trust material is not strictly validated.
- **Current:** never disables verification; Core handles CA/server-usage/
  `verify-x509-name`/`peer-fingerprint`; no policy for profiles with no CA and no
  pin; no CRL/OCSP; no CA-change warning.
- **Work:** strict profile policy (require CA or `peer-fingerprint`, or an
  explicit documented exception); document that revocation is server/PKI-side;
  warn when a same-named profile is re-imported with a different CA fingerprint;
  negative tests (expired/wrong CA/client-cert-as-server/hostname mismatch).
- **Acceptance:** profile with no trust material rejected or clearly marked;
  `remote-cert-tls` enforcement covered by import/connect tests; revocation
  documented; negative tests against a controlled server.
- **Touch:** `ProfileImportPolicy`, `ConfigImporter`, `docs/security.md`, ADR-004/005.
- **Caution:** §4.4 decision — do not weaken the PR #5 enforcement; this issue is
  about *stricter* validation and evidence.

### #12 — [android15: verify 16 KB support + native hardening](https://github.com/smhasnanmonir/openvpn/issues/12)
- **Why:** Android 15 supports 16 KB pages and Play requires native
  compatibility.
- **Current:** PR #5 adds `-Wl,-z,max-page-size=16384`; `.so` LOAD align
  `0x4000` verified for both ABIs; no CI alignment check; hardening flags and
  device matrix undocumented.
- **Work:** CI step that fails when packaged `.so` is not 16 KB aligned (both
  ABIs); install/run on 16 KB Android 15 emulator/device; review
  `-fstack-protector-strong`, RELRO/NOW, FORTIFY, visibility; document NDK
  defaults; keep dependency build reproducible.
- **Acceptance:** CI rejects misaligned libs; app launches/connects on 16 KB
  device; hardening documented; release notes mention Android 15.
- **Touch:** `.github/workflows/android.yml`, `scripts/`, `docs/testing.md`,
  `app/build.gradle.kts` if flags change.

### #13 — [supply-chain: SBOM, advisory monitoring, patch deadlines](https://github.com/smhasnanmonir/openvpn/issues/13)
- **Why:** pinned OpenVPN 3 Core 3.9 + mbedTLS 3.6.2 are high-value targets.
- **Current:** pins/hashes documented in `docs/openvpn-core-integration.md`; CI
  builds both ABIs; no SBOM, no vulnerability scan, no patch-deadline policy.
- **Work:** SBOM per release; advisory monitoring for OpenVPN 3, mbedTLS, asio,
  fmt, jsoncpp, lz4, AndroidX, NDK; documented security-update SLA/emergency
  release process; secret scanning and signed-build provenance; license delta on
  core updates.
- **Acceptance:** SBOM per release; advisory owner + patch deadline; CI scans;
  release checklist includes core/mbedTLS versions.
- **Touch:** CI, release docs, `docs/openvpn-core-integration.md`, `SECURITY.md`.

### #14 — [reliability: process-death/reboot/reconnect lifecycle](https://github.com/smhasnanmonir/openvpn/issues/14)
- **Why:** VPN must recover from process death/reboot/network loss without stale
  state or silent loss of protection.
- **Current (post-#5):** bounded pause retry with backoff; Always-on start
  persists profile and returns `START_STICKY`; null intent handled only when
  `isAlwaysOn`; `onDestroy` closes native session. Still missing: persisted
  “desired connected” state, boot receiver, process-death tests, jitter,
  handling another VPN app taking over.
- **Work:** define behavior for process death/reboot/force-stop/other VPN;
  persisted desired-state model separate from native session; exponential
  backoff with jitter + cap (extend `PauseRetryPolicy` or add lifecycle policy);
  tests/manual checks; avoid duplicate sessions/stale notifications.
- **Acceptance:** never restore a dead native pointer; reboot/Always-on behavior
  documented+tested; bounded backoff with jitter; no stale notification/tile.
- **Touch:** `MyVpnService`, `PauseRetryPolicy`, `VpnController`,
  `AndroidManifest.xml` (if boot receiver), `docs/testing.md`, ADR-007.
- **Note:** issue body is stale (says “fixed 5 s loop”); update it after merge.

### #15 — [compliance: privacy policy + Play VpnService disclosure](https://github.com/smhasnanmonir/openvpn/issues/15)
- **Why:** Play regulates VpnService apps; need disclosure/encryption/no traffic
  manipulation and a truthful privacy policy.
- **Current:** `specialUse`/`vpn` FGS metadata; no analytics/crash reporter/
  backend; optional public-IP check to `https://api.ipify.org`; no privacy
  policy or Play disclosure checklist in repo.
- **Work:** privacy policy (public-IP, diagnostics, no-analytics stance); Play
  VpnService declaration and Data safety form; `specialUse` justification;
  target-market store review; ensure release signing + no debug logging.
- **Acceptance:** policy published+linked; declarations complete; release
  checklist covers store compliance; public-IP provider disclosed.
- **Touch:** docs/legal/store checklists (out of app code).

### #16 — [backend: control plane, PKI, device management, gateway hardening](https://github.com/smhasnanmonir/openvpn/issues/16)
- **Why:** a VPN client is only part of a commercial product.
- **Current:** this repo is an Android client using user-supplied `.ovpn`
  profiles; no accounts/subscriptions/device management/PKI/discovery/control
  plane.
- **Work:** architecture separating control/data plane; account auth + MFA +
  short-lived tokens + device management; PKI issuance/revocation with offline
  root + intermediate; harden gateways/DNS/APIs/admin; monitoring/rate-limiting/
  abuse/incident response; backend ASVS + privacy review.
- **Acceptance:** documented architecture; unique revocable device credentials;
  server-side authorization tested; PKI/gateway owners and runbooks.
- **Note:** umbrella/out of this repo; split into backend/PKI/infrastructure
  issues when work starts.

### #17 — [feature: per-app split tunneling with leak warnings](https://github.com/smhasnanmonir/openvpn/issues/17)
- **Why:** common feature, common leak source.
- **Current:** no `addAllowedApplication()`/`addDisallowedApplication()`; routes/
  excluded routes come from profile/Core; docs say not exposed.
- **Work:** allow/deny app selector with clear summary; refuse enabling while
  connected or force reconnect; persistent “apps bypass the VPN” warning; test
  with traffic capture; reconcile profile routes/excluded routes with app policy.
- **Acceptance:** users select apps; UI states what bypasses; no silent leaks;
  reconnect/profile-switch coverage.
- **Touch:** `MyVpnService.establishTun`, settings/UI, `docs/security.md`,
  testing matrix.
- **Decision needed:** product scope + whether per-profile policy is allowed.

### #18 — [feature: captive-portal detection + temporary pause/sign-in](https://github.com/smhasnanmonir/openvpn/issues/18)
- **Why:** captive portals are impossible through full-tunnel VPN; users may
  disable the VPN entirely.
- **Current:** no detection/pause flow; Core pause (PR #5) is not a portal flow;
  no “sign in to network” action.
- **Work:** detect `NET_CAPABILITY_CAPTIVE_PORTAL`/sign-in intents; “Pause VPN to
  sign in” action that clearly pauses, opens portal, reconnects; never show
  “Protected” while paused; document exposure.
- **Acceptance:** portal can be completed without disabling app; UI accurate;
  tunnel reconnects; tested on a real portal.
- **Touch:** `NetworkMonitor`, `MyVpnService`, `MainActivity`/UI, `docs/security.md`.

### #19 — [security: strict compression + AEAD-only cipher modes](https://github.com/smhasnanmonir/openvpn/issues/19)
- **Why:** VORACLE / legacy ciphers.
- **Current:** `compressionMode="asym"` for provider compatibility (ADR-005);
  symmetric compression refused; legacy `tls-cipher` pins dropped at import;
  no strict mode; `data-ciphers`/`cipher` still profile-driven.
- **Work:** security setting/mode that refuses server-pushed compression; AEAD-
  only policy for new profiles with explicit compatibility opt-in for legacy
  providers; warn on BF-CBC/CBC-only pins; document trade-off.
- **Acceptance:** users choose strict/compat policy; compatibility mode explicit
  not silent; diagnostics state policy; tests for both modes.
- **Touch:** `native_bridge.cpp` config, `ProfileImportPolicy`, settings/UI,
  ADR-005, `docs/security.md`.
- **Decision needed:** default policy and whether to break legacy providers.

### #20 — [ios: Network Extension client + App Store compliance](https://github.com/smhasnanmonir/openvpn/issues/20)
- **Why:** separate platform/product decision.
- **Current:** no iOS code/targets.
- **Work:** decide scope; use `NEPacketTunnelProvider` (not Android assumptions);
  Keychain sharing; full-tunnel `includeAllNetworks`, exclusions, on-demand
  rules, captive portals; App Store guideline 5.4 (organization enrollment, VPN
  disclosure, privacy, licensing); share the Core pin/update process.
- **Acceptance:** platform scope documented; architecture/entitlements if in
  scope; credential-sharing design reviewed; App Store checklist complete.
- **Note:** out of scope for this Android repo/PR.

### #21 — [testing: leak-test matrix, MASVS/ASVS, penetration test](https://github.com/smhasnanmonir/openvpn/issues/21)
- **Why:** the app must fail securely, not only connect.
- **Current:** 110 JVM tests (after review-fix pass; issue text says 103 — stale);
  instrumentation tests for import/native lifecycle/Keystore/user-pass; a
  controlled OpenVPN 2.7 tunnel on an emulator; no packet-capture leak suite, no
  real-device matrix, no MASVS/ASVS review, no external pen test; captive portal/
  IPv6-only/DNS leak/kill switch/process death pending.
- **Work:** leak-test harness with packet capture at the network boundary;
  cover IPv4/IPv6/DNS leaks, kill switch/lockdown, Wi-Fi↔cellular, airplane
  mode, captive portal, process death, reboot, other VPN; multiple Android
  versions/real devices; map app→MASVS, backend→ASVS; independent pen test;
  regression tests for every PR #5 bug.
- **Acceptance:** documented matrix passes on physical devices; gap assessment;
  findings triaged; CI covers automated subset.
- **Touch:** `docs/testing.md`, new `scripts/`/CI/emulator harness, maybe
  `androidTest`.

---

## 8. Recommended order and how to start

### 8.1 First actions for the next agent (in order)

1. Read `AGENTS.md` (this file), `docs/security-readiness.md`, `docs/testing.md`,
   `docs/security/leak-test-matrix.md`, `docs/security/masvs-gap-assessment.md`,
   and the ADRs referenced in §4A.
2. Confirm the backlog PR state on GitHub (`gh pr view`, `gh pr checks`,
   `gh issue list`). **Do not switch/checkout branches or run mutating git
   commands on the shared working tree unless asked.**
3. Run the local gates on the merge candidate:
   `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`,
   `:app:externalNativeBuildDebug` (x86_64 at least), then
   `scripts/check-16kb-alignment.sh app/build/outputs/apk/debug/app-debug.apk`
   and `scripts/generate-sbom.sh`. Record the exact test count/suites here.
4. Execute the physical-device leak-test matrix (`docs/security/leak-test-matrix.md`)
   for the rows that need it; update `docs/testing.md` honestly.
5. Then external work: publish the privacy policy + Play declarations, and
   commission the independent penetration test. Backend (#16)/iOS (#20) stay
   separate tracks/repositories.

### 8.2 Suggested PR #5 description corrections (historical; PR #5 is merged)

PR #5 merged; this is retained so the record is accurate.

Keep the `Fixes #4, #6, #7` line, but correct the rest:

- Tests: **110 tests in 16 suites**, not 103/15.
- Retry: bounded `PauseRetryPolicy` (5 s → 60 s, max 6 attempts, then explicit
  “traffic is no longer protected” error), not “every 5 seconds”.
- Remove “No TLS or certificate verification setting is changed”: PR #5 *does*
  enforce `remote-cert-tls server`; the compatibility break is documented.
- Always-on manual check: force-stop prevents background starts; use
  `adb shell am kill com.smhasnanmonir.openvpn` (and unlock first after reboot).
- Add: generated header also rejects server-pushed `dyn-tls-crypt`; rekey
  message uses the engine log; inline `<peer-fingerprint>` recognised.
- Keep “Fixes #4, #6, #7” and the remaining-gaps list (#8–#21 are **not**
  closed by this PR; their closing links were removed).

### 8.3 Suggested acceptance criteria for any change

- `:app:testDebugUnitTest` passes; add/adjust tests for new behavior.
- `:app:lintDebug` 0 errors (do not add new warnings in changed files).
- `:app:assembleDebug` (and `:app:assembleDebugAndroidTest` if UI/androidTest
  touched) builds; native `:app:externalNativeBuildDebug` for both ABIs if
  `cpp/`/`CMakeLists.txt` changed.
- If native output changed: verify generated header patches (§3.2) and 16 KB
  alignment (§3.3).
- Update the affected docs/ADR/readiness status; update the relevant GitHub
  issue checklist (create/close only what is actually done).
- Conventional commit messages (`fix(scope): …`, `feat(scope): …`,
  `docs: …`, `build(native): …`, `test: …`); no secrets, no submodule changes.
- State clearly what was verified vs. what still needs a device.

---

## 9. Working agreements for agents

- **Stay off the user's checkout:** branch/worktree/clone for changes; restore
  `main` state and remove temp worktrees/junctions when done. Verify with
  `git status --short --branch` in `E:\Code Backup\openvpn`.
- **Do not modify** `third_party/openvpn3` (submodule), pinned core sources,
  licenses, or signing configuration. Build-time patches go through
  `app/src/main/cpp/CMakeLists.txt` with fail-fast checks.
- **Security invariants:** never disable certificate verification, never
  silently fall back from fail-closed behavior, never log secrets/keys/profiles,
  keep `android:allowBackup=false`, `usesCleartextTraffic=false`,
  `exported=false` for the VPN service, protect transport sockets, and keep
  `FLAG_SECURE`/biometric UI gate untouched unless the issue explicitly asks.
- **No network side effects without asking:** pushing branches, editing issues
  or PR metadata, merging, or posting comments requires the user's request; the
  PR #5 closing-reference cleanup was explicitly done to prevent accidental
  issue closure — do not re-add those links.
- **Privacy:** there is no analytics/crash SDK and no backend; only the optional
  `api.ipify.org` check. Do not add third-party trackers without a product
  decision.
- **AGPL-3.0:** keep license/NOTICE/THIRD_PARTY_NOTICES consistent when adding
  dependencies.
- If a build fails on this host because `deps/<arm64>` is missing, build it with
  `scripts/build-native-deps.sh arm64-v8a` (CI does this) — do not fix it by
  hacking CMake.

---

## 10. Known traps and gotchas

### 10.1 Windows/environment
- `JAVA_HOME` may point at a deleted `jdk-17.0.18`; set it explicitly to
  `jdk-17.0.20.8-hotspot`.
- PowerShell `NativeCommandError`/exit 1 often wraps harmless stderr from
  `git`/Gradle; check the actual output (e.g., a successful `git push` prints
  “To https://…” to stderr).
- Junctions: `cmd /c mklink /J`, remove with `cmd /c rmdir`; `git worktree
  remove` can fail on long paths (use `\\?\` prefix + `git worktree prune`).
- `Get-Content`/`Set-Content` on the wrong encoding can corrupt emoji/UTF-8 docs;
  use the read/edit tools for files with non-ASCII content.

### 10.2 OpenVPN Core / native
- `tunPersist=false`: TUN is recreated per connection/resume. Do not enable
  persistence without a design (ADR-007).
- Core `OpenVPNClient::resume()` is `void` and silently no-ops when not paused;
  never treat “JNI call returned” as “resumed”.
- Core reports `KEY_EXPANSION_ERROR` as `client_connect_unhandled_exception`;
  the specific rekey message depends on the engine log hint.
- The CMake patch strings are exact; if the pinned core changes, the build
  fails with a pointer to the function to re-check (`peer_info_string()`,
  `parse_pushed_protocol_flags()`, `parse_pushed_compression()`). Update the
  patches and ADR-006/ADR-010 deliberately. There are now **three** patches
  (dyn-tls-crypt advertisement gate, server-pushed dyn-tls-crypt rejection,
  strict-compression server-push rejection).
- 16 KB alignment comes from `-Wl,-z,max-page-size=16384`; do not remove it.
- Native handle lifetime: `NativeSession.close()` destroys the session; the
  resume path is locked, but `connect()`/stats reads still have a pre-existing
  race (see #14). Prefer adding a native registry/refcount if touching this.

### 10.3 Service / platform
- While Core is paused, the TUN is closed and Android restores the underlying
  network: traffic is **not** protected. The UI must not imply otherwise, and
  retries must stay bounded (ADR-007). Always-on lockdown keeps its own block.
- `VpnService.SERVICE_INTERFACE` is the Always-on start action; the manifest
  already has the filter and `BIND_VPN_SERVICE`. `isAlwaysOn()` needs API 29+.
- `connect()` uses `startForegroundService`; a connect intent during teardown
  must satisfy the foreground requirement (PR #5 calls `stopSelfSafely()`).
- No wakelock is held; Doze can delay retry timers.
- `ConfigImporter`/`ProfileStore` run on IO; Keystore is unavailable before the
  first unlock on boot — Always-on cannot restore the tunnel pre-unlock.

### 10.4 Certificate policy
- `remote-cert-tls server` is appended at import and connect for CA profiles
  without explicit policy; this can reject legacy servers whose leaf lacks
  `serverAuth` EKU. This is documented as a compatibility break — do not
  “fix” it by silently skipping verification; if support wants an escape hatch,
  make it explicit and documented (see ADR-009).
- Strict trust (ADR-009): a profile needs a CA, `peer-fingerprint` (line or
  `<peer-fingerprint>` block), or legacy `tls-remote`; otherwise it is rejected
  at import **and** refused at connect. Do not add an "accept untrusted"
  setting. Revocation is server/PKI-side; the app has no CRL/OCSP.

### 10.5 Provider compatibility (ADR-005/ADR-010)
- `compressionMode="asym"` (Compatibility, default) and dropping legacy-only
  `tls-cipher` pins are deliberate; strict compression and AEAD-only ciphers
  are **opt-in** settings, not silent defaults. Do not “harden” the default and
  break providers without a product decision.
- The strict-compression behaviour depends on generated patch #3; if the
  mbedTLS/core pin changes, re-verify it (`§3.2`).
- Split tunneling (ADR-011) needs `QUERY_ALL_PACKAGES` on Android 11+; the
  package list must stay local-only (never log/transmit it).

---

## 11. This file is committed

`AGENTS.md` is committed with the backlog PR so the handoff survives the branch.
Keep the status snapshot accurate at merge time and after any status change.
Some agents automatically read a root `AGENTS.md`; others need it pointed at
explicitly.

---

## 12. Status freshness checklist (run before trusting this file)

- `git status --short --branch` — on the expected branch
  (`feat/backlog-security-reliability` for the backlog PR); do not switch
  branches in the shared working tree.
- Confirm the backlog PR is the active work (`gh pr view`, `gh pr checks`) and
  that #8–#21 are addressed as in §4A.
- `.\gradlew.bat --no-daemon :app:testDebugUnitTest` — expect **225 tests /
  24 suites / 0 failures** on the backlog branch (110/16 on `main`). Then
  `:app:lintDebug` (0 errors, 45 warnings) and `assembleDebug`.
- `:app:assembleDebugAndroidTest` must compile (it did on the merge candidate).
- `:app:externalNativeBuildDebug -Pabis=x86_64` must succeed and the generated
  header must contain all three patches (§3.2).
- `scripts/check-16kb-alignment.sh app/build/outputs/apk/debug/app-debug.apk`
  — must pass for the fresh APK (a stale APK with `p_align=0x1000` correctly
  fails).
- `scripts/generate-sbom.sh` — valid CycloneDX JSON; review the scheduled
  osv-scanner/gitleaks results.
- Update this file and `docs/testing.md` when the numbers/status change.

---

## 13. Reference index

- Issues: <https://github.com/smhasnanmonir/openvpn/issues> (#8–#21 backlog)
- PR #5 (merged): <https://github.com/smhasnanmonir/openvpn/pull/5>
- Review-fix commits: `778cd5a`, `6bba94c`, `4d58b1c`
- ADRs: `docs/decisions/ADR-004-*` (cert-less user/pass), `ADR-005-*`
  (provider compat TLS 1.3 + LZO-ASYM), `ADR-006-*` (dynamic tls-crypt EKM),
  `ADR-007-*` (post-connect pause/retry), `ADR-008-*` (DNS fail-closed),
  `ADR-009-*` (strict server trust), `ADR-010-*` (compression/cipher modes),
  `ADR-011-*` (split tunneling), `ADR-012-*` (captive portal pause),
  `ADR-013-*` (desired-state lifecycle), `ADR-014-*` (backend scope),
  `ADR-015-*` (iOS scope)
- Readiness: `docs/security-readiness.md` (status per area + issue map)
- Verification log: `docs/testing.md` (unit/instrumentation/manual/backlog PR)
- Leak matrix: `docs/security/leak-test-matrix.md`;
  MASVS/ASVS assessment: `docs/security/masvs-gap-assessment.md`
- Core integration + pinned versions: `docs/openvpn-core-integration.md`
- Security model: `docs/security.md`; release process: `docs/release.md`;
  performance: `docs/performance.md`
- Legal/store: `docs/legal/privacy-policy.md`,
  `docs/legal/play-store-vpn-disclosure.md`,
  `docs/compliance/release-checklist.md`
- Architecture: `docs/architecture.md`, `docs/architecture/backend-control-plane.md`,
  `docs/architecture/ios-plan.md`
- CI: `.github/workflows/android.yml`, `.github/workflows/security.yml`,
  `.github/dependabot.yml`; scripts: `scripts/check-16kb-alignment.sh`,
  `scripts/generate-sbom.sh`
- Code entry points: `vpn/MyVpnService.kt`, `vpn/VpnController.kt`,
  `vpn/EngineEventMapper.kt`, `vpn/PauseRetryPolicy.kt`,
  `vpn/DnsLeakPolicy.kt`, `vpn/SplitTunnelPolicy.kt`,
  `config/ProfileTrustPolicy.kt`, `config/ProfileSecurityPolicy.kt`,
  `app/src/main/cpp/CMakeLists.txt`, `app/src/main/cpp/native_bridge.cpp`,
  `app/src/main/cpp/engine_adapter/ovpn_client.cpp`
