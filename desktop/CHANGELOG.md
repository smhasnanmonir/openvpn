# Changelog

## 1.0.0 - 2026-11-17

### Added

- Native MyGo desktop client for Linux, Windows and macOS: vault unlock, Home,
  Profiles, Logs and Settings, tray, notifications and public-IP check.
- `.ovpn` import with inline/companion certificates, strict CA trust,
  `remote-cert-tls server`, cipher/compression/MTU policies and fail-closed DNS.
- Argon2id + AES-256-GCM encrypted vault for profiles, credentials and settings.
- OpenVPN 2.7+ management integration with honest state, bounded reconnects,
  byte counters and redacted logs.
- Linux per-app split tunneling (cgroup v2 + nftables + policy routing), opt-in
  nftables kill switch, XDG start-at-login and auto-connect after unlock.
- Privileged helper separation: unprivileged GUI, sanitized OpenVPN argv,
  trusted binary paths and process-group cleanup.
- Windows `OpenVPNDesktopHelper` LocalSystem service and macOS launchd daemon,
  with a token-authenticated loopback protocol and protected token files.
- One-click helper install from the GUI (UAC/polkit/osascript), bundled helper
  binaries in every platform package, and Windows/macOS start-at-login.
- Signed MyGo updates with `desktop-v*` tags and a GitHub update feed.
- macOS `networksetup` DNS and a root-owned OpenVPN copy so the daemon never
  executes a user-writable binary.
- Release workflow, SBOM, vulnerability scan, checksums and signing hooks.

### Known limitations

- Windows/macOS real-device tunnel tests, code signing/notarization and an
  independent security audit remain release gates.
- Kill switch and per-app split tunneling are Linux-only.
- Windows DNS uses TAP-Windows6 with `--block-outside-dns`; Wintun may not apply
  pushed resolvers.
