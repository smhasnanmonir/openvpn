# Windows and macOS privileged services

The Go/MyGo client cross-compiles for Windows and macOS. Neither platform has a
universally available unprivileged TUN API, so the GUI does not run OpenVPN
itself. A root/System helper service starts OpenVPN on the GUI's behalf and
streams its logs over a token-authenticated loopback connection.

> **Verification status:** the shared service protocol, job runner and GUI
> service client are verified end-to-end on Linux (`TestLiveTunnelService` uses
> the real `helper serve` process and a real OpenVPN tunnel). The Windows
> service wrapper, macOS launchd daemon and installers cross-compile and have
> unit tests, but a real Windows/macOS tunnel has **not** been run from this
> Linux host. Use the checklist below on each platform; signing/notarization and
> the final device test remain release steps.

## Architecture

```
GUI (user, unprivileged)
  │  127.0.0.1:<port> + random token
  ▼
helper service (Windows: LocalSystem / macOS: launchd root)
  │  validates token, sanitizes the job, writes protected temp files
  ▼
helper `run` child ──openvpn──▶ management interface 127.0.0.1:<random>
```

- `internal/serviceproto` defines the JSON job (`config`, credentials,
  management endpoint, DNS/kill-switch flags) and log/exit messages.
- `internal/helper/service.go` implements the loopback listener, constant-time
  token check, protected temp directory, child `run` process, log streaming and
  stop/cleanup.
- `internal/ovpn/service_session.go` is the GUI client: it sends the job, streams
  logs, dials OpenVPN's management interface, and treats service exit as the
  session exit.
- The service runs the same `helper run` path as Linux, so profile sanitization,
  trust enforcement, credential redaction and OpenVPN argv construction are
  shared.
- Windows/macOS tokens:
  - Windows system config: `%ProgramData%\OpenVPN Desktop\service.json`
    (SYSTEM/Administrators); user token:
    `%APPDATA%\openvpn-desktop\service.json` (current user only).
  - macOS system config:
    `/Library/Application Support/OpenVPN Desktop/service.json` (root 0600);
    user token:
    `~/Library/Application Support/openvpn-desktop/service.json` (user 0600).
- The token is 256-bit random and checked in constant time. The listener binds
  to loopback only.

## Windows

### Requirements

- Windows 10/11, administrator.
- OpenVPN 2.7+ installed under `C:\Program Files\OpenVPN` with the
  **TAP-Windows6** driver. The helper finds
  `C:\Program Files\OpenVPN\bin\openvpn.exe`.
- A built `openvpn-desktop-helper.exe`.

### Build

```powershell
cd desktop
$env:GOOS = "windows"; $env:GOARCH = "amd64"
go build -trimpath -o bin\openvpn-desktop-helper.exe ./cmd/openvpn-desktop-helper
# package the GUI (run from a platform where MyGo can package Windows):
go tool mygo build -platform windows/amd64
```

### Install

From an **elevated** PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File desktop\scripts\install-windows-service.ps1
```

The installer:

1. copies the helper to `C:\Program Files\OpenVPN Desktop\`;
2. generates a token and writes the SYSTEM/Administrators config and the current
   user's token file;
3. creates the automatic `OpenVPNDesktopHelper` service (LocalSystem) via
   `helper install-service`;
4. starts it.

The GUI reads the user token and connects on first use. Restart the GUI after
installation.

### Windows networking

- OpenVPN creates the TAP/Wintun adapter and routes; the helper does not use
  Linux `nft`/`ip`.
- DNS: the helper passes `--block-outside-dns --register-dns`; pushed DNS is
  applied through the TAP driver. **TAP-Windows6 is required for DNS**; a Wintun
  setup may block non-tunnel DNS but not apply pushed resolvers. Prefer TAP for
  daily use.
- Kill switch and per-app split tunneling are **Linux-only** in this client; the
  GUI hides the kill switch and per-app picker on Windows.
- Route-based split tunneling (CIDR include/exclude and LAN bypass) is
  OpenVPN-route based and works on Windows.
- System tray, captive-portal pause/sign-in and the public-IP check are
  cross-platform; start-at-login is Linux-only (use a Windows shortcut or Task
  Scheduler for now).

### Uninstall

```powershell
powershell -ExecutionPolicy Bypass -File desktop\scripts\install-windows-service.ps1 -Uninstall
```

### Verify on a Windows machine

- [ ] Service `OpenVPNDesktopHelper` is Running and Automatic.
- [ ] `%APPDATA%\openvpn-desktop\service.json` is readable by the current user
      only; `%ProgramData%\OpenVPN Desktop\service.json` is admin/System only.
- [ ] Import a profile, enter credentials, connect; management state reaches
      `CONNECTED`.
- [ ] `ping`/browse through the tunnel; DNS resolves through the tunnel and a
      DNS-leak test shows only the VPN resolver.
- [ ] Logs show the service's child output and no credentials.
- [ ] Stop/disconnect; the service child exits and no `openvpn.exe` remains.
- [ ] Reboot; the service starts, the GUI connects after unlock (if enabled).

## macOS

### Requirements

- macOS with an administrator account.
- OpenVPN 2.7+; Homebrew (`/opt/homebrew/sbin/openvpn` or
  `/usr/local/sbin/openvpn`) or the official package. A root-owned binary is
  preferred; Homebrew is accepted only when it is not group/world writable
  (documented residual trust).
- Go 1.27+ to build the helper.

### Build and install

```bash
cd desktop
export PATH="$PATH:/usr/local/go/bin:/opt/homebrew/bin"
go build -trimpath -o bin/openvpn-desktop-helper ./cmd/openvpn-desktop-helper
sudo ./scripts/install-macos-service.sh
```

The installer places the helper in
`/Library/PrivilegedHelperTools/com.smhasnanmonir.openvpndesktop.helper`,
installs a launchd daemon at
`/Library/LaunchDaemons/com.smhasnanmonir.openvpndesktop.helper.plist`, writes
the root/user token files, and bootstraps the daemon.

### macOS networking

- OpenVPN uses `utun`; no extra driver.
- DNS is applied with `networksetup` to the primary network service. The previous
  DNS servers/search domains are saved in `/var/run/openvpn-desktop` and
  restored on disconnect. `--dns-updown disable` prevents OpenVPN's own script
  from bypassing the fail-closed policy.
- `networksetup` applies DNS per network service, so split-domain routing is
  approximate; full-tunnel DNS is the reliable mode.
- Kill switch and per-app split tunneling are Linux-only; the GUI hides them.
  Route-based split tunneling works.
- System tray, captive-portal pause/sign-in and public-IP check are
  cross-platform. Start-at-login reports Linux-only; use a macOS Login Item for
  now.

### Uninstall

```bash
sudo ./scripts/install-macos-service.sh --uninstall
```

### Verify on a Mac

- [ ] `launchctl print system/com.smhasnanmonir.openvpndesktop.helper` shows the
      daemon running.
- [ ] `/Library/Application Support/OpenVPN Desktop/service.json` is root 0600;
      `~/Library/Application Support/openvpn-desktop/service.json` is user 0600.
- [ ] Connect; `CONNECTED`, `utun` address, DNS from the pushed resolver.
- [ ] `scutil --dns`/browser resolution goes through the VPN; disconnect restores
      the previous DNS servers.
- [ ] Logs are redacted; no credentials/keys.
- [ ] Disconnect leaves no `openvpn` process.
- [ ] Reboot/relogin; daemon starts, GUI connects after unlock.

## Signing and distribution (release steps)

- **Windows:** sign the GUI and helper with an Authenticode certificate; install
  the service via the signed installer. Unsigned local builds work only with
  warnings/administrator approval.
- **macOS:** sign the helper and app with a Developer ID, notarize, and ship a
  `.pkg` that installs the launchd daemon. Gatekeeper may block an unsigned
  helper on a clean machine.
- The MyGo GUI itself is packaged by `go tool mygo build`; the helper is a
  separate binary and must be included in the installer.

## Security summary

- The GUI is unprivileged; only the helper service runs as System/root.
- The service authenticates every job with a random token stored in a
  user-only file; the root config is System/Administrators or root-only.
- The service re-sanitizes the profile and controls OpenVPN's argv; the GUI's
  config is never trusted.
- Windows temp directories/files get a SYSTEM/Administrators DACL; macOS uses
  0700 root directories.
- Credentials are written to 0600 temp files, overwritten/deleted after the
  session.
- OpenVPN's path is fixed/standard; Linux uses root-owned binaries. macOS
  Homebrew acceptance is a documented trust trade-off; Windows Program Files is
  admin-only.
- Linux-only features are not silently emulated: kill switch/per-app are hidden
  on Windows/macOS and the service rejects them if requested.
