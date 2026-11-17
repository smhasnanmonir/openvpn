# Windows and macOS privileged services

The Go/MyGo client runs on Windows and macOS. Neither platform has a
universally available unprivileged TUN API, so the GUI does not run OpenVPN
itself. A System/root helper service starts OpenVPN on the GUI's behalf and
streams its logs over a token-authenticated loopback connection.

> **Verification status:** the shared service protocol, job runner, GUI client,
> one-click installers and packages are verified on Linux
> (`TestLiveTunnelService` uses the real `helper serve` process and a real
> OpenVPN tunnel; the Windows NSIS installer and macOS `.app` build with the
> helper bundled). The real Windows service and macOS launchd install/uninstall
> smoke tests passed on GitHub-hosted runners. A real Windows/macOS **tunnel**,
> DNS behavior, reboot/upgrade, code signing and notarization still require
> those devices. Use the checklists below; they remain release gates.

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
  management endpoint, DNS flags) and log/exit messages.
- `internal/helper/service.go` implements the loopback listener, constant-time
  token check, protected temp directory, child `run` process, log streaming and
  stop/cleanup.
- `internal/serviceinstall` installs the platform helper: Windows service,
  macOS launchd daemon, Linux polkit action. It reuses a valid system token on
  upgrade and writes the per-user token.
- `internal/ovpn/service_session.go` is the GUI client: it sends the job, streams
  logs, dials OpenVPN's management interface, and treats service exit as the
  session exit.
- `internal/ovpn/install_helper_*.go` performs the one-click install with
  `ShellExecute runas` (Windows), `osascript ... with administrator privileges`
  (macOS) or `pkexec` (Linux).
- The service runs the same `helper run` path as Linux, so profile sanitization,
  trust enforcement, credential redaction and OpenVPN argv construction are
  shared.
- Windows/macOS tokens:
  - Windows system config: `%ProgramData%\OpenVPN Desktop\service.json`
    (SYSTEM/Administrators only); user token:
    `%APPDATA%\openvpn-desktop\service.json` (current user + SYSTEM only).
  - macOS system config:
    `/Library/Application Support/OpenVPN Desktop/service.json` (root 0600);
    user token:
    `~/Library/Application Support/openvpn-desktop/service.json` (user 0600).
- The token is 256-bit random and checked in constant time. The listener binds
  to loopback only.
- Windows ACLs use well-known SIDs (`S-1-5-18`, `S-1-5-32-544` and the current
  user's SID), not localized account names.

## Windows

### Requirements

- Windows 10/11, administrator for the one-time helper install.
- No separate OpenVPN download. The app ships the official OpenVPN 2.7.1
  installer as `openvpn-installer.msi` next to the executable; the elevated
  helper install verifies its SHA-256, runs it silently, and then uses
  `C:\Program Files\OpenVPN\bin\openvpn.exe`. That installer provides the
  **TAP-Windows6** driver, which is required for pushed DNS.
- An existing OpenVPN installation is reused and the bundled installer is not
  run.
- The installer comes from the build step `scripts/fetch-openvpn-msi.sh`; it is
  not committed. Offline builds fall back to the previous behaviour: install
  OpenVPN 2.7+ yourself.

### Install

The MyGo NSIS installer includes `openvpn-desktop-helper.exe` and offers the
service install with a UAC prompt. If it is skipped or fails, open
**Settings → Privileged helper → Install privileged helper**; the GUI runs the
bundled helper with `runas`.

Terminal fallback from an **elevated** PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File desktop\scripts\install-windows-service.ps1
```

The installer/helper:

1. copies the helper to `C:\Program Files\OpenVPN Desktop\`;
2. writes the SYSTEM/Administrators system token and the current user's token
   with explicit SID-based ACLs;
3. creates or upgrades the automatic `OpenVPNDesktopHelper` service
   (LocalSystem);
4. starts it.

The GUI reads the user token and connects without a restart.

### Windows networking

- OpenVPN creates the TAP/Wintun adapter and routes; the helper does not use
  Linux `nft`/`ip`.
- DNS: the helper passes `--windows-driver tap --block-outside-dns --register-dns`; pushed DNS is
  applied through the TAP driver. **TAP-Windows6 is required for DNS**; a Wintun
  setup may block non-tunnel DNS but not apply pushed resolvers. Prefer TAP for
  daily use.
- Kill switch and per-app split tunneling are **Linux-only** in this client; the
  GUI hides the kill switch and per-app picker on Windows.
- Route-based split tunneling (CIDR include/exclude and LAN bypass) is
  OpenVPN-route based and works on Windows.
- System tray, captive-portal pause/sign-in, public-IP check and start-at-login
  (per-user `Run` key) work on Windows.

### Uninstall

Use **Settings → Apps → OpenVPN Desktop → Uninstall**. The NSIS uninstaller
stops and removes the service. Terminal fallback:

```powershell
powershell -ExecutionPolicy Bypass -File desktop\scripts\install-windows-service.ps1 -Uninstall
```

### Verify on a Windows machine

Run the service smoke test first (elevated, no VPN needed):

```powershell
powershell -ExecutionPolicy Bypass -File desktop\scripts\test-windows-service.ps1
```

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
- [ ] Upgrade over an installed version; the service keeps working and the
      token remains valid.
- [ ] Uninstall removes the service and the per-user token.

## macOS

### Requirements

- macOS 13+ with an administrator account.
- OpenVPN 2.7+; Homebrew (`/opt/homebrew/sbin/openvpn` or
  `/usr/local/sbin/openvpn`) or the official package. The installer copies the
  binary to a **root-owned** location; the daemon never executes a
  user-writable Homebrew binary.

### Install

Open the app and use **Settings → Privileged helper → Install privileged
helper**. macOS asks for administrator approval; the bundled helper installs
itself, copies OpenVPN to a root-owned location, writes the tokens and loads the
launchd daemon.

Terminal fallback:

```bash
cd desktop
sudo ./scripts/install-macos-service.sh            # builds the helper
sudo ./scripts/install-macos-service.sh --helper "build/darwin-universal/OpenVPN Desktop.app/Contents/Resources/openvpn-desktop-helper"
```

The helper is installed at
`/Library/PrivilegedHelperTools/com.smhasnanmonir.openvpndesktop.helper`; the
daemon plist at
`/Library/LaunchDaemons/com.smhasnanmonir.openvpndesktop.helper.plist`.

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
- System tray, captive-portal pause/sign-in, public-IP check and start-at-login
  (per-user LaunchAgent) work on macOS.

### Uninstall

```bash
sudo ./scripts/install-macos-service.sh --uninstall
```

### Verify on a Mac

Run the service smoke test first (it needs OpenVPN installed and no VPN
connection):

```bash
sudo desktop/scripts/test-macos-service.sh
```

- [ ] `launchctl print system/com.smhasnanmonir.openvpndesktop.helper` shows the
      daemon running.
- [ ] `/Library/Application Support/OpenVPN Desktop/service.json` is root 0600;
      `~/Library/Application Support/openvpn-desktop/service.json` is user 0600.
- [ ] `/Library/Application Support/OpenVPN Desktop/bin/openvpn` is root-owned
      and not group/world writable.
- [ ] Connect; `CONNECTED`, `utun` address, DNS from the pushed resolver.
- [ ] `scutil --dns`/browser resolution goes through the VPN; disconnect restores
      the previous DNS servers.
- [ ] Logs are redacted; no credentials/keys.
- [ ] Disconnect leaves no `openvpn` process.
- [ ] Reboot/relogin; daemon starts, GUI connects after unlock.
- [ ] Upgrade the app; the daemon and token keep working.

## Signing and distribution

The release workflow is in `.github/workflows/desktop-release.yml`; see
[`release.md`](release.md) for the secrets and checklist.

- **Windows:** set `windows.certificate` and
  `MYGO_WINDOWS_CERTIFICATE_PASSWORD`; MyGo signs the GUI, bundled helper and
  NSIS installer with Authenticode (timestamped).
- **macOS:** import a Developer ID Application certificate and sign/notarize
  with `-sign` and `macos.notarize`; MyGo signs the app and the helper among its
  resources, then notarizes and staples the app/disk image.
- **Updates:** `updates.publicKey` is committed; `MYGO_UPDATER_PRIVATE_KEY`
  signs the update archives/manifests. Never commit the private key.

## Security summary

- The GUI is unprivileged; only the helper service runs as System/root.
- The service authenticates every job with a random token stored in a
  user-only file; the root config is System/Administrators or root-only.
- Windows config files/directories get explicit SID-based DACLs; macOS uses
  root-owned 0700 directories and chowns only the per-user token directory/file.
- The service re-sanitizes the profile and controls OpenVPN's argv; the GUI's
  config is never trusted.
- Credentials are written to 0600 temp files (DACL-protected on Windows) and
  deleted after the session; logs are redacted before streaming.
- OpenVPN's path is fixed/standard; macOS uses a root-owned copy, Windows
  Program Files is admin-only, and Linux uses root-owned binaries.
- Linux-only features are not silently emulated: kill switch/per-app are hidden
  on Windows/macOS and the service rejects them if requested.
