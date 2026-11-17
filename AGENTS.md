# AGENTS.md — monorepo handoff

> Read this first. Each client has its own detailed handoff:
>
> - **Android:** [`android/AGENTS.md`](android/AGENTS.md) — product status,
>   toolchain, CI, security invariants, device/store work.
> - **Desktop:** [`desktop/AGENTS.md`](desktop/AGENTS.md) — architecture,
>   helper powers, tests, live-tunnel evidence, gotchas.
>
> This file only describes the monorepo layout and cross-cutting rules.

## Status snapshot (2026-11-17)

| Item | Value |
|---|---|
| Branch | `feat/desktop-production` (PR #37; restructure + desktop client) |
| Android | Moved to `android/`; Gradle project root is now `android/`; native submodule is `android/third_party/openvpn3`; Android CI uses `working-directory: android` |
| Desktop | `desktop/` Go + MyGo client, 1.0.0; 102 tests / 15 packages; live tunnel PASS; Linux/Windows/macOS packages with bundled helper; signed update feed configured |
| Shared CI | `.github/workflows/android.yml`, `.github/workflows/desktop.yml`, `.github/workflows/desktop-release.yml`, `.github/workflows/security.yml` (Android SBOM), Dependabot for Gradle/Go/Actions |
| Root docs | `README.md` (overview), this file, `LICENSE`; detailed docs live in each client |

## Layout

```
android/
  app/                  Kotlin + Compose + JNI
  third_party/openvpn3  pinned submodule (do not modify)
  scripts/              native deps, 16 KB gate, SBOM
  docs/                 ADRs, security, legal, architecture
  gradlew, gradle/, build.gradle.kts, settings.gradle.kts
desktop/
  main.go               MyGo window/menus
  cmd/openvpn-desktop-helper/
  internal/             app, appui, profile, dnspolicy, ovpn, helper, vault, ...
  scripts/              install-helper, test-live-tunnel
  docs/                 architecture, testing, parity
  mygo.json, go.mod, go.sum
.github/workflows/      android.yml, desktop.yml, security.yml
```

## Working rules

- Keep the Android and desktop security policies in sync when a rule changes
  (trust anchor, `remote-cert-tls server`, DNS fail-closed, compression/cipher
  modes, MTU, split-tunnel warnings, log redaction).
- Never weaken verification or add an “accept untrusted” path in either client.
- Do not modify `android/third_party/openvpn3`; Android core fixes are generated
  header patches in `android/app/src/main/cpp/CMakeLists.txt`.
- The desktop GUI stays unprivileged; only `desktop/cmd/openvpn-desktop-helper`
  runs as root, and it must re-sanitize profiles and control OpenVPN's argv.
- No secrets, credentials, private keys or profiles in logs, commits or CI.
- No analytics/backend in either client.
- Do not push, merge, edit issues/PRs or add closing references unless the user
  asks. Local builds/tests are fine.

## Verification

```bash
# Android (from android/)
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

# Desktop (from desktop/)
go test ./... -count=1
go vet ./...
go build ./...
go tool mygo build
sudo ./scripts/test-live-tunnel.sh     # root, TUN, netns
```

Android CI runs `android.yml`; desktop CI runs `desktop.yml`; the scheduled
`security.yml` scans the Android SBOM and repository secrets. The desktop live
tunnel test is not run in CI (it needs root/TUN/netns) and is documented as a
manual gate.

## Known gaps

- Android: physical-device leak matrix, 16 KB device launch, store/legal
  publication, independent pen test (see `android/AGENTS.md` §7–§8).
- Desktop: independent audit/pen test; real Windows/macOS device tunnel,
  reboot/upgrade/uninstall tests; production code signing/notarization with
  the maintainer's certificates. Linux per-app split tunneling (cgroup v2 +
  nftables/policy routing), tray, captive-portal pause/sign-in, start-at-login
  on all three platforms, one-click helper install, and signed `desktop-v*`
  updates are implemented. See `desktop/docs/release.md`.
- Neither client has a backend/control plane or iOS implementation; those are
  documented scope decisions in `android/docs/architecture/`.
