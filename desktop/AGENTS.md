# AGENTS.md — desktop client handoff

> Read this before changing `desktop/`. The Android client has its own handoff
> in [`../android/AGENTS.md`](../android/AGENTS.md); the monorepo overview is in
> [`../AGENTS.md`](../AGENTS.md).

## Status snapshot (2026-11-17)

| Item | Value |
|---|---|
| Product | Production-minded Linux/Windows/macOS OpenVPN client in Go + MyGo native UI |
| Version | 1.0.0 (`mygo.json`) |
| Branch | `feat/desktop-production` (PR #37; android/desktop restructure + desktop app) |
| Unit tests | **102 test functions / 15 packages / 0 failures** (`go test ./... -count=1`) |
| Vet/build | `go vet ./...`, `go build ./...` clean |
| Packaging | `./scripts/build-helpers.sh && go tool mygo build` → Linux binary + `.deb`/`.rpm`/tarball (helper included), Windows NSIS setup (helper included), macOS app/dmg (helper in `Contents/Resources`) |
| Cross-compile | `GOOS=windows/amd64`, `GOOS=darwin/arm64`, `GOOS=linux/arm64` all build clean; helpers for linux/windows/darwin amd64+arm64 are built by `scripts/build-helpers.sh` |
| Live tunnel | **PASS**: local OpenVPN 2.7 server in a server netns, client in a client netns; TLS 1.3, cert auth, `CONNECTED`, IP `10.8.0.2`, ping through tunnel, counters, redacted logs, clean disconnect (`scripts/test-live-tunnel.sh`) |
| Parity additions | System tray + minimize-to-tray, captive-portal probe/pause/sign-in/reconnect, start-at-login (XDG/Run key/LaunchAgent) + auto-connect after unlock (no stored password), Linux per-app split tunneling (cgroup v2 + nftables/policy routing, include/exclude, new-process monitor, DNS resolver pinning), Windows service + macOS launchd helper with token-authenticated loopback jobs, one-click helper install, signed `desktop-v*` updates |
| Headless launch | Xvfb + openbox creates the 1120×800 `"OpenVPN Desktop"` window; MyGo's GL surface makes raw screenshots unreliable, so the viewport test is authoritative |
| Not done | Independent pen test/audit; real Windows/macOS device tunnel/reboot/upgrade tests; live code signing/notarization with production certificates |
| Next action | Run `docs/windows-macos.md` on real Windows/macOS, attach certificates/secrets, publish `desktop-v1.0.0` draft, then external audit (`docs/release.md`) |

## Non-negotiable security invariants

1. Never disable certificate verification, add an “accept untrusted” setting, or
   accept a profile without a CA/`tls-remote`.
2. Keep `remote-cert-tls server` enforced at import **and** connect.
3. The GUI stays unprivileged. Only the helper runs as root, and it must:
   - sanitize the profile itself (do not trust the GUI),
   - build OpenVPN's argv itself,
   - resolve tools only from root-owned, non-writable absolute paths,
   - never pass the user's environment or `PATH` to OpenVPN.
4. Keep the fail-closed DNS default. A missing resolver with “Require tunnel
   DNS” on must abort the connection. Windows uses `--block-outside-dns`
   (TAP-Windows6); macOS uses `networksetup`.
5. Never log credentials, keys, profiles or static-challenge responses. Keep
   `internal/security` markers in sync with any new sensitive option.
6. The vault is Argon2id + AES-256-GCM, atomic `0600` writes in a `0700`
   directory. Do not add plaintext settings/profile files.
7. Keep the Windows token files/directories on explicit SID-based DACLs and
   the macOS system config/helper/OpenVPN copy root-owned. The daemon must never
   execute a user-writable binary.
8. Update manifests/archives are signed with the Ed25519 key whose public half
   is in `mygo.json`; never commit the private key or ship an unsigned tag.
9. The kill switch is opt-in, removed on normal exit, and recoverable with
   `openvpn-desktop-helper cleanup`. Do not claim system-wide protection when it
   is off.
10. Split tunneling/compression/cipher opt-ins must stay explicit and warned.
11. No analytics, telemetry, crash reporter, account or backend. The only
   optional request is the public-IP check to the configured URL.

## Commands

```bash
cd desktop
export PATH=$PATH:/usr/local/go/bin
go test ./... -count=1
go test -race ./... -count=1
go vet ./...
go build ./...
./scripts/build-helpers.sh
python3 scripts/gen-sbom.py -o build/sbom.cdx.json
go tool mygo build

# helper only
go build -trimpath -o bin/openvpn-desktop-helper ./cmd/openvpn-desktop-helper
./bin/openvpn-desktop-helper doctor

# real tunnel (root, Linux, TUN)
sudo ./scripts/test-live-tunnel.sh

# per-app cgroup/nftables/policy routing (root, netns, cgroup v2)
sudo ./scripts/test-per-app-split.sh

# release build (SBOM + packages + signed updates when the key is set)
./scripts/build-helpers.sh
python3 scripts/gen-sbom.py -o build/sbom.cdx.json
MYGO_UPDATER_PRIVATE_KEY="$(cat .keys/mygo-update.key)" go tool mygo build

# Windows/macOS builds and service installers
GOOS=windows GOARCH=amd64 go build ./...
GOOS=darwin GOARCH=arm64 go build ./...
# Windows (elevated): scripts/install-windows-service.ps1
# Windows service smoke test: scripts/test-windows-service.ps1
# macOS: sudo scripts/install-macos-service.sh
# macOS service smoke test: sudo scripts/test-macos-service.sh

# install/remove the root helper
sudo ./scripts/install-helper.sh [auto|polkit|sudo|setuid]
sudo ./scripts/uninstall-helper.sh
```

## Architecture quick map

| Concern | File |
|---|---|
| Window, menus, data dir, helper discovery | `main.go` |
| Service, snapshot, retry supervisor, public IP | `internal/app/service.go` |
| Import/dedupe/CA-change warnings | `internal/app/importer.go` |
| Vault | `internal/vault/vault.go` |
| Import/trust/cipher/compression/MTU/split policies | `internal/profile/*.go` |
| DNS fail-closed decisions | `internal/dnspolicy/policy.go` |
| Captive-portal probe/URL safety | `internal/captiveportal/portal.go` |
| XDG start-at-login | `internal/autostart/autostart.go` |
| OpenVPN process + management | `internal/ovpn/{session,management,retry,privilege}.go` |
| Root helper, DNS script, kill switch | `internal/helper/{helper,dns,killswitch,exec}.go` |
| Privileged service (Win/Mac) | `internal/helper/service.go`, `internal/serviceproto/`, `internal/winhelper/`, `cmd/openvpn-desktop-helper/main_windows.go` |
| GUI service client | `internal/ovpn/service_session.go`, `internal/ovpn/privilege.go` |
| Per-app cgroup/nft/policy routing | `internal/helper/split_linux.go` |
| Running-process discovery | `internal/perapp/scan_linux.go` |
| UI (unlock/Home/Profiles/Logs/Settings) | `internal/appui/{app,view}.go` |
| Tests | `*_test.go`; live test `internal/app/live_test.go` (`integration` tag) |

Data flow: UI → service → vault/profile → runtime config → helper → OpenVPN →
management/process output → snapshot/logs → UI. The UI polls snapshots; never
mutate view state from a background goroutine directly.

## Gotchas

- **MyGo flex layout:** `Fill()` on a nested row/card consumes the remaining
  height and pushes content out of view. Use `WidthPercent(100)` for nested
  width; keep `Fill()` only on the root column. `TestContentIsInsideTheViewport`
  guards this.
- **Management state:** `state on` only reports future transitions. The client
  sends `state` and polls it; the one-shot response is a **bare CSV line**, not
  `>STATE:`. Keep both parsers.
- **Port/firewall in tests:** the live test uses UDP 1194 because many hosts
  only allow that. It runs the server in its own netns so host firewall rules
  cannot drop tunnel ICMP.
- **Helper logout:** the helper execs/forwards signals to OpenVPN; a SIGKILL can
  leave the kill switch table behind. `cleanup` is the recovery path.
- **DNS in namespaces:** `resolvectl`/`resolvconf` may not apply inside a test
  netns, so the live test disables “Require tunnel DNS”. Do not read that as the
  fail-closed path being untested; `internal/dnspolicy` unit tests cover it.
- **Credentials temp files:** they live only for the session; overwrite and
  delete them together with the session directory.
- **OpenVPN option blacklist:** add newly discovered code-loading/script options
  to `internal/profile/sanitize.go`. Polkit/sudo is safer than setuid.
- **Per-app split:** the helper creates a private cgroup v2 tree, marks with
  nftables, and adds fwmark 0x1 policy rules/table 51820. Keep the VPN process
  in the `vpn` cgroup (include mode), keep the system resolver on the VPN path,
  restore resolver cgroups on stop, and fail the connection if setup fails.
  New processes are matched by `/proc/PID/exe` with a one-second monitor;
  wrapper scripts match the interpreter. The kill switch allows mark 0x1 only
  when per-app split is active.
- **Windows/macOS service:** the GUI sends a token-authenticated job to the
  service; the service runs `helper run` as a child. Never log the token or
  credentials, keep the root config SYSTEM/Administrators or root 0600, keep the
  Windows temp DACL and macOS `0700` dirs, and use `--block-outside-dns` on
  Windows / `networksetup` on macOS. Kill switch/per-app are Linux-only and must
  stay hidden/rejected elsewhere. Real-device tests and signing are still
  required.
- **Captive portal:** the probe treats a redirect or 200 as a portal; keep
  `--dns-updown disable` so OpenVPN cannot apply DNS behind the policy. Never
  open a portal URL without `captiveportal.ValidatePortalURL`.
- **Tray:** if `NewTray` fails there is no StatusNotifier host; do not hide the
  window on close in that case. A real quit must set `forceQuit`/call `Exit`.
- **Autostart:** never store the master password in the XDG entry. The entry
  runs `--autoconnect`, which waits for unlock. Keep the executable path
  absolute and quote it for the `Exec` key.

## Verification before calling a change done

- `go test ./... -count=1` (all packages) and `go vet ./...`.
- `go build ./...` and `go tool mygo build`.
- If the helper/argv/sanitizer changed: `sudo ./scripts/test-live-tunnel.sh`.
- If per-app split changed: `sudo ./scripts/test-per-app-split.sh` (real
  cgroup/nft/ip rules in a netns), plus `nft -c`/unit rule tests.
- If the service changed: `go test ./...` (protocol), the service test inside
  `sudo ./scripts/test-live-tunnel.sh`, and `GOOS=windows`/`GOOS=darwin` builds.
  Device testing must follow `docs/windows-macos.md`.
- If the UI/layout changed: `TestContentIsInsideTheViewport` and a manual
  Xvfb+openbox launch. If tray/portal/autostart changed, run the manual
  checklist in `docs/testing.md` (StatusNotifier host, real portal, logout/login).
- If a security policy changed: update `SECURITY.md`, `docs/architecture.md`,
  `docs/testing.md`, `docs/parity.md` and the relevant unit tests.
