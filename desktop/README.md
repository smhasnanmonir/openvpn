# OpenVPN Desktop

A production-minded OpenVPN client for Linux, Windows and macOS, built in Go
with [MyGo](https://mygo.run/) native UI. It is the desktop sibling of the Android
client in [`../android`](../android): the same import, trust, DNS, MTU,
compression/cipher, split-tunnel and logging policies, adapted to a desktop
where OpenVPN runs as a privileged system process.

> **Status: 1.0.0 release candidate.** The GUI, encrypted vault, privileged
> helper, management integration, DNS fail-closed policy, kill switch,
> one-click helper install, signed updates and packaging are implemented. A
> real tunnel has been verified end-to-end on Linux against a local OpenVPN 2.7
> server (TLS 1.3, certificate authentication, ping through the tunnel,
> counters, clean disconnect). Windows and macOS cross-compile and their
> installers build; real-device tunnel tests, code signing/notarization and an
> independent audit remain release gates. See
> [`docs/testing.md`](docs/testing.md) and [`docs/release.md`](docs/release.md).

---

## Features

| Area | Desktop client |
|---|---|
| Profiles | Import `.ovpn` with inline or companion `ca`/`cert`/`key`/`tls-auth`/`tls-crypt` files; the companion files are inlined and the profile is stored encrypted. |
| Trust | A CA certificate or legacy `tls-remote` is **required**. `remote-cert-tls server` is enforced. A `peer-fingerprint`-only profile is refused. Same-name CA changes are flagged. |
| Credentials | Username/password, private-key password, and static-challenge response. The static-challenge response is used for one connection and never written to the vault. |
| Storage | One password-encrypted vault: Argon2id key derivation + AES-256-GCM, atomic writes, `0600` file in a `0700` directory. Profiles, credentials and settings are all inside the ciphertext. |
| Connection | System OpenVPN 2.7+ (`openvpn`), controlled over its management interface with state, byte counters, logs and bounded automatic reconnects. |
| DNS | Fail-closed: if the server pushes no usable resolver and **Require tunnel DNS** is on, the connection is stopped. Optional validated IPv4/IPv6 fallback resolvers. Applied through `resolvectl`, `resolvconf` or `/etc/resolv.conf`. |
| MTU | Automatic or 1500 / 1400 / 1280, with `pull-filter ignore tun-mtu` so the server cannot override it. |
| Compression | Compatibility (`allow-compression asym`) or Strict (`allow-compression no` + server-push rejection). |
| Ciphers | Profile default or AEAD-only (AES-GCM / ChaCha20-Poly1305), with a server-push rejection filter. |
| Split tunneling | **Per-app** include/exclude on Linux (cgroup v2 + nftables marks + policy routing), plus route-based include/exclude networks and optional LAN bypass. New processes are moved into the rule by a one-second monitor; explicit leak/DNS warnings are shown. |
| Kill switch | **Linux only:** opt-in nftables output policy that allows only loopback, the tunnel, DHCP, IPv6 neighbour discovery and the VPN server. A recovery command removes a stale table. |
| Captive portal | Explicit connectivity probe (204 = internet, redirect/200 = portal); **Pause VPN and sign in**, opens a validated http(s) sign-in page, then **I signed in — reconnect** checks the network and reconnects. The tunnel is never shown as protected while paused. |
| Tray and startup | System tray with Show/Connect/Disconnect/Quit, optional minimize-to-tray, start-at-login on Linux (XDG), Windows (Run key) and macOS (LaunchAgent), and auto-connect after the vault is unlocked (the master password is never stored). |
| Windows/macOS | Privileged helper **service**: Windows `OpenVPNDesktopHelper` (LocalSystem) and a macOS launchd daemon; token-authenticated loopback jobs, shared sanitization, OpenVPN management and log streaming. The helper is bundled with each installer and installed from the GUI with one administrator prompt. Windows DNS uses TAP + `--block-outside-dns`; macOS DNS uses `networksetup`. |
| Privacy | No analytics, crash reporter, account or backend. The only network request besides the VPN is the optional public-IP check (`https://api.ipify.org` by default). |
| Logs | In-memory 500-line ring buffer, credential/key lines redacted, copy/clear from the UI. Nothing is written to disk. |
| UI | Native MyGo UI: Home, Profiles, Logs, Settings; desktop notifications; tray icon; menu shortcuts; light/dark/system theme; signed auto-updates from `desktop-v*` releases. |

### Deliberate desktop differences

- **Privileges.** OpenVPN creates a TUN device and changes routes/DNS, which
  requires root. The GUI stays unprivileged. Linux launches a root-owned helper
  via polkit (`pkexec`), passwordless sudo or (discouraged) setuid; Windows uses
  a LocalSystem service and macOS a launchd daemon. The GUI offers a one-click
  **Install privileged helper** action that performs the single elevation
  prompt.
- **Per-app split tunneling** is implemented on Linux with cgroup v2,
  nftables marks and policy routing. New processes are moved into the rule by a
  one-second monitor. System-resolver DNS is kept on the VPN path; apps using
  their own DoH/DoT follow the per-app rule. See `SECURITY.md` for the caveats.
- **Windows/macOS** use a privileged helper service instead of a setuid helper.
  The GUI sends a token-authenticated job over loopback; the service runs the
  same sanitized `helper run` path. Real Windows/macOS tunnel verification is a
  release step; see [`docs/windows-macos.md`](docs/windows-macos.md).
- **Always-on/lockdown** is an Android platform feature. The desktop offers an
  explicit nftables kill switch instead; when it is off, the app does not claim
  system-wide protection.
- **Vault unlock** uses a master password (no desktop hardware Keystore
  equivalent is assumed); there is no biometric unlock.
- **Captive portals** use an explicit HTTP connectivity probe rather than an
  OS connectivity API. The app can pause the tunnel, open the portal's
  validated http(s) URL, and reconnect after a successful check. There is no
  background captive-portal monitor beyond the automatic check after a failed
  connection (which can be disabled).
- **OpenVPN engine.** Android embeds OpenVPN 3 Core; the desktop uses the
  system's OpenVPN 2.7+ binary. The same profile trust/DNS/cipher/MTU policies
  are enforced in Go before the helper runs.

### Platform support

- **Linux:** production target; per-app split tunneling, nftables kill switch,
  XDG start-at-login, root helper, live-tunnel verified. Packages include the
  helper and can install it through the GUI's polkit prompt.
- **Windows:** LocalSystem helper service, bundled OpenVPN 2.7.1 with
  TAP-Windows6 (installed during helper setup), start at
  login, tray/captive portal/public IP, route-based split; per-app/kill switch
  are Linux-only. The NSIS installer includes the helper and starts the service
  with UAC; the GUI offers the same install if it was skipped. Real-device
  tunnel verification remains.
- **macOS:** launchd privileged helper, root-owned OpenVPN copy,
  `networksetup` DNS, start at login, tray/captive portal/public IP, route-based
  split; per-app/kill switch are Linux-only. The app bundles the helper and
  installs it with one administrator prompt. Real-device tunnel verification
  remains.

Windows/macOS details, requirements and verification checklists:
[`docs/windows-macos.md`](docs/windows-macos.md).

---

## Requirements

- Linux, Windows 10/11 or macOS 13+.
- **OpenVPN 2.7+.** Linux: root-owned and not group/world writable. Windows:
  none needed up front. The helper install runs the bundled official OpenVPN
  2.7.1 installer, which also installs the TAP-Windows6 driver required for
  pushed DNS; an existing `C:\Program Files\OpenVPN` installation is reused.
  macOS: Homebrew or official; the installer copies it into a root-owned
  location.
- Go 1.27+ to build from source; the release installers do not need Go.
- Linux: `nft` for the kill switch (optional), `resolvectl` or `resolvconf` for
  DNS (or a writable `/etc/resolv.conf`), and `pkexec` (polkit) or `sudo`.
- Native UI: MyGo draws the UI itself (no cgo); Linux needs the GTK 3 runtime.

Windows and macOS details, installers and verification checklists:
[`docs/windows-macos.md`](docs/windows-macos.md). Release/signing steps:
[`docs/release.md`](docs/release.md).

---

## Build and run

```bash
cd desktop
go test ./...
go vet ./...
go build ./...

# bundle the helper for every target, then build the native app
./scripts/build-helpers.sh
go tool mygo build

# development (uses the helper in resources/ when present)
go tool mygo dev
```

Release flow, signing secrets and the platform verification checklist:
[`docs/release.md`](docs/release.md). Windows binaries can optionally be signed
with free [SignPath Foundation signing](docs/signpath.md); the
[`code signing policy`](../CODE_SIGNING.md) lists what would be signed.

The packages contain the GUI **and** the platform helper. On first connect,
open **Settings → Privileged helper** and press **Install privileged helper**.
The app asks for administrator approval once, installs the helper in a
privileged location and rechecks it without restarting. The helper writes its
version next to the installed binary; after an app update, Settings shows
**Update privileged helper** if the service is stale.

- **Linux:** the `.deb`/`.rpm` place the root-owned helper in
  `/opt/openvpn-desktop/`; the GUI uses `pkexec` to install a polkit action.
  The portable tarball's helper is user-owned, so the GUI refuses to elevate it;
  run `sudo ./scripts/install-helper.sh` once for that layout.
- **Windows:** the NSIS installer starts the LocalSystem service with UAC; if it
  is skipped, the GUI runs the bundled helper with `runas`.
- **macOS:** the GUI runs the bundled helper with `osascript` administrator
  approval, which installs a launchd daemon and a root-owned OpenVPN copy.

Terminal fallbacks (also useful for source builds):

```bash
# Linux
cd desktop && sudo ./scripts/install-helper.sh

# macOS
cd desktop && sudo ./scripts/install-macos-service.sh

# Windows (elevated PowerShell)
powershell -ExecutionPolicy Bypass -File desktop\scripts\install-windows-service.ps1
```

The Linux installer chooses polkit or passwordless sudo; `setuid` is available
for minimal systems but discouraged. See [`SECURITY.md`](SECURITY.md). Remove
the Linux helper with `sudo ./scripts/uninstall-helper.sh`; Windows and macOS
installers call the helper's `uninstall-service --purge` path.

Run the app:

```bash
./build/linux-amd64/openvpn-desktop
```

On first launch, choose a master password. Then import a `.ovpn` profile (and
its certificate/key files, if it references them) from **Profiles → Import
.ovpn**, enter credentials if the profile needs them, and press **Connect**.

For unattended launch, use `--autoconnect` (or
`OPENVPN_DESKTOP_AUTOCONNECT=1`) and **Install start at login** in Settings
(Linux XDG, Windows Run key, macOS LaunchAgent). The app opens locked; the
tunnel connects only after you unlock it. The master password is never stored.

Data lives in `$XDG_CONFIG_HOME/openvpn-desktop` (normally
`~/.config/openvpn-desktop`). `OPENVPN_DESKTOP_DATA_DIR` overrides it.
`OPENVPN_DESKTOP_PASSPHRASE` auto-unlocks an existing vault for automated
runs; do not use it on a shared machine.

---

## Verify

```bash
cd desktop
go test ./...                # 102 test functions across 15 packages
go vet ./...
go build ./...
go tool mygo build

# real tunnel: root, OpenVPN, TUN and two network namespaces
sudo ./scripts/test-live-tunnel.sh
```

```bash
sudo ./scripts/test-per-app-split.sh
```

The per-app test creates a network namespace with a physical veth default and a
dummy VPN device, binds the host cgroup tree, and runs the real cgroup/nftables/
policy-routing setup in both include and exclude modes. It checks existing and
newly started process membership, the nft mark rule, `ip rule`/table 51820,
marked-vs-unmarked route selection, the VPN process exemption, and cleanup.

The live test creates a throwaway CA/server/client, runs the server in its own
network namespace, runs the client inside another, and drives the real
service/helper/management path. It asserts `CONNECTED`, the `10.8.0.2` tunnel
address, a successful `ping` to `10.8.0.1` through the tunnel, moving byte
counters, no secret material in the logs, and a clean disconnect. It also runs
`TestLiveTunnelService`, which drives the Windows/macOS service protocol and GUI
client through a real `helper serve` process and tunnel. See
[`docs/testing.md`](docs/testing.md) for the recorded output and the manual
checklist.

---

## Using the app

### Home

Shows the honest state (`Disconnected`, `Connecting`, `Protected`,
`Reconnecting`, `Authentication failed`, `Error`), the profile, server, tunnel
IP, TUN device, DNS, MTU, byte counters and session duration. A warning card
lists every active leak surface (split tunneling, LAN bypass, kill switch,
DNS opt-out, compression compatibility, missing helper). The **Network /
captive portal** card can check connectivity, pause the tunnel for sign-in,
open the portal page, and reconnect. **Check now** performs the optional
public-IP lookup; **Check network** performs the captive-portal probe.

### Profiles

Import, select, edit credentials and delete profiles. Warnings from import
(legacy ciphers, ignored peer-fingerprint, CA change, removed script directives)
are shown on the profile card. The master password encrypts everything; deleting
a profile also deletes its credentials.

### Logs

OpenVPN's own log, with credential/key lines replaced by
`[redacted: sensitive log line]`. Filter, copy all, or clear. Logs are never
persisted.

### Settings

DNS fail-closed and fallback resolvers; MTU; compression; cipher policy;
nftables kill switch; automatic public-IP check and URL; theme; route-based split
tunneling and LAN bypass; per-app include/exclude with a running-application
picker and manual paths; captive-portal probe URL and auto-detect; start at
login, auto-connect after unlock, minimize-to-tray; helper status; vault
location/fingerprint; lock vault.

### Menus and notifications

- **File → Import Profile…** (`Ctrl+O`), **Lock Vault** (`Ctrl+L`), **Quit**.
- **Connection → Connect** (`Ctrl+Shift+C`), **Disconnect**
  (`Ctrl+Shift+D`), **Check Public IP**.
- Desktop notifications on connect, disconnect and failure.

---

## Security in one page

- The profile is untrusted. The importer and the helper both remove `up`,
  `down`, `route-up`, `plugin`, `config`, `management`, `script-security`,
  `auth-user-pass`, `askpass`, `engine`, `providers`, PKCS#11 providers,
  `tls-verify`, `client-connect`, `iproute`, and every `pull-filter` before
  OpenVPN sees the profile. The helper builds the argv (management endpoint,
  route-up/down scripts, `auth-nocache`, credentials path) itself.
- The helper resolves binaries only from fixed root-owned locations and runs
  them with a minimal environment; it never uses a user `PATH`.
- Credentials are written to `0600` files for the life of a connection,
  overwritten with zeroes and removed when it ends; `--auth-nocache` keeps
  OpenVPN from caching them.
- Certificate verification is never disabled. Profiles without a CA or
  `tls-remote` are refused at import and again at connect.
- DNS is fail-closed by default; resolvers of an address family the tunnel does
  not carry are dropped instead of unblocking that family.
- The kill switch is opt-in, drops everything except the tunnel/server/DHCP/NDP,
  and is removed on exit. If the app is force-killed, run
  `openvpn-desktop-helper cleanup`.

Full threat model, cryptographic details, limitations and reporting:
[`SECURITY.md`](SECURITY.md).

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| “The privileged helper is not ready” | Run `sudo ./scripts/install-helper.sh` (or `polkit`/`sudo` explicitly), then restart the app. Check `openvpn-desktop-helper doctor`. |
| `pkexec` prompt does not appear | No polkit agent in the session. Use `sudo ./scripts/install-helper.sh sudo`, or install a polkit authentication agent. |
| Windows helper service not ready | Run the PowerShell installer as administrator; check `Get-Service OpenVPNDesktopHelper`. The helper install also installs the bundled OpenVPN 2.7.1 with TAP-Windows6; if that step failed, run `openvpn-desktop-helper.exe install-service` again from the app folder. See `docs/windows-macos.md`. |
| macOS helper service not ready | Run `sudo ./scripts/install-macos-service.sh`; check `launchctl print system/com.smhasnanmonir.openvpndesktop.helper`; install OpenVPN 2.7+. |
| Per-app split will not start | It needs Linux cgroup v2 (`/sys/fs/cgroup/cgroup.controllers`), `nft`, `ip`, a non-VPN default route and the privileged helper. The connection is aborted rather than silently ignoring the setting. |
| A newly started app uses the wrong path for a moment | The monitor scans once per second; there is a short window. Select the real executable (`/proc/PID/exe`), not a wrapper script. |
| Tray icon does not appear | The desktop needs a StatusNotifier/AppIndicator host. The app logs `tray icon unavailable` and does not hide the window on close in that case. |
| Start at login opens but does not connect | The vault is intentionally locked at login. Unlock it; **Connect automatically after the vault is unlocked** then connects. The master password is not stored. |
| Captive portal still detected after signing in | Press **I signed in — reconnect**; it probes the network and only reconnects after a 204. Change the probe URL in Settings if the network blocks the default endpoint. |
| Connection stops with a DNS message | The server pushed no usable resolver and **Require tunnel DNS** is on. Add a fallback resolver in Settings or use a profile that pushes DNS. This is fail-closed by design. |
| Large downloads stall | Lower the MTU (Settings → 1400 or 1280). |
| Provider needs LZO compression | Keep **Compression: Compatibility**; switch to Strict only if the provider does not require compression. |
| “non-AEAD data cipher” error | The profile pins a CBC cipher and **Data ciphers: AEAD only** is selected. Use the profile-default policy or a provider that offers AES-GCM/ChaCha20-Poly1305. |
| Network is blocked after a crash with the kill switch on | `sudo openvpn-desktop-helper cleanup` removes the nftables table and restores `/etc/resolv.conf`. |
| `openvpn` not found / not trusted | Install OpenVPN 2.7+ from the distribution; the helper refuses a group/world-writable binary. |

---

## Repository layout

```
desktop/
  main.go                     window, menus, data dir, helper discovery
  cmd/openvpn-desktop-helper  privileged helper entry point
  internal/app                service: sessions, retry, logs, importer, public IP
  internal/appui              MyGo native UI (unlock, Home, Profiles, Logs, Settings)
  internal/ovpn               process/session runner + management protocol client
  internal/helper             root-side sanitizer, DNS script, kill switch
  internal/profile            import/trust/cipher/compression/MTU/split policies
  internal/dnspolicy          fail-closed DNS decisions
  internal/serviceproto       GUI ⇄ privileged-service job/log protocol
  internal/perapp             running-process discovery for per-app split
  internal/captiveportal      portal probe + URL safety
  internal/autostart          XDG start-at-login entry
  internal/winhelper          Windows service install/run
  internal/security           log redaction
  internal/settings           validated preferences
  internal/vault              Argon2id + AES-256-GCM vault
  scripts/                    install/uninstall helper, live tunnel test
  docs/                       architecture, testing, parity, windows-macos
```

## License

AGPL-3.0, the same as the Android client. See [`../LICENSE`](../LICENSE).
