# Windows signing with SignPath Foundation

OpenVPN Desktop uses the free
[SignPath Foundation](https://signpath.org/) code-signing service for Windows
releases. There is no `.pfx` or `.crt` in the repository, in GitHub secrets, or
on a maintainer machine. SignPath holds the private key in its HSM and signs
artifacts submitted by the GitHub Actions workflow.

The Windows workflow is in
[`.github/workflows/desktop-release.yml`](../../.github/workflows/desktop-release.yml).
Linux and macOS jobs are unchanged.

Signing is **optional and off by default**. The workflow signs through SignPath
only when `SIGNPATH_API_TOKEN` is set, falls back to a local `.pfx` when one is
configured, and otherwise builds and publishes an unsigned installer with a
`::warning` annotation. SmartScreen and UAC then show an unknown publisher.
Auto-updates are unaffected either way: they are verified with the Ed25519
update key, not Authenticode.

## Why this is a two-stage workflow

SignPath can sign a PE file, but it cannot deep-sign an NSIS installer. The
application executable and uninstaller must be signed **before** the installer
embeds them:

1. Build a staging installer from the same source and version.
2. Install it on the Windows runner and extract the generated `Uninstall.exe`.
3. Upload `OpenVPN Desktop.exe`, `openvpn-desktop-helper.exe` and
   `Uninstall.exe` to SignPath in one ZIP; sign and download them.
4. Build the release installer with MyGo, using `windows.signCommand` only as
   an **injection hook** that puts the signed app, helper and uninstaller in
   place. MyGo still generates the signed update archive and manifest.
5. Upload the completed installer to SignPath; sign and download it.
6. Verify every signature, then publish the signed installer with the normal
   release job.

This keeps MyGo's GitHub release and auto-update flow: the update archive is
built after the signed app/helper are injected, so its hashes and Ed25519
manifest are correct.

## One-time application and setup

1. Read the Foundation conditions at <https://signpath.org/terms>. The project
   must be public, OSI-licensed, maintained, already released in the form to be
   signed, and have a code signing policy. This repository has
   [`CODE_SIGNING.md`](../../CODE_SIGNING.md). If no desktop Windows release
   exists yet, publish one unsigned first; SignPath may decline a project that
   has never shipped the artifact.
2. Apply at <https://signpath.org/apply> with:
   - repository URL;
   - license (`AGPL-3.0`);
   - GitHub Releases download URL;
   - description of the Windows app/installer;
   - a link to `CODE_SIGNING.md`.
3. After approval, in <https://app.signpath.io>:
   - add the **GitHub.com** trusted build system to the organization;
   - create a project linked to this repository;
   - install the [SignPath GitHub App](https://github.com/apps/signpath);
   - create the `release-signing` policy (start with `test-signing` while
     validating);
   - create artifact configurations from:
     - [`.signpath/artifact-configurations/windows-app.xml`](../../.signpath/artifact-configurations/windows-app.xml)
     - [`.signpath/artifact-configurations/windows-installer.xml`](../../.signpath/artifact-configurations/windows-installer.xml)
   - add the CI user/API token as a **Submitter**;
   - add an **Approver** (every release needs manual approval).

Slugs and IDs are entered in GitHub, not committed:

| GitHub setting | Value |
|---|---|
| Secret `SIGNPATH_API_TOKEN` | API token from the submitting user |
| Variable `SIGNPATH_ORGANIZATION_ID` | SignPath organization ID |
| Variable `SIGNPATH_PROJECT_SLUG` | SignPath project slug |
| Variable `SIGNPATH_SIGNING_POLICY_SLUG` | `release-signing` (or `test-signing`) |
| Variable `SIGNPATH_APP_ARTIFACT_CONFIGURATION_SLUG` | `windows-app` (default) |
| Variable `SIGNPATH_INSTALLER_ARTIFACT_CONFIGURATION_SLUG` | `windows-installer` (default) |

The workflow defaults the two artifact configuration slugs to `windows-app`
and `windows-installer`.

Set them with `gh`:

```bash
gh secret set SIGNPATH_API_TOKEN
gh variable set SIGNPATH_ORGANIZATION_ID --body "<organization-id>"
gh variable set SIGNPATH_PROJECT_SLUG --body "openvpn-desktop"
gh variable set SIGNPATH_SIGNING_POLICY_SLUG --body "release-signing"
```

## Test signing first

Set the policy to `test-signing` and run the release workflow manually or from
a branch. SignPath sends approval e-mails. The workflow waits up to one hour
per request. Test-signed files are not trusted on users' machines and must not
be published as production releases.

Verify a test-signed file on the runner or a test VM:

```powershell
Get-AuthenticodeSignature ".\build\windows-amd64\OpenVPN Desktop.exe" |
  Format-List Status, SignerCertificate, TimeStamperCertificate
```

The signer subject contains `SignPath`; production releases must return
`Status = Valid`.

## Release signing

Set the policy to `release-signing`, add the update key/other platform secrets,
push a `desktop-v*` tag, and approve the two SignPath requests when they arrive:

1. app + helper + uninstaller;
2. installer.

The workflow fails closed if either request is missing, denied, times out, or
returns an unsigned/invalid file. It does not publish an unsigned Windows
fallback.

## Artifact configuration notes

The committed configurations use `<zip-file>` roots because
`actions/upload-artifact` wraps uploads in a ZIP. They sign every PE file in the
upload.

SignPath Foundation asks that signed binaries have product metadata and
metadata restrictions. MyGo's app executable already carries Windows version
information. The helper and NSIS uninstaller may not; if the SignPath project
review requires metadata restrictions, add version resources to the helper and
NSIS installer and then add `product-name`, `product-version`, `file-version`,
`company-name`, `copyright` and `original-filename` attributes to the two XML
files. Do not silently drop the files from the request.

The uninstaller is a **derived** binary: it is generated by the staging
installer from the same NSIS script and signed as part of the same trusted
GitHub build. The workflow injects the signed bytes through
`windows.signCommand`; it does not sign them locally.

## If SignPath is unavailable

There is no other genuinely free, publicly trusted Authenticode route for a
GitHub-hosted release:

- **SignPath Foundation** is free, but requires OSS eligibility, a public
  repository, a code signing policy, GitHub-hosted builds, and manual approval.
- **Certum Open Source** is inexpensive but not free.
- **Azure Trusted Signing** has a monthly fee.
- **Sigstore/cosign** is free but does not provide Windows Authenticode or
  clear SmartScreen's publisher prompt.
- **Self-signed certificates** are free but are not trusted by other machines.

If Foundation rejects the project or the service is unavailable, the release
must remain explicitly unsigned or use one of the paid options. Do not label a
self-signed build as trusted.
