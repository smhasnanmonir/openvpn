# Security policy

## Reporting a vulnerability

Please report security issues **privately** instead of opening a public issue.
Preferred channel: the repository's GitHub **private security advisory**
("Security" → "Advisories" → "Report a vulnerability") on
<https://github.com/smhasnanmonir/openvpn>. If that is unavailable, open a
minimal public issue asking for a private contact channel without disclosing
the details.

Include:

- affected version/commit and build type (debug/release, ABI);
- Android version/device and whether Always-on/lockdown was enabled;
- a minimal reproduction using **synthetic** profiles and credentials;
- the security impact and, if known, the suggested severity;
- optionally a proof of concept (redact paths and identifiers).

Do **not** include real credentials, private keys, full profiles, personal
data, or third-party provider details. If a PoC needs a profile, generate a
throwaway CA/server and redact paths. Never test against real users' VPN
servers or third-party provider infrastructure.

## Scope

In scope:

- secret exposure (profiles, credentials, keys, logs, backups, clipboard,
  package-list handling);
- TLS/certificate-validation bypasses in this app's integration (including the
  strict trust policy and `remote-cert-tls server` enforcement);
- DNS leak / fail-closed bypass, split-tunnel policy bypass, captive-portal
  state dishonesty (UI claiming protection when the tunnel is down);
- TUN/socket-protection or routing flaws introduced by this app;
- JNI lifetime/memory-safety bugs;
- exported component/permission mistakes (including `QUERY_ALL_PACKAGES` use);
- unsafe profile import or path handling;
- app-lock bypasses;
- supply-chain/release issues in this repository's workflows (unpinned action,
  secret in CI, SBOM inaccuracy).

Out of scope (report upstream or handle operationally):

- vulnerabilities in upstream OpenVPN 3 Core / mbedTLS / AndroidX that are not
  reachable through this app;
- rooted/OS-compromise scenarios, malicious accessibility services, or a
  compromised device;
- malicious VPN servers or profiles that intentionally weaken their own
  configuration;
- lack of an app-level guaranteed kill switch (documented limitation — use
  Android Always-on/lockdown);
- revocation being server/PKI-side rather than client-enforced (documented;
  ADR-009);
- backend/PKI/gateway infrastructure (not implemented in this repository;
  issue #16).

## Supported versions

Security fixes are applied to the latest tagged release and, when feasible, to
the previous minor release for critical/high issues. Development snapshots and
pull-request branches are provided as-is. The release notes and changelog state
which versions contain a fix.

## Patch-deadline SLA

The repository maintainer triages reports within **5 business days** and aims
to ship a fix or documented mitigation within:

| Severity | Examples | Fix/mitigation target |
|---|---|---|
| **Critical** | remote code execution, TLS/certificate verification bypass, credential/key leak, fail-closed DNS/trust bypass that leaks traffic | **7 calendar days** |
| **High** | local secret exposure, split-tunnel policy bypass, kill-switch-adjacent state dishonesty, JNI memory-safety issue reachable by a profile/server | **30 calendar days** |
| **Medium** | hardening gaps, information disclosure with limited impact, supply-chain hygiene issues | **90 calendar days** |
| **Low** | defense-in-depth improvements, documentation/UX security clarity | next scheduled release |

If a deadline cannot be met, the maintainer publishes a mitigation or an
explicit status update and keeps the issue tracked. SLA timers start at
triage/acknowledgement, not at first report.

## Advisory monitoring owner and cadence

**Owner:** the repository maintainer is the security-advisory owner and the
single accountable person for triaging advisories, meeting the SLA, and
deciding on emergency releases.

Cadence:

- **Automated:** every push to `main` and a weekly schedule run
  `.github/workflows/security.yml` — `osv-scanner` against the generated SBOM
  and `gitleaks` secret scanning. Dependabot opens Gradle/GitHub Actions update
  PRs weekly.
- **Weekly:** review scheduled-scan results and Dependabot security PRs; open a
  tracking task for anything within SLA.
- **Monthly:** review upstream advisories for the pinned components — OpenVPN 3
  Core (`release/3.9` @ `10c6a56`), mbedTLS 3.6.2, asio 1.24.0, fmt 10.2.1,
  jsoncpp 1.9.6, lz4 1.9.4, AndroidX/Gradle/AGP/NDK — against the SBOM
  component list.
- **On upstream release:** review the OpenVPN Core and mbedTLS release notes
  within 14 days; decide whether the pin must move. Core updates follow
  ADR-006/ADR-010 (build-time patches with fail-fast checks) and require a
  licence-delta review and a full CI run; the submodule is never edited
  in-place.
- **On release:** the compliance checklist
  (`docs/compliance/release-checklist.md`) requires no untriaged critical/high
  advisory against the SBOM before publishing.

The advisory owner records the review date and the SBOM digest/commit in the
release notes or tracking issue.

## Emergency release process

For a critical/high issue with an active exploit or a leak/verification bypass:

1. **Contain:** decide whether to ship a code fix, a documentation/UI
   mitigation, or (for a compromised key/signing secret) rotate the secret and
   revoke/replace the artifact. Never leave a known-bad artifact published.
2. **Prepare the fix** on a private branch/minimal diff; keep the normal CI
   gates (unit tests, lint, native builds, 16 KB alignment, SBOM) unless a gate
   itself is the blocker — any bypass must be documented in the release note
   and re-run as soon as possible.
3. **Regenerate the SBOM**, verify the signed APK (`apksigner verify`), and
   attach the SBOM to the GitHub Release.
4. **Publish an out-of-band `vX.Y.Z` release** (not waiting for the next
   scheduled release), with a security note that does not disclose exploit
   details before coordinated disclosure.
5. **Publish the advisory** after users can update; credit the reporter if they
   agree and link the fixed version.
6. **Post-incident review:** record root cause, detection gap, and a follow-up
   action (test/CI/docs) in the tracker.

The normal release process is in `docs/release.md`; the version/tag flow is
unchanged for emergency releases.

## Secrets

No secrets, credentials, private keys, keystores, or real profiles are
committed. Release signing material is supplied to CI through repository
secrets only (`RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`). The workflows use
`permissions: contents: read` except the release job that needs `contents:
write`. `gitleaks` and the pre-release checklist verify the tree; if a secret
is ever committed, treat it as compromised: rotate it, remove it from history
as needed, and publish a security note if it could have been used.

## Security controls in this repository (summary)

- Certificate verification is never disabled; strict trust policy requires
  CA/`peer-fingerprint`/`tls-remote` (ADR-009); `remote-cert-tls server` is
  enforced for CA-based profiles.
- Tunnel DNS fails closed by default (ADR-008); split tunneling is explicit and
  warned (ADR-011); captive-portal pause is honest about protection state
  (ADR-012).
- Profiles/credentials encrypted with Android Keystore-backed AES-256-GCM;
  backup disabled; sanitized in-memory logs.
- Native hardening and 16 KB alignment gate; SBOM + advisory/secret scans.
- MASVS/ASVS gap assessment and the required independent penetration test are
  tracked in `docs/security/masvs-gap-assessment.md`; the leak-test matrix is
  `docs/security/leak-test-matrix.md`.

## Disclosure

Please allow time for a fix and coordinated disclosure. This is a
community-maintained project without a bug bounty. The maintainer will
coordinate disclosure timing with the reporter and publish an advisory when a
fix is available. Reports made in good faith will not be pursued legally.
