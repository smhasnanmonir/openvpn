# Testing and verification — OpenVPN Desktop

## Automated tests

Run:

```bash
cd desktop
go test ./... -count=1
go vet ./...
go build ./...
```

Current result: **102 test functions in 15 packages, all passing** on Linux
(2026-11-17). The Windows-only autostart test also runs in the Windows CI job;
the live-tunnel integration tests are separate and root-only.

| Package | What is covered |
|---|---|
| `internal/profile` | Trust anchor decisions, `remote-cert-tls server`, CA fingerprint stability, legacy cipher findings, AEAD-only transform/validation, strict compression, MTU override, dangerous-directive sanitization, external-file inlining, metadata parsing, runtime config build, split-tunnel routes, warnings. |
| `internal/dnspolicy` | Literal-only resolver validation, octal/loopback/link-local rejection, fallback parsing, pushed/fallback/refusal decisions, address-family filtering, `foreign_option` parsing. |
| `internal/vault` | Create/open round-trip, `0600` mode, wrong password, missing vault, duplicate create, password change, lock, profile deletion removes credentials, short-password rejection. |
| `internal/ovpn` | Management state/bytecount/log parsing (including the bare `state` CSV line), state mapping, retry backoff/jitter/budget, SCRV1 credentials, log classification. |
| `internal/helper` | Kill-switch nftables rules (including the per-app mark allowance), run-argument validation (loopback/port/DNS/per-app/unknown flags), remote-IP pinning, managed `setenv` stripping, trusted-binary lookup, per-app nft rule generation, default-route parsing. |
| `internal/perapp` | Executable matching with ` (deleted)` normalization, `/proc` scanning of the test binary. |
| `internal/security` | Credential/key log redaction and truncation. |
| `internal/app` | Import, dedupe, trust rejection, companion inlining, delete, synchronous policy errors, settings validation, fatal-error classification, captive-portal probe/detection/pause behaviour. |
| `internal/captiveportal` | URL validation, 204 internet, redirect/portal URL resolution, 200 portal page, offline, unsafe probe/portal URL rejection. |
| `internal/autostart` | Linux XDG entry generation/remove/quoting; Windows per-user `Run` key install/remove (Windows CI); macOS LaunchAgent install/remove (macOS CI). |
| `internal/serviceinstall` | Helper option normalization, protected copy mode, token reuse across upgrades, repair of an invalid system config. |
| `internal/serviceproto` | Service config validation/round-trip, Unix file mode, token/loopback checks. |
| `internal/ovpn` service client | Service endpoint discovery, missing config, env endpoint, no local command for service mode, bundled-helper discovery, install refusal without pkexec. |
| `internal/settings` | Defaults/normalization, portal URL validation, CIDR parsing/validation. |
| `internal/appui` | Unlock screen, vault creation through the view, tabs, profile rendering, wrong password, warnings, and **viewport layout** (Home/Settings/Logs content inside the window with non-zero size). |
| root | Data-directory override. |

`TestContentIsInsideTheViewport` is important: MyGo's flex layout treats
`Fill()` on a nested row/card as “consume all remaining height”, which pushed
the page content out of view. The test uses `ui.Tester.Find` to assert the Home,
Settings and Logs content is inside the 1100×780 viewport.

## Real tunnel test

```bash
cd desktop
sudo ./scripts/test-live-tunnel.sh
```

Requirements: Linux, root (CAP_NET_ADMIN + `/dev/net/tun`), `openvpn`,
`openssl`, `ip`, Go. The script:

1. creates a throwaway CA, server and client certificate, an ECDH DH file and a
   `tls-crypt` key;
2. creates **two** network namespaces connected by a veth pair
   (`192.168.123.1` server, `192.168.123.2` client) and runs the OpenVPN server
   in the server namespace, so the host firewall cannot interfere;
3. builds the privileged helper;
4. invokes `dns-up` with no pushed resolver and asserts that the fail-closed
   policy refuses the connection with an explanatory message;
5. runs `TestLiveTunnel` inside the client namespace with `-tags integration`.

The test imports the generated client profile, sets `Require tunnel DNS` off
(the test namespace has no systemd-resolved), connects, waits for `CONNECTED`,
asserts the tunnel address `10.8.0.2`, pings `10.8.0.1` through the tunnel,
waits for non-zero byte counters, checks that no certificate/private-key
material reached the logs and that OpenVPN's own `dns-updown` script did not run
(it is disabled so it cannot bypass the fail-closed policy), disconnects and
waits for `DISCONNECTED`.

Recorded result (2026-11-17, Linux 7.0.0 x86_64, OpenVPN 2.7.0, OpenSSL 3.5.5):

```
==> checking the fail-closed DNS refusal
    refused as expected
=== RUN   TestLiveTunnel
    live_test.go:84: connected: ip=10.8.0.2 server=192.168.123.1:1194 stats={BytesIn:5042 BytesOut:4823 PacketsIn:0 PacketsOut:0}
    live_test.go:97: disconnected cleanly
--- PASS: TestLiveTunnel (5.69s)
PASS
==> live tunnel verification passed
```

This verifies in one run: profile import and hardening, helper argv/sanitization,
management authentication and the bare `state` query, OpenVPN TLS 1.3 with
certificate verification, `CONNECTED` mapping, the tunnel address, data-plane
forwarding (ping), byte counters, log redaction and clean teardown.

## Windows/macOS privileged service test

The shared service protocol and GUI client are exercised on Linux by
`TestLiveTunnelService` inside `scripts/test-live-tunnel.sh`:

- starts `openvpn-desktop-helper serve --config <temp token config>` (the same
  service binary used by the Windows/macOS wrappers);
- connects the real GUI service client, sends a job, dials management and
  reaches `CONNECTED`;
- pings through the service-managed tunnel and disconnects cleanly.

Recorded result:

```
=== RUN   TestLiveTunnelService
    service_live_test.go:95: service-managed tunnel disconnected cleanly
--- PASS: TestLiveTunnelService (4.74s)
```

The Windows service wrapper (`internal/winhelper`, `cmd/.../main_windows.go`)
and macOS launchd/DNS code cross-compile; their installers and device checklist
are in [`windows-macos.md`](windows-macos.md). A real Windows/macOS tunnel,
code signing and notarization still require those machines.

### Hosted Windows/macOS installer smoke tests (2026-11-17)

The release workflow was dry-run on GitHub-hosted runners (unsigned, no VPN):

- **Windows:** `openvpn-desktop-setup-1.0.0-amd64.exe` built with the NSIS
  helper hook; `test-windows-service.ps1` installed the real
  `OpenVPNDesktopHelper` LocalSystem service, verified it was Running and wrote
  the per-user token, then stopped and removed it.
- **macOS:** the universal app and `.dmg` built with the helper in
  `Contents/Resources`; `test-macos-service.sh` installed the launchd daemon
  and root-owned OpenVPN copy, verified `launchctl print`, and removed it.
- **Linux:** race tests, `govulncheck`, SBOM, `.deb`/`.rpm`/tarball with the
  helper, and the helper-in-package checks passed.

This verifies the real Windows service and macOS launchd install/uninstall
paths on those operating systems. It does **not** verify a tunnel, DNS behavior,
reboot/upgrade, or code signing.

### Packaging evidence (2026-11-17, Linux host)

```
$ go tool mygo build -platform windows/amd64
  ✓ Created openvpn-desktop-setup-1.0.0-amd64.exe  13.0s
  ✓ Built OpenVPN Desktop 1.0.0
  OpenVPN Desktop.exe                      16.8 MB
  openvpn-desktop-setup-1.0.0-amd64.exe     6.6 MB
  openvpn-desktop-helper.exe                4.0 MB

$ go tool mygo build -platform linux/amd64
  $ dpkg-deb -c build/linux-amd64/*.deb | grep helper
  -rwxr-xr-x root/root 3788960 ./opt/openvpn-desktop/openvpn-desktop-helper

$ MYGO_UPDATER_PRIVATE_KEY=... go tool mygo build -platform linux/amd64
  ✓ Built OpenVPN Desktop 1.0.0
  update-linux-amd64.json 2009 bytes (signed manifest)
```

The NSIS installer includes `packaging/installer.nsh` with a UAC `runas` call
that installs the service; the macOS app contains the helper under
`Contents/Resources`. The helper's `install-service` path was exercised on
Linux (`install`/`uninstall` wrote and removed the polkit action and
`/usr/local/libexec/openvpn-desktop/openvpn-desktop-helper`).

## Per-app split tunneling test

```bash
cd desktop
sudo ./scripts/test-per-app-split.sh
```

The script creates a network namespace with a physical veth default and a dummy
`tun-test` device, binds the host cgroup tree at a normal path (the netns
remounts `/sys`), copies a `sleep` binary to a unique path, and runs
`-tags integration ./internal/helper`. It verifies, for both modes:

- existing and newly started selected processes are moved into the app cgroup;
- the nftables mark rule exists and the policy rule/table use `0x1`/51820;
- after the main default is pointed at the dummy VPN device, marked traffic
  resolves to the physical veth and unmarked traffic to the VPN device;
- the VPN process is exempt in the `vpn` cgroup (include mode) and a
  non-selected process is not moved;
- `Stop` removes the nft table, rules, routes and cgroups.

Recorded result (2026-11-17):

```
=== RUN   TestPerAppSplitInclude
[openvpn-desktop] per-app split: include, 1 app(s), families v4=true v6=false
--- PASS: TestPerAppSplitInclude (2.65s)
=== RUN   TestPerAppSplit
[openvpn-desktop] per-app split: exclude, 1 app(s), families v4=true v6=false
--- PASS: TestPerAppSplit (2.12s)
PASS
==> per-app split tunneling verification passed
```

DNS handling: in include mode the test moves host resolver processes into the
VPN cgroup and restores them on stop; selected apps' system DNS therefore stays
on the VPN path. Apps with their own DoH/DoT follow the per-app cgroup. The UI
documents the one-second new-process window and the wrapper-script caveat.

## Cross-compilation

The Go client is plain Go (no cgo), so the same source builds for other
platforms:

```bash
GOOS=windows GOARCH=amd64 go build ./...
GOOS=darwin GOARCH=arm64 go build ./...
GOOS=linux GOARCH=arm64 go build ./...
```

All three are clean. The privileged helper is a Linux/Unix component: on other
platforms it compiles but refuses to trust/run binaries (fail closed), and the
start-at-login entry reports “implemented for Linux”. Native Windows/macOS
service integration remains a separate platform task.

## Packaging and launch

```bash
go tool mygo build
```

Recorded output: `OpenVPN Desktop 1.0.0`, `build/linux-amd64/openvpn-desktop`
(12.1 MB), icons, `.deb` (5.2 MB), `.rpm` (5.2 MB), tarball, `install.sh`.

Headless launch check (Xvfb + openbox):

```bash
Xvfb :99 -screen 0 1280x900x24 +extension GLX +render -noreset &
DISPLAY=:99 openbox &
DISPLAY=:99 ./build/linux-amd64/openvpn-desktop &
```

The window `"OpenVPN Desktop"` is created at 1120×800. MyGo paints through a GL
surface; a plain X screenshot can be black or fail with `BadMatch`, so the
authoritative rendering check is the `ui.Tester` viewport test above. The app
runs without a GPU (MyGo logs “native UI draws without the GPU: there is none”)
and continues.

## Manual checklist (not automated)

Run on a real desktop with a real provider (or the local live-test server):

- [ ] First launch: create vault; reject a short/mismatched password.
- [ ] Unlock with the correct password; wrong password shows an error.
- [ ] Import a provider `.ovpn` with inline material and one with companion
      files; verify the profile metadata, warnings and encrypted storage.
- [ ] Import a profile with no CA and one with only `peer-fingerprint`; both are
      refused with actionable messages.
- [ ] Re-import the same profile: “already imported”, no duplicate.
- [ ] Import a same-named profile with a different CA: CA-change warning.
- [ ] Connect/disconnect; verify `CONNECTED`, tunnel IP, server IP/port, DNS,
      MTU, counters, notifications, clean disconnect.
- [ ] Wrong password: `AUTH_FAILED`, no infinite retry.
- [ ] Unreachable server: bounded reconnect then an explicit error; traffic is
      not claimed protected.
- [ ] Kill the OpenVPN process while connected: reconnect within the budget;
      after budget exhaustion, visible error.
- [ ] DNS: server without DNS + Require tunnel DNS on → connection refused;
      with a fallback → connects and shows “(fallback)”.
- [ ] MTU 1400/1280 on a path-MTU-limited network; large download completes.
- [ ] AEAD-only with a CBC-only profile → refused before connecting; switch to
      profile-default → connects.
- [ ] Strict compression with a provider that pushes LZO → refused explicitly;
      compatibility → connects.
- [ ] Split-tunnel exclude/include; verify the warning and the routes
      (`ip route`) and that excluded traffic uses the physical gateway.
- [ ] Per-app split: select a browser or another app, connect, and verify its
      traffic follows the include/exclude choice; start a new instance and
      verify it is moved within ~1 s; check system-resolver DNS follows the VPN;
      inspect `nft list table inet openvpn_desktop_split`, `ip rule` and
      `ip route table 51820`; disconnect and verify cleanup.
- [ ] Per-app + kill switch: bypassed packets are allowed by the mark rule;
      non-selected packets are still dropped.
- [ ] Kill switch: enable, connect, disconnect; verify no non-tunnel traffic
      escapes and the table is removed. `kill -9` the app, verify traffic is
      blocked, then `sudo openvpn-desktop-helper cleanup` restores it.
- [ ] Public IP check with and without the tunnel; verify the address changes.
- [ ] Captive portal: connect, check network, pause, open the sign-in page in a
      browser, sign in, press **I signed in — reconnect**; verify the tunnel
      stays down while paused and reconnects only after a 204.
- [ ] Portal URL safety: a `javascript:`/`file:` Location is not opened.
- [ ] Tray: enable minimize-to-tray, close the window, verify the app stays in
      the tray; Show/Connect/Disconnect/Quit work; without a tray host the
      window closes normally instead of disappearing.
- [ ] Start at login: install, log out/in, verify the app opens locked; enable
      auto-connect, unlock, verify the selected profile connects. Remove the
      entry and verify it no longer starts.
- [ ] Lock vault; verify the UI returns to unlock and connections stop.
- [ ] Delete a profile; verify its credentials are gone.
- [ ] Copy logs; verify credential/key lines are redacted.
- [ ] Light/dark/system theme; window resize; min size.
- [ ] Helper missing: the UI says so and Connect fails with a clear message.
- [ ] `pkexec` and `sudo` helper paths; reinstall and confirm `doctor`.

## Known unverified areas

- Independent penetration test / code audit (not performed).
- Real Windows and macOS tunnels and DNS behavior on those operating systems.
- Native per-app split tunneling on Windows/macOS (Linux-only by design;
  route-based split works everywhere).
- Captive-portal detection against a real hotel/airport portal (the probe and
  URL-safety logic are unit-tested, and the pause/reconnect flow is manual).
- Start-at-login under GNOME/KDE/XFCE autostart implementations (the XDG entry
  is generated and unit-tested).
- System-tray behaviour on desktops without a StatusNotifier host (the app
  falls back to closing the window and logging the tray error).
- Per-app split on a real desktop with a system resolver (the integration test
  runs in a namespace and exercises cgroup/nft/policy routing, not every
  desktop resolver), Wi-Fi↔cellular changes, and apps with several child
  processes/wrapper scripts.
- Real Windows and macOS tunnels, DNS behavior, upgrade/reboot behavior,
  code signing/notarization. The service installers themselves were
  smoke-tested on hosted runners. Use the checklists in
  [`windows-macos.md`](windows-macos.md); the shared service core and packaging
  are verified on Linux but the platform wrappers have not run on those devices
  from this host.
- Real 16 KB-page / mobile parity is Android-specific and covered by the
  Android test suite, not here.
