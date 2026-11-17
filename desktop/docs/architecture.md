# Architecture — OpenVPN Desktop

## Layers

```
main.go
  │  window, menus, data directory, helper discovery
  ▼
internal/appui (MyGo native UI)
  │  App state, unlock, Home/Profiles/Logs/Settings, dialogs, notifications
  ▼
internal/app (service)
  │  vault, profiles, import, session supervisor, retry, logs, public IP
  ▼
internal/ovpn            internal/helper (root)
  │  Session/process       │  sanitize, argv, DNS up/down, kill switch
  ▼                        ▼
openvpn 2.7+ (management interface)
```

Supporting pure packages: `internal/profile` (import/trust/cipher/compression/
MTU/split policies), `internal/dnspolicy`, `internal/perapp`,
`internal/captiveportal`, `internal/autostart`, `internal/serviceproto`,
`internal/winhelper` (Windows), `internal/security`, `internal/settings`,
`internal/vault`, `internal/model`.

No package except the helper changes the host's network configuration. The GUI
is safe to run as the user.

## Connect sequence

1. The user selects a profile and presses **Connect**.
2. `app.Service.Connect`:
   - loads the encrypted profile and credentials from the vault;
   - validates credentials (username/password, static challenge);
   - calls `profile.BuildRuntimeConfig`, which:
     - re-validates the trust anchor (`CA` or `tls-remote`);
     - sanitizes scripts/plugins/management/external refs;
     - enforces `remote-cert-tls server`;
     - applies MTU, compression, AEAD-only and split-tunnel policies;
     - adds the DNS `setenv` values and bounded reconnect options;
   - sets the snapshot to `preparing`, then starts `runLoop` in a goroutine.
3. `runLoop` calls `ovpn.Start`:
   - writes `profile.ovpn`, credentials and the management password to a
     `0700` session directory (files `0600`);
   - finds or asks for a free loopback port;
   - launches the helper through pkexec/sudo/direct;
   - the helper re-sanitizes the profile, writes a root-owned copy, installs the
     kill switch if requested, and starts OpenVPN with a controlled argv;
   - the GUI connects to the management interface, authenticates, enables
     `state on`/`bytecount 1`, queries `state` and polls it every 2 s.
4. Management events update the snapshot: `ADD_ROUTES` → `connecting`,
   `CONNECTED` → `connected`, `RECONNECTING` → `reconnecting`, auth failure →
   `auth-failed`, `EXITING` → `disconnecting`.
5. Process output is read line by line, sanitized, and appended to the in-memory
   log ring. The helper's `dns-up` prints
   `[openvpn-desktop] dns=... fallback=0|1`, which becomes the DNS details.
6. On disconnect, the GUI sends `signal SIGTERM` through management, waits up to
   10 s, then kills the process. The helper removes the kill switch and restores
   DNS. Session files are overwritten and deleted.

## State machine

```
disconnected
  └─ Connect ─► preparing ─► connecting ─► connected
                              │  ▲             │
                              │  └─ retry ◄────┘ (process exit / lost)
                              ├─► reconnecting ─┐
                              ├─► auth-failed   │ (no retry)
                              └─► error ◄───────┘ (budget exhausted / policy)
  any ─ Disconnect ─► disconnecting ─► disconnected
```

Retry policy (`internal/ovpn/retry.go`): 5 s initial delay, doubling to 60 s,
jittered in `[base/2, base]`, at most 6 attempts. Attempts reset only on
`CONNECTED`. Policy errors (untrusted profile, AEAD-only mismatch, missing
helper, DNS refusal, certificate failure, auth failure) fail immediately; a
network loss is retried. The UI never says “protected” while reconnecting; only
OpenVPN's `CONNECTED` state does.

## Management protocol

`internal/ovpn/management.go` speaks OpenVPN 2.x's TCP management protocol:

- server prompt `ENTER PASSWORD:` → send the password;
- `state on`, `bytecount 1`, then `state` and a 2 s `state` poll;
- notifications `>STATE:...`, `>BYTECOUNT:...`, `>LOG:...`;
- the one-shot `state` command returns a **bare CSV line** (not `>STATE:`),
  e.g. `1794865860,CONNECTED,SUCCESS,10.8.0.2,192.168.123.1,1194,,`, followed by
  `END`. The parser handles both forms; without the bare form a management
  connection opened after `CONNECTED` would leave the UI stuck in “connecting”.

The management endpoint binds to `127.0.0.1` with a random per-session password
file (`0600`). The GUI reads the same file. No other local process can control
the session without the password.

## DNS helper

`cmd/openvpn-desktop-helper dns-up` is OpenVPN's `--route-up` hook. The helper
starts OpenVPN with `--dns-updown disable`, so OpenVPN 2.7's own
`/usr/libexec/openvpn/dns-updown` cannot apply a pushed resolver independently
of this decision. It reads `foreign_option_N`, `ifconfig_local`,
`ifconfig_ipv6_local`, `dev` and the `OPENVPN_DESKTOP_*` policy variables, calls
`internal/dnspolicy.Decide`, and:

- refuses (exit 1) when the policy says the connection would leak;
- otherwise applies resolvers through `resolvectl` (when
  `/run/systemd/resolve` exists), `resolvconf`, or a direct `/etc/resolv.conf`
  write (never following a symlink);
- prints the chosen resolvers for the GUI;
- `dns-down` reverts the link, removes the resolvconf stanza, or restores the
  saved `/etc/resolv.conf`, and also removes a stale kill-switch table.

## Windows/macOS privileged service

Linux launches a root-owned helper process. Windows and macOS instead install a
privileged **service** (`internal/helper/service.go`):

1. The GUI creates a random management password and loopback port, connects to
   the service endpoint and sends a `serviceproto.Job` containing the hardened
   config, credentials, management endpoint and policy flags.
2. The service checks the 256-bit token in constant time, writes the job to a
   protected temp directory, and starts `helper run` as a child. The child
   re-sanitizes the profile and builds OpenVPN's argv.
3. The service streams child stdout/stderr as redacted JSON `log` messages. The
   GUI dials OpenVPN's management interface and maps state as in Linux.
4. A stop message or disconnect sends SIGTERM (Unix) or kills the process tree
   (Windows); the child cleans up, and the service returns an `exit` message.

Windows: `internal/winhelper` installs/runs `OpenVPNDesktopHelper` as
LocalSystem; `cmd/openvpn-desktop-helper/main_windows.go` provides
`install-service`/`uninstall-service`/`service`. DNS uses TAP-Windows6 with
`--block-outside-dns --register-dns`.

macOS: launchd runs `openvpn-desktop-helper serve --config ...` as root;
`dns_darwin.go` applies pushed resolvers with `networksetup` to the primary
service and restores them on `dns-down`. The helper requires a root-owned
OpenVPN (Homebrew is accepted only when not group/world writable).

Guest/host transport is a token-authenticated loopback TCP connection, not a
platform-native pipe/XPC, so the same protocol and tests cover both platforms.
The shared service core and GUI client are exercised by
`TestLiveTunnelService` on Linux; Windows/macOS device verification is listed in
[`windows-macos.md`](windows-macos.md).

## Per-app split tunneling (Linux)

The service passes `PerAppMode` and the selected absolute `/proc/PID/exe` paths
to the helper; the helper passes them to `startSplit` after OpenVPN starts.
`internal/helper/split_linux.go`:

1. creates `/sys/fs/cgroup/openvpn-desktop-<helper-pid>/apps` and `.../vpn`;
2. moves the OpenVPN PID into `vpn` (include mode) and verifies the move;
3. reads the non-VPN default route(s) (`ip route show default`, skipping
   `tun*`/`tap*`) for IPv4/IPv6;
4. installs `table inet openvpn_desktop_split`:
   - **exclude**: `meta nfproto ipv4 meta cgroup <apps> meta mark set 0x1`
     (and the IPv6 equivalent when a physical IPv6 default exists);
   - **include**: `oif "lo" accept`, `meta cgroup <vpn> accept`,
     `meta cgroup <apps> accept`, then `meta mark set 0x1` for each family with
     a physical default;
5. adds `fwmark 0x1/0x1 table 51820` with a per-session priority (20000–29999,
   so cleanup cannot delete an unrelated user rule) and a `default via <gw>
   dev <dev> table 51820` route per family;
6. scans `/proc` every second, moving matching executables into `apps` and (in
   include mode) known resolver processes into `vpn`, recording the resolver's
   original cgroup for restoration;
7. refreshes the physical policy route every five seconds if the non-VPN
   default changes.

`Stop` removes the nft table, rules and table routes, restores resolver
cgroups, evacuates the cgroups to the root, and removes the private tree. A
setup failure aborts the connection. `internal/perapp` is the shared
`/proc` scanner and canonicalizer (the UI uses it for the running-application
picker).

DNS: system resolver processes are kept on the VPN path in include mode; apps
that resolve directly follow their own cgroup. The UI documents the one-second
new-process window and the wrapper-script/interpreter caveat.

## Captive portal

`internal/captiveportal` performs one explicit HTTP probe (default
`http://connectivitycheck.gstatic.com/generate_204`). A 204 is internet, a
redirect or 200 is a portal, and a transport error is offline. Redirects are
not followed; the `Location` is resolved against the probe URL and validated as
http/https before the UI opens it. The service exposes `CheckPortal`,
`PauseForPortal` and `ResumeFromPortal`:

- `PauseForPortal` disconnects, sets `snapshot.PausedForPortal = true` and logs
  that traffic is not protected. The UI shows a warning banner and never
  renders “Protected”.
- `ResumeFromPortal` probes again and only calls `Connect` after a 204.
- `AutoPortalDetect` (default on) runs at most one probe per connection attempt
  after a failure; it is not a continuous monitor.

The probe URL and the automatic check are in Settings; both are optional and
disclosed in `SECURITY.md`.

## Tray and startup

The window has an embedded 1024 px app icon scaled to 32 px for the tray. The
tray menu is Show Window / Connect / Disconnect / Check Public IP / Quit.
`MinimizeToTray` prevents the window-close event and hides the window; if tray
creation failed (no StatusNotifier host), the app does not hide, so the user is
never stranded. A real quit sets `forceQuit` (via `OnBeforeQuit`) and calls
`Application.Exit`.

`internal/autostart` writes
`$XDG_CONFIG_HOME/autostart/openvpn-desktop.desktop` with
`Exec="<absolute app path>" --autoconnect`. It stores no password: the app
opens locked, and the pending auto-connect runs after `attach`/unlock. The
`--autoconnect` flag and `OPENVPN_DESKTOP_AUTOCONNECT=1` do the same for
headless launches.

## Kill switch

`internal/helper/killswitch.go` generates an nftables table
`inet openvpn_desktop_ks` with an output chain policy `drop`, allowing:

- `lo`, `tun*`, `tap*`;
- established/related connections;
- DHCP (68→67) and DHCPv6 (546→547);
- IPv6 neighbour discovery and essential ICMPv6;
- the VPN server's IPv4/IPv6 addresses (resolved before installation and pinned
  in the profile).

It is off by default. Normal exit removes it; `openvpn-desktop-helper cleanup`
recovers from a crash. The helper's `doctor` command lists the trusted tools.

## Vault

See [`../SECURITY.md`](../SECURITY.md). In code:
`internal/vault/vault.go` — Argon2id → AES-256-GCM, magic/version AAD, atomic
`0600` write in a `0700` directory. The service reads profiles/credentials
through the vault's mutex-protected API; the UI never touches the file.

## Importer

`internal/app/importer.go` + `internal/profile`:

- accepts one or more selected files; `.ovpn` files are profiles, the rest are
  companions matched by base name;
- inlines referenced certificate/key files, rejects missing/oversized ones;
- parses metadata (remote, protocol, auth, static challenge, inline material);
- rejects TAP, `config` includes, plugins and external PKI without user/pass;
- removes legacy-only TLS 1.2 cipher pins, appends `remote-cert-tls server`,
  enforces the trust anchor, sanitizes scripts, and hashes the result;
- deduplicates by hash (including the pre-hardening hash), warns on a same-named
  CA change, legacy ciphers, and ignored peer-fingerprint;
- stores the profile encrypted and selects it.

## UI

`internal/appui` is a single MyGo window with an unlock screen and four tabs.
The view polls thread-safe service snapshots every 750 ms (`Context.After`);
background work never mutates view state directly. The layout uses
`WidthPercent(100)` for nested rows/cards rather than `Fill()`, because a
nested `Fill()` in MyGo's flex layout consumes the remaining height and pushes
the page content out of view; `TestContentIsInsideTheViewport` pins this.

## Packaging

`mygo.json` names the app, identifier and version. `go tool mygo build` emits a
Linux binary, icons, `.deb`, `.rpm`, tarball and `install.sh` under
`build/linux-amd64/`. The app's own installer script installs the helper; the
MyGo package installs the GUI. Both are needed for a working connection.
