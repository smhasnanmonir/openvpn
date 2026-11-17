# Releasing OpenVPN Desktop

This is the release checklist for the desktop client. The Android client has
its own `v*` tags and workflow; desktop releases use **`desktop-v*`** so the two
never collide.

## What a release contains

| Platform | Artifacts |
|---|---|
| Linux | `openvpn-desktop` binary, `.tar.gz` with `install.sh`, `.deb`, `.rpm`, `PKGBUILD`, `update-linux-*.json`, `SHA256SUMS` |
| Windows | `OpenVPN Desktop.exe`, `openvpn-desktop-helper.exe`, `openvpn-desktop-setup-<version>-amd64.exe`, `install.ps1`, `update-windows-amd64.json` |
| macOS | `OpenVPN Desktop.app`, `<slug>-<version>-<arch>.dmg`, `install-macos.sh`, `update-darwin-*.json` |
| All | CycloneDX SBOM, flat `SHA256SUMS` (asset names), signed update manifests and signature files |

The helper is bundled in every package under the platform resource directory
and writes `helper.version`. The GUI offers **Update privileged helper** when an
app auto-update leaves an older installed service. The Linux
`.deb`/`.rpm`/tarball carry `/opt/openvpn-desktop/openvpn-desktop-helper`;
Windows and macOS ship the helper next to the app / in `Contents/Resources`.

## Release gates

Do not publish a stable release until all of these are true.

- [ ] `desktop/scripts/check.sh` passes (vet, tests, race, build, packages,
      helper-in-package checks).
- [ ] `sudo ./scripts/test-live-tunnel.sh` passes: `TestLiveTunnel` and
      `TestLiveTunnelService`.
- [ ] `sudo ./scripts/test-per-app-split.sh` passes both include and exclude.
- [ ] A real Windows 10/11 machine passes
      `scripts/test-windows-service.ps1` (elevated) and
      [`windows-macos.md`](windows-macos.md#verify-on-a-windows-machine).
- [ ] A real macOS 13+ machine (Intel and Apple Silicon if possible) passes
      `sudo scripts/test-macos-service.sh` and
      [`windows-macos.md`](windows-macos.md#verify-on-a-mac).
- [ ] Upgrade, uninstall and reboot tests pass on Windows and macOS.
- [ ] Windows GUI, helper and installer are Authenticode-signed and
      timestamped.
- [ ] macOS app, embedded helper and disk image are Developer ID signed and
      notarized; the ticket is stapled.
- [ ] The update private key is stored in the repository secrets and in the
      maintainer's password manager.
- [ ] Independent penetration test / code audit is complete (or the release
      is explicitly marked a beta).
- [ ] The OpenVPN trademark decision is recorded.

## Version and tags

- `mygo.json` holds the version. The release workflow rewrites it from the tag
  with `scripts/set-version.py`.
- Tag desktop releases as `desktop-v1.2.3`; never `v1.2.3` (that triggers the
  Android release workflow).
- The update feed uses `updates.tagPrefix = "desktop-v"` and
  `updates.github = "smhasnanmonir/openvpn"`.

## Secrets

Configure these in **Settings → Secrets and variables → Actions**:

| Secret | Purpose |
|---|---|
| `MYGO_UPDATER_PRIVATE_KEY` | Contents of `mygo-update.key`; signs update manifests and archives. Required for a tag. |
| `WINDOWS_CERT_PFX_BASE64` | Base64 `.pfx` Authenticode certificate. Required for a tag. |
| `WINDOWS_CERT_PASSWORD` | Password for the `.pfx`. |
| `MACOS_CERT_P12_BASE64` | Base64 Developer ID Application `.p12`. Required for a tag. |
| `MACOS_CERT_PASSWORD` | Password for the `.p12`. |
| `MACOS_SIGNING_IDENTITY` | e.g. `Developer ID Application: Name (TEAMID)`. |
| `MACOS_NOTARY_APPLE_ID` | Apple ID for notarization. |
| `MACOS_NOTARY_TEAM_ID` | Apple Developer team ID. |
| `MACOS_NOTARY_PASSWORD` | App-specific password for notarization. |

The public update key is committed in `mygo.json`; the **private** key is never
committed. The maintainer's local copy is `desktop/.keys/mygo-update.key`
(ignored by Git). Losing it strands installed apps; back it up before the first
release.

## Release flow

1. Update `CHANGELOG.md` with a `## <version>` section.
2. Run the local gate and the live tests.
3. Commit and push the release branch.
4. Tag and push:

   ```bash
   git tag desktop-v1.0.0
   git push origin desktop-v1.0.0
   ```

5. `.github/workflows/desktop-release.yml` builds on Ubuntu, Windows and macOS,
   signs when the secrets are present, generates the SBOM and checksums, and
   creates a **draft** GitHub release.
6. Review the draft assets. Confirm:
   - `openvpn-desktop-setup-*.exe` exists and is signed;
   - the macOS `.dmg` is signed/notarized and the helper is inside the app;
   - `update-*.json` and signature files exist;
   - `SHA256SUMS` matches the assets.
7. Publish the release with **“Set as the latest release” unchecked**. The
   update feed finds it by the `desktop-v` tag prefix.
8. Install from the published assets on a clean machine per platform and run
   the verification checklists.

## Local release build

Unsigned local artifacts:

```bash
cd desktop
./scripts/build-helpers.sh
python3 scripts/gen-sbom.py -o build/sbom.cdx.json
go tool mygo build
```

Signed update artifacts locally:

```bash
MYGO_UPDATER_PRIVATE_KEY="$(cat .keys/mygo-update.key)" go tool mygo build
```

Service smoke tests (no VPN required):

```powershell
# Windows, elevated
powershell -ExecutionPolicy Bypass -File desktop\scripts\test-windows-service.ps1
```

```bash
# macOS
sudo desktop/scripts/test-macos-service.sh
```

Windows installer and signature on Windows:

```powershell
$env:MYGO_UPDATER_PRIVATE_KEY = Get-Content .keys\mygo-update.key -Raw
$env:MYGO_WINDOWS_CERTIFICATE_PASSWORD = "..."
# mygo.json: "windows": { "certificate": "packaging/windows.pfx", ... }
go tool mygo build -platform windows/amd64
```

macOS signing/notarization is handled by the workflow; locally use a Developer
ID identity and `xcrun notarytool`:

```bash
go tool mygo build -platform darwin/universal -sign "Developer ID Application: ..."
```

## Verification evidence to attach

- Local gate output.
- `TestLiveTunnel` / `TestLiveTunnelService` output.
- Per-app include/exclude output.
- Windows `Get-Service OpenVPNDesktopHelper`, install/uninstall/reboot logs.
- macOS `launchctl print`, `codesign --verify`, `spctl --assess`,
  `xcrun stapler validate`, DNS before/after.
- SBOM and `SHA256SUMS`.

## Rollback

- Unpublish the GitHub release or mark it a prerelease; installed apps stop
  seeing it as the `desktop-v` latest release.
- Ship a new higher version with the fix. Do not reuse a version or key.
- Windows: uninstall the app (the uninstaller stops and removes the service).
- macOS: `sudo ./scripts/install-macos-service.sh --uninstall`.
- Linux: `sudo ./scripts/uninstall-helper.sh`, then remove the package.

## Known release limitations

- Windows/macOS real-device tunnel tests are mandatory manual gates; CI can
  only compile, unit-test and package them.
- No independent security audit yet.
- Kill switch and per-app split tunneling are Linux-only.
- Windows DNS requires TAP-Windows6; Wintun may block DNS without applying
  pushed resolvers.
- macOS DNS is applied per network service; split-domain DNS is approximate.
- The macOS installer makes a root-owned copy of OpenVPN; rerun the helper
  install after upgrading OpenVPN so the daemon uses the new binary.
- Start-at-login opens the app locked; the master password is never stored.
