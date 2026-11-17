# Code signing policy

## Free code signing provided by SignPath.io, certificate by SignPath Foundation

OpenVPN Desktop Windows release artifacts are signed through
[SignPath.io](https://signpath.io/) using a certificate issued by the
[SignPath Foundation](https://signpath.org/). The private key is held in
SignPath's HSM and is never available to maintainers or GitHub Actions
runners.

This policy covers the `desktop/` application in this repository.

## What is signed

Only binaries built by this repository's GitHub Actions workflow from a
`desktop-v*` tag are submitted for signing:

- `OpenVPN Desktop.exe`
- `openvpn-desktop-helper.exe`
- the NSIS-installed `Uninstall.exe`
- `openvpn-desktop-setup-<version>-amd64.exe`

The Windows installer is signed after it is built. The NSIS-generated
uninstaller is generated once from the same installer script on a
GitHub-hosted Windows runner, signed, embedded in the release installer, and
verified before embedding.

Third-party binaries are not signed with this project's certificate. The
OpenVPN binary is installed by the user from its own distribution.

## Team roles

This is a single-maintainer project at present:

- **Authors:** [smhasnanmonir](https://github.com/smhasnanmonir)
- **Reviewers:** [smhasnanmonir](https://github.com/smhasnanmonir); outside
  pull requests are reviewed before merge.
- **Approvers:** [smhasnanmonir](https://github.com/smhasnanmonir); every
  SignPath signing request is approved manually.

All project members must use multi-factor authentication for GitHub and
SignPath.

## Build origin

Signing requests are made only from the
[desktop release workflow](.github/workflows/desktop-release.yml) on
GitHub-hosted `windows-latest` runners, using SignPath's GitHub trusted-build
integration. The workflow uploads the unsigned artifact to GitHub Actions
before submitting it to SignPath. Local builds are never submitted for
signing.

## Privacy

This program does not transfer information to other networked systems unless
specifically requested by the user or the person installing or operating it.
The VPN connects to the server in the user's imported profile; the optional
public-IP check contacts the configured URL. There is no analytics, telemetry,
crash reporter, account or backend. See [`desktop/SECURITY.md`](desktop/SECURITY.md)
and [`desktop/README.md`](desktop/README.md).

## Verification

On Windows:

```powershell
Get-AuthenticodeSignature ".\OpenVPN Desktop.exe" | Format-List Status, SignerCertificate
Get-AuthenticodeSignature ".\openvpn-desktop-helper.exe" | Format-List Status, SignerCertificate
Get-AuthenticodeSignature ".\openvpn-desktop-setup-1.0.0-amd64.exe" | Format-List Status, SignerCertificate
Get-AuthenticodeSignature "$env:LOCALAPPDATA\Programs\OpenVPN Desktop\Uninstall.exe" | Format-List Status, SignerCertificate
```

The production signer is **SignPath Foundation**. SmartScreen reputation is
separate from a valid signature and may take time to build.
