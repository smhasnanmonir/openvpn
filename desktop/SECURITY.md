# Security model — OpenVPN Desktop

This document describes what the desktop client protects, what it assumes, and
where the limits are. It is deliberately explicit: the app must fail visibly
rather than silently weaken the VPN.

## Reporting

Report vulnerabilities privately through the repository's security policy
([`../android/SECURITY.md`](../android/SECURITY.md)) or the GitHub security advisory flow. Do
not open a public issue with credentials, private keys or a working exploit.

## Assets and adversaries

Assets: profile bodies, usernames/passwords, private-key passwords,
static-challenge responses, the tunnel's traffic and DNS, the integrity of the
host's routing/firewall, and the user's expectation that “Connected” means
protected.

Adversaries considered:

1. **A malicious or compromised `.ovpn` profile.** It can contain scripts,
   plugins, management options, providers, external file references and
   deliberately weak trust settings.
2. **A local unprivileged user** on a multi-user machine who can run the GUI and
   the helper entry point.
3. **A network attacker** between the client and the VPN server.
4. **A malicious VPN server** that pushes routes, DNS, compression or ciphers.
5. **A stolen disk or backup** containing the vault.
6. **A local process that can read the user's memory or files** (same-user
   malware). This is out of scope for any desktop app without OS sandboxing.

## Privilege separation

The GUI runs as the user. It never creates a TUN device, changes routes or
rewrites DNS. It launches `openvpn-desktop-helper`, which is root-owned, and the
helper builds OpenVPN's argv itself.

- **Recommended:** a polkit action runs only
  `/usr/local/libexec/openvpn-desktop/openvpn-desktop-helper` through `pkexec`.
  Authentication is required (`auth_admin_keep`).
- **Alternative:** a sudoers rule allows `sudo -n` to run only that binary. This
  is passwordless and grants every local user the right to invoke the helper;
  use it only on single-user machines.
- **Discouraged:** setuid on the helper. It removes the authentication prompt
  entirely. The helper still sanitizes the profile and controls argv, but polkit
  or sudo is safer.

The helper:

- refuses to run if `euid != 0`;
- finds `openvpn`, `nft`, `resolvectl`, `resolvconf` and `ip` only in fixed
  directories (`/usr/sbin`, `/usr/bin`, `/sbin`, `/bin`, `/usr/local/...`) and
  requires them to be root-owned regular files that are not group/world
  writable; it never executes a binary from the user's `PATH`;
- runs OpenVPN with a minimal environment (`HOME=/root`,
  `PATH=/usr/sbin:/usr/bin:/sbin:/bin`, `LC_ALL=C`), not the user's environment;
- requires the management endpoint to bind to loopback;
- reads the profile and credential files as bounded regular files and rejects
  group/world-writable input;
- writes its own copy of the profile and credentials into a `0700` directory
  under `/run/openvpn-desktop/`, mode `0600`;
- re-sanitizes the profile even though the GUI already did (defense in depth).

### Profile sanitization

The importer and the helper remove dangerous directives before OpenVPN parses
the profile:

`up`, `down`, `up-restart`, `up-delay`, `route-up`, `route-pre-down`,
`down-pre`, `script-security`, `plugin`, `config`, `client-connect`,
`client-disconnect`, `learn-address`, `auth-user-pass-verify`, `tls-verify`,
`iproute`, `iproute-pre-down`, `chroot`, `user`, `group`, `cd`, `daemon`, `log`,
`log-append`, `status`, `writepid`, `management*`, `auth-user-pass`, `askpass`,
`mute`, `verb`, `auth-nocache`, `persist-key`, `connect-retry*`,
`connect-timeout`, `resolv-retry`, Windows-only DNS options, `engine`,
`providers`, `provider`, `pkcs11-providers`, `pkcs11-provider`,
`cryptoapicert`, `tls-export-cert`, `wintun`, `tap-windows`, every
`pull-filter`, and `setenv opt ...`.

The helper then appends its own `route-up`/`down` hooks (itself, with the
`dns-up`/`dns-down` subcommands), `--script-security 2`, `--auth-nocache`, and a
bounded reconnect policy. A profile cannot re-enable a filter or a script.

External file references are resolved at import time only from files the user
selected in the same dialog; they are inlined into `<ca>`, `<cert>`, `<key>`,
`<tls-auth>`, `<tls-crypt>` or `<tls-crypt-v2>` blocks. A reference that is not
selected fails the import. The stored profile has no external file references.

### Residual trust in the system OpenVPN binary

The helper trusts the distribution's `openvpn` binary and its OpenSSL
configuration. A compromised root-owned OpenVPN package defeats the client's
profile policy; that is a system supply-chain compromise, not a profile escape.
The helper refuses a writable OpenVPN binary so a local user cannot replace it.
The helper's option blacklist is broad but OpenVPN has many options; a future
OpenVPN option that executes code would need to be added to the blacklist. This
is why polkit/sudo (with authentication) is preferred over setuid.

## Vault

- One file: `$XDG_CONFIG_HOME/openvpn-desktop/vault.ovpnx`.
- Master password → **Argon2id** (`time=3`, `memory=64 MiB`, `threads=4`,
  `keyLen=32`) → **AES-256-GCM** with a random 16-byte salt and a random
  12-byte nonce per save.
- Format: `OVPNDESK` magic, version byte, salt, nonce, ciphertext, with the
  magic/version as associated data. A wrong password or tampered ciphertext
  fails authentication.
- Atomic write: temp file in the same directory, `chmod 0600`, `fsync`, rename,
  directory `fsync`. The directory is `0700`.
- The vault holds profiles, credentials and settings together. There is no
  plaintext settings file.
- **No recovery.** Losing the password loses the profiles and credentials. This
  is intentional; do not weaken it with an escrow.
- The key is held in process memory while unlocked. Go cannot guarantee that the
  key or plaintext is never copied by the garbage collector or swapped; use disk
  encryption and lock the vault when not in use.

## Credentials and logs

- Credentials and a private-key password are written to `0600` files only for
  the life of a connection (`--auth-user-pass`, `--askpass`), in a `0700`
  directory. On exit the files are overwritten with zeroes and removed.
- `--auth-nocache` prevents OpenVPN from caching credentials.
- The static-challenge response is used for one connection and is **not**
  persisted in the vault.
- Log lines containing `password`, `passphrase`, `private key`,
  `begin certificate`, `auth-token`, `session token`, `auth-user-pass`,
  `proxy-password`, `tls-auth`, `tls-crypt`, `static challenge` or
  `challenge response` are replaced with `[redacted: sensitive log line]`.
  Lines are truncated at 2048 bytes. Logs stay in a 500-line in-memory buffer
  and are never written to disk.
- The UI's “Copy all” copies the sanitized buffer; it cannot recover the
  original secret.

## Server authentication

- A profile must contain a CA certificate (`<ca>` or `ca`) or a legacy
  `tls-remote` subject pin. Otherwise import fails and connect is refused again.
- `remote-cert-tls server` is appended when a CA-based profile does not already
  constrain server usage (`remote-cert-tls`, `remote-cert-eku`,
  `remote-cert-ku`, `tls-remote`, `verify-x509-name`, `peer-fingerprint`).
- A `peer-fingerprint`-only profile is refused. The Android client refuses it
  because its mbedTLS core ignores pins; the desktop keeps the same policy so a
  profile is treated the same way on both platforms. A profile with a CA and a
  pin is accepted through the CA and the pin is reported.
- Legacy-only `tls-cipher` pins (no ECDHE suite) are removed so the server can
  negotiate TLS 1.3; pins that contain an ECDHE suite are kept.
- Certificate verification is never disabled. There is no “accept untrusted”
  setting.
- Revocation is server/PKI-side. The client does not implement CRL/OCSP; a
  revoked client certificate is rejected by the server, and a revoked server
  certificate requires the provider to update the profile's CA/CRL policy.

## DNS fail-closed

`internal/dnspolicy` ports the Android policy:

- only literal unicast IPv4/IPv6 addresses are accepted; no lookup is performed
  while importing or deciding, so the policy cannot create a leak;
- loopback, unspecified, multicast, link-local, broadcast and octal/ambiguous
  forms are rejected;
- private/CGNAT ranges are allowed (split-horizon DNS is legitimate);
- pushed resolvers win, then the user's validated fallback list;
- resolvers of an address family the tunnel does not route are dropped, because
  installing one would unblock that family;
- if **Require tunnel DNS** is on and neither source yields a usable resolver,
  `dns-up` exits non-zero, which aborts the OpenVPN connection.

`dns-up` applies the result through `resolvectl` when systemd-resolved is
available, then `resolvconf`, then a direct `/etc/resolv.conf` write. It refuses
to follow a `/etc/resolv.conf` symlink for the direct write, because that would
clobber systemd-resolved's stub file. A full tunnel installs `~.` as the routing
domain; split-include mode installs only the pushed search domains. `dns-down`
reverts the link/restores the backup.

OpenVPN 2.7 has its own automatic DNS handling
(`/usr/libexec/openvpn/dns-updown`). The helper passes
`--dns-updown disable` so that script can never install a pushed resolver before
or independently of the fail-closed decision; this client is the only DNS
manager. The live test also invokes `dns-up` with no resolver and asserts a
non-zero exit and an explanatory message.

## Compression and ciphers

- **Compatibility** (default): `allow-compression asym`; the server may compress
  what it sends, upload compression is not enabled, symmetric compression is
  refused.
- **Strict**: `allow-compression no`, removes local `compress`/`comp-lzo`, and
  rejects every pushed compressor except the `comp-lzo no` stub.
- **AEAD-only**: rejects profiles that pin a non-AEAD cipher, forces
  `data-ciphers AES-256-GCM:AES-192-GCM:AES-128-GCM:CHACHA20-POLY1305`, adds a
  local `cipher AES-256-GCM` fallback, and rejects a server-pushed non-AEAD
  cipher with `pull-filter`.
- The default is provider-compatible because some providers require LZO-ASYM.
  Strict/AEAD-only are explicit opt-ins; the UI explains the trade-off.

## Captive portals

- Detection is an explicit HTTP probe, not an OS connectivity API. A 204 is
  internet; a redirect or a 200 is treated as a portal; a transport error is
  offline. The probe URL must be absolute http/https.
- A portal URL returned by the network is validated again before it is opened
  (`http`/`https` only). `javascript:`, `file:`, `data:` and relative URLs are
  refused, so a hostile portal cannot launch a local scheme or file.
- **Pause VPN and sign in** stops the tunnel and sets `pausedForPortal`; the UI
  shows “traffic is not protected” and never renders the session as protected.
  **I signed in — reconnect** probes again and only reconnects after internet is
  reachable.
- Automatic detection runs at most once per connection attempt after a failure;
  it is not a continuous monitor. It can be disabled in Settings.

## Windows/macOS privileged service

- The GUI is unprivileged. On Windows the helper runs as **LocalSystem** via the
  `OpenVPNDesktopHelper` service; on macOS it runs as **root** under launchd.
  Neither platform uses a setuid binary.
- The service listens on `127.0.0.1` and requires a **256-bit random token** on
  every job, compared in constant time. The root/System config is
  `%ProgramData%\OpenVPN Desktop\service.json`
  (SYSTEM/Administrators) or
  `/Library/Application Support/OpenVPN Desktop/service.json` (root 0600). The
  per-user token file is `%APPDATA%\openvpn-desktop\service.json` or
  `~/Library/Application Support/openvpn-desktop/service.json`, restricted to
  the installing user. Windows uses explicit SID-based DACLs for the token
  files and their directories; macOS chowns the per-user directory/file and
  keeps the system copy root-owned. A local process without that token cannot
  start a job.
- Jobs carry profile text, credentials and the management endpoint over the
  authenticated loopback connection. The service writes them to a protected
  temp directory: SYSTEM/Administrators DACL on Windows, `0700` root on macOS.
  The child `run` helper re-sanitizes the profile and controls OpenVPN's argv, so
  a tampered job cannot add scripts/plugins/management options.
- Credentials are written to `0600` temp files (DACL-protected on Windows) and
  deleted after the session; logs are redacted before they are streamed.
- OpenVPN path trust:
  - Windows: `C:\Program Files\OpenVPN\bin\openvpn.exe` (Program Files ACL).
  - macOS: the installer copies the installed OpenVPN binary to
    `/Library/Application Support/OpenVPN Desktop/bin/openvpn` and makes it
    root-owned. The helper requires a root-owned, non-group/world-writable
    binary and refuses a user-owned Homebrew binary, so a compromised login
    account cannot replace the daemon's executable.
  - Linux: root-owned fixed directories (see above).
- Windows DNS uses the TAP-Windows6 driver plus
  `--windows-driver tap --block-outside-dns --register-dns`; a Wintun-only setup may block non-tunnel
  DNS without applying pushed resolvers. Prefer TAP for DNS.
- macOS DNS uses `networksetup` on the primary network service, saving and
  restoring the previous resolvers/search domains. `networksetup` is per
  service, so split-domain DNS is approximate; full-tunnel DNS is reliable.
- Kill switch and per-app split tunneling are **Linux-only**. The GUI hides them
  on Windows/macOS and the service rejects them if requested; they are not
  silently emulated.
- The GUI's one-click install runs only the bundled helper through UAC,
  `osascript` administrator approval or `pkexec`; the helper then copies itself
  to a privileged location and writes the token files. Windows upgrades stop
  the old service and recreate it with the new binary.
- Signed updates use an Ed25519 key pair: the public key is built into the app,
  the private key lives only in CI secrets. Installed apps reject unsigned
  manifests/archives.
- Code signing/notarization is configured in the release workflow: Authenticode
  for the Windows GUI/helper/installer and Developer ID + notarization for the
  macOS app/helper. A public release must not ship unsigned binaries.

## Per-app split tunneling (Linux)

- The helper creates a private cgroup v2 tree
  (`/sys/fs/cgroup/openvpn-desktop-<helper-pid>`), moves matching processes into
  an app cgroup and, in include mode, the VPN process into a separate cgroup.
  It installs an `inet` nftables chain at output/mangle priority that sets
  `meta mark 0x1` on the selected packets, plus an `ip rule`/`ip -6 rule` for
  fwmark `0x1` and a policy table (51820) with the physical default route.
- **Exclude** mode marks the selected app cgroup; **include** mode exempts the
  VPN/app cgroups and marks every other packet for the physical path. Only
  processes with `CAP_NET_ADMIN` can set `SO_MARK` themselves; unprivileged
  applications cannot opt out of the policy.
- **New processes:** a one-second `/proc` scan moves matching executables into
  the cgroup. Children inherit the cgroup. There is a short window between a
  process starting and being moved; this is documented in the UI.
- **DNS:** the system resolver (`systemd-resolved`, `dnsmasq`, `unbound`,
  `named`) is recorded and moved to the VPN cgroup in include mode, so selected
  apps' system DNS does not leak to the ISP; it is restored to its original
  cgroup on teardown. Apps using their own DoH/DoT follow their per-app rule.
  If the resolver is not one of those processes (for example glibc reads
  `/etc/resolv.conf`), the DNS packets belong to the app and follow its cgroup.
- The kill switch allows `meta mark 0x1` only when per-app split is active, so
  intentionally bypassed apps are not silently dropped. With the kill switch
  off, bypass traffic is still unprotected by design and the UI warns.
- Setup is **fail-visible**: if cgroup v2, `nft`, `ip`, a physical default route
  or the helper is missing, the connection is aborted instead of silently
  ignoring the setting.
- Selecting a wrapper script matches the interpreter (`/proc/PID/exe`), not the
  script path; the UI says so. Network changes are handled by refreshing the
  physical policy route every five seconds; if no physical route exists for an
  address family, that family stays on the VPN path rather than bypassing.

## Split tunneling and the kill switch

- Route-based split tunneling is a **leak source**. The UI shows an explicit
  warning whenever a mode lets traffic bypass the tunnel, including LAN bypass.
  “Only these networks” removes the default route; server-pushed routes still
  apply. “All except these” adds `route ... net_gateway` entries.
- The nftables kill switch is **off by default**. When on, it installs a
  `table inet openvpn_desktop_ks` output chain with `policy drop`, allowing
  loopback, `tun*`/`tap*`, established/related flows, DHCP, IPv6 neighbour
  discovery and the VPN server's resolved IPs. The helper resolves the remote
  host before installing the table and pins the IPs in the profile, so OpenVPN
  does not need DNS after the firewall is up.
- If the helper is killed with `SIGKILL`, the table can persist and networking
  will be blocked until `openvpn-desktop-helper cleanup` is run. This is the
  fail-closed trade-off; the app runs cleanup on the next start and on a normal
  exit.
- The app never calls itself a system-wide kill switch when the toggle is off.
  Always-on VPN/lockdown is an Android platform feature; the desktop equivalent
  is this explicit firewall.

## Privacy

There is no account, backend, analytics, crash reporter or telemetry. Two
optional requests exist besides the VPN:

- the public-IP check to the URL in Settings (`https://api.ipify.org` by
  default), off by default, run only when the user asks or enables “check
  automatically after connecting”; the response is limited to 256 bytes and
  validated as an IP literal;
- the captive-portal probe to the URL in Settings
  (`http://connectivitycheck.gstatic.com/generate_204` by default), run on
  demand and after a failed connection when **Detect automatically** is on. It
  sends no identifiers, reads at most 4 KiB, does not follow redirects (the
  redirect is the portal signal), and is disabled by clearing/disable the
  setting.

Start at login writes an XDG autostart entry only; it does **not** store the
master password. The app opens locked and `--autoconnect` connects only after
the user unlocks the vault. Do not add a stored password, keyring export or
plaintext auto-unlock as a shortcut.

## Limitations

- No independent penetration test or third-party audit yet.
- The master-password vault is not hardware-backed; a compromised user session
  can read the decrypted vault from process memory.
- Go's GC may copy key material; the client zeroes credential files and the
  in-memory key on lock, but cannot guarantee no copies in swap/core dumps.
- DNS application depends on the host's resolver manager. In a container,
  network namespace or unusual distribution, fail-closed may stop a connection
  that would otherwise work; that is intentional and the error explains it.
- Per-app split tunneling and the nftables kill switch are Linux-only.
- Real Windows/macOS tunnel verification, code signing and notarization remain
  release/manual steps; this Linux host can only cross-compile and unit-test
  those wrappers.
- Windows DNS depends on TAP-Windows6; macOS `networksetup` DNS is per network
  service rather than per tunnel.
- Captive-portal detection is heuristic (a probe response); a portal that
  proxies traffic without redirecting or returning a page can be missed.
- Start-at-login opens the app locked; it cannot connect before the vault is
  unlocked. There is no boot-time headless unlock.
- The option blacklist is not a formal OpenVPN sandbox; see the residual trust
  note above.

## Verification evidence

- Unit tests cover trust, imports, sanitization, DNS decisions, cipher/
  compression/MTU transforms, the vault, management parsing, retry policy, the
  helper's rule generation and the UI's viewport layout.
- A live tunnel test against a local OpenVPN 2.7 server verifies TLS 1.3,
  certificate authentication, `CONNECTED`, tunnel IP `10.8.0.2`, ping through
  the tunnel, moving byte counters, redacted logs and a clean disconnect. See
  [`docs/testing.md`](docs/testing.md).
- The packaged app is built by `go tool mygo build`; the UI view is exercised
  without a window by `go test` and the window is created under Xvfb+openbox in
  manual checks.
