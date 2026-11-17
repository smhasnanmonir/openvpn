# ADR-014: Backend control-plane/data-plane split is out of this Android repository

## Status

Accepted (scope decision). The backend itself is **not implemented**; this
repository only documents the architecture and the client contract it would
need. See `docs/architecture/backend-control-plane.md`.

## Context

This repository is an Android OpenVPN client for user-supplied `.ovpn`
profiles. It has no accounts, subscriptions, device management, PKI, VPN
gateways, or control plane. Issue #16 describes the pieces a commercial VPN
product needs around a client:

- separation of control plane (accounts, auth/MFA, tokens, device management,
  API) and data plane (VPN gateways, DNS);
- PKI issuance/revocation with an offline root and intermediate CA;
- gateway, DNS and API hardening;
- monitoring, rate limiting, abuse handling, incident response, ownership and
  runbooks.

Those concerns cannot be implemented inside the Android app repository, and
implementing a partial backend here would create a false sense of completeness
and a large security surface that this project cannot staff or test.

## Decision

1. **Scope:** the backend, PKI, gateways, and monitoring are **out of scope for
   this repository and this PR**. No backend code, credentials, or
   infrastructure-as-code is added here.
2. **Architecture is documented instead.** `docs/architecture/backend-control-plane.md`
   records the intended control-plane/data-plane split, account/MFA/token/
   device model, offline-root PKI and revocation model, gateway/DNS/API
   hardening, monitoring/incident response, and explicit owner/runbook
   placeholders. It is clearly marked as a design and plan, not as shipped
   functionality.
3. **Client contract is described, not implemented.** The document describes the
   authenticated API the client would consume (profile discovery, device
   enrollment, short-lived tokens) so follow-up work can start from a reviewed
   outline. The current app continues to use imported profiles only and does
   not call any backend.
4. **Follow-up issues.** The work is broken into backend/API, PKI, gateway/
   DNS/infra, and monitoring/IR issues when it actually starts; #16 remains the
   umbrella. Each will have its own threat model, ASVS review
   (`docs/security/masvs-gap-assessment.md` has the ASVS note), and owners.
5. **No secrets and no false claims.** No private keys, API keys, tokens, or
   production hostnames are committed. The docs do not claim the backend exists.

## Consequences

- The Android repository stays reviewable and buildable as a client; the
  "product" work is explicitly tracked elsewhere.
- Users of this repository keep using their own provider profiles; the app makes
  no account/backend assumptions and remains privacy-friendly (no analytics, no
  backend calls).
- The documented PKI decision (offline root + intermediate, per-device
  credentials, server-side authorization and revocation) is the baseline for
  future work; it is not a certification of an implemented system.
- A future commercial product must still pass its own privacy review, security
  review (ASVS on the backend), and independent penetration test
  (`docs/security/masvs-gap-assessment.md`).

## Alternatives considered

- **Implement a minimal backend in this repo.** Rejected: wrong repository,
  wrong threat model, no operational ownership, and it would blur the client's
  privacy story.
- **Ignore the issue.** Rejected: the product-readiness review needs an
  explicit scope decision and a documented target architecture.
- **Publish only a one-page sketch.** Rejected: the acceptance criteria for
  #16 require the full split (auth/MFA/tokens/devices, PKI, gateway/API
  hardening, monitoring/IR, ownership/runbooks), so the document is thorough
  while clearly marked as a plan.

## References

- Issue: [#16](https://github.com/smhasnanmonir/openvpn/issues/16)
- `docs/architecture/backend-control-plane.md`
- `docs/security/masvs-gap-assessment.md` (ASVS note for a future backend)
- `docs/security.md`, `SECURITY.md`
