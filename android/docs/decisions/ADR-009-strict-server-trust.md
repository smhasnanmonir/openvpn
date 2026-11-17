# ADR-009: Strict server-trust policy for profiles

## Status

Accepted.

## Context

OpenVPN Core only verifies what the profile tells it to verify. A profile with a
CA is still only "trust this CA"; server-usage enforcement depends on an
explicit `remote-cert-*`/`tls-remote`/`peer-fingerprint` directive. PR #5
started appending `remote-cert-tls server` for CA-based profiles that lacked an
explicit policy, which closed the "client certificate accepted as the server"
gap but did not address profiles with **no trust material at all**.

A profile can be structurally valid for Core and still have no way to validate
the server: no inline/external CA, no `peer-fingerprint`, no legacy
`tls-remote`. Depending on the core version and directives it may fail at
connect time with an opaque TLS error, or it may be accepted with weaker
assumptions. Issue #11 requires a strict, explicit policy and actionable
messages instead of implicit behaviour.

Revocation is a separate layer: the client has no CRL/OCSP integration in the
pinned OpenVPN 3 Core 3.9, and revocation is enforced by the VPN server/PKI
(an issued-but-revoked certificate cannot complete the server side of the
handshake once the PKI rejects it). The app must document that honestly rather
than imply client-side revocation checking.

## Decision

1. **Import-time trust requirement.** A profile is only accepted when it
   contains at least one **enforceable** server-trust anchor:
   - a CA (`ca` directive, inline `<ca>`, or a merged external CA reference);
   - the legacy `tls-remote` directive (treated as an explicit, documented
     legacy trust/name policy because it pins the expected subject).
   A `peer-fingerprint` (line form or inline `<peer-fingerprint>` block) is
   recorded as profile metadata and its presence triggers a warning, but it is
   **not** accepted as a trust anchor: the pinned OpenVPN 3 Core is built with
   the mbedTLS TLS backend, and that backend does not implement
   `peer-fingerprint` verification, so relying on a pin would silently verify
   with the ordinary CA/hostname path (or fail). Profiles whose only trust
   material is a fingerprint are rejected with an actionable message saying
   the pin cannot be enforced by this client and a CA is required.
   Profiles with no trust material are **rejected at import** with an
   actionable message that says what is missing (CA) and what the user must do
   (re-export the profile from the provider with its CA).
   *Amendment (2026-11-11):* the original decision listed `peer-fingerprint` as
   an anchor. End-to-end verification showed the mbedTLS build ignores it, so
   accepting fingerprint-only profiles would have been security theatre. The
   pin is now reported as unenforced instead (see `hasIgnoredPeerFingerprint`
   and the import warning).
2. **Connect-time re-check for stored/legacy profiles.** Profiles already stored
   by older app versions are checked again at connect time with the same
   `ProfileTrustPolicy`. An untrusted legacy profile is refused with a clear
   error instead of being handed to Core, so an upgrade cannot silently keep
   using a profile that would now be rejected at import.
3. **Keep (do not weaken) the PR #5 server-usage enforcement.**
   `remote-cert-tls server` continues to be appended at import and connect for
   CA-based profiles without an explicit policy; `tls-remote` profiles are left
   untouched. Profile `peer-fingerprint` lines are preserved (they are harmless
   metadata/warnings for a future backend), but are never treated as a
   substitute for the CA. Strict trust is additive.
4. **CA-change warning on same-name re-import.** When a profile with the same
   display name is re-imported and its CA material differs, the import succeeds
   but the result carries an explicit warning (shown in the UI and recorded in
   diagnostics) that the trust anchor changed. The comparison hashes the
   decoded DER certificate bytes (order-independent for multi-certificate
   bundles, ignoring PEM wrapping/comments), so re-encoding the same CA does
   not trigger a false warning. Silent replacement of the CA is a classic
   downgrade path; the app must make it visible.
5. **Document revocation as server/PKI-side.** The app does not claim CRL/OCSP
   support and does not fake a revocation check. Operators are told to enforce
   revocation on the PKI/server side; the client refuses untrusted trust
   anchors and reports certificate-verification failures.
6. **No "insecure" escape hatch.** There is no setting that disables
   certificate verification or accepts an untrusted server. The only profile
   exceptions are the explicit ones above (CA or legacy `tls-remote`), both of
   which are profile-supplied and visible.

## Consequences

- **Intentional behaviour change:** profiles with no CA/`tls-remote` are
  rejected (import) or refused (connect), and profiles whose only trust
  material is a `peer-fingerprint` are rejected with an explicit
  "pin cannot be enforced" message. Some hand-built or stripped-down profiles
  that used to attempt a connection will now stop with an explanation. This is
  documented in the changelog and README; re-exporting the profile from the
  provider with its CA is the supported fix.
- Profiles that rely on the server presenting a certificate from a public CA
  without a profile CA are not supported; the app does not add a system trust
  store. That is a deliberate non-goal (the pinned core has no such mode).
- The strict policy is enforced by pure Kotlin (`ProfileTrustPolicy`) so the
  rejection rules are unit-tested without a server; the actual X.509/hostname
  verification still happens in OpenVPN Core/mbedTLS.
- The CA-change warning needs a stable "same profile" identity. Re-import
  dedupe uses the content hash, so a changed CA is a different hash; the
  warning is keyed on the display name and is advisory, not a hard blocker.
- Revocation remains a documented operational control (see
  `docs/security.md` and `docs/architecture/backend-control-plane.md`); a
  future core upgrade with CRL/OCSP support would need its own ADR.

## Alternatives considered

- **Warn and connect without trust material.** Rejected: impossible to
  distinguish "provider forgot the CA" from "attacker stripped it".
- **Require a fingerprint for every profile.** Too strict: CA-based profiles
  are the normal OpenVPN model and provider exports do not include
  fingerprints.
- **Implement CRL/OCSP in the client.** Rejected for this PR: out of scope for
  the pinned core, more network calls and privacy surface, and revocation is
  authoritatively enforced at the gateway/PKI. Documented instead.
- **Silently replace an existing profile's CA on re-import.** Rejected: a
  silent trust-anchor swap is a downgrade; the warning keeps the user in
  control.

## References

- Issue: [#11](https://github.com/smhasnanmonir/openvpn/issues/11)
- `app/src/main/java/com/smhasnan/openvpn/config/ProfileTrustPolicy.kt`
- `app/src/main/java/com/smhasnan/openvpn/config/ProfileImportPolicy.kt`
- `app/src/main/java/com/smhasnan/openvpn/config/ConfigImporter.kt`
- ADR-004 (certificate-less username/password profiles)
- `docs/profile-import.md`, `docs/security.md`
