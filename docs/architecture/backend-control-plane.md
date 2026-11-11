# Backend control plane and data plane — architecture and scope

> **Status: design document, not implemented.** Nothing in this document is
> shipped in this repository. This Android repository contains **no backend,
> account system, PKI, VPN gateway, DNS service, or telemetry**. The document
> exists so the product/security work in issue #16 has a reviewed target
> architecture, explicit ownership gaps, and a client contract before any
> backend code is written. See ADR-014.

## 1. Why this is separate from the Android client

The app is a client for user-supplied `.ovpn` profiles. A commercial VPN
product needs infrastructure that this repository cannot provide, test, or
operate:

- accounts and authentication (including MFA), tokens, subscriptions;
- device enrollment/management and per-device credentials;
- PKI issuance/revocation with an offline root;
- VPN gateways and DNS resolvers (the data plane);
- API edge, rate limiting, abuse handling, monitoring, incident response.

Embedding a partial backend in the client repo would blur the trust boundary,
create an untested security surface, and make the app's privacy story
ambiguous. The split below is the target for follow-up repositories/services.

## 2. Trust boundaries

```
                    ┌──────────────────────────────────────────────┐
                    │ Control plane (accounts/API/PKI/monitoring)  │
                    │  - identity provider, MFA, short-lived tokens │
                    │  - device enrollment + device list/revocation │
                    │  - PKI issuance (offline root → intermediate) │
                    │  - subscriptions/entitlements                 │
                    │  - admin/audit/IR                             │
                    └───────────────┬──────────────────────────────┘
                                    │ authenticated, narrow API
                    ┌───────────────▼──────────────────────────────┐
 Android client ────┤ API edge (public): auth, discovery, config     │
 (this repo)        └───────────────┬──────────────────────────────┘
                                    │ signed profile / gateway list
                    ┌───────────────▼──────────────────────────────┐
                    │ Data plane: VPN gateways + DNS resolvers      │
                    │  - per-gateway hardening, no admin exposure   │
                    │  - mTLS/keys per device, revocation checks    │
                    └──────────────────────────────────────────────┘
```

Rules:

- The data plane accepts **VPN traffic only**; no management API on gateways.
- Control-plane credentials never authorize data-plane configuration directly;
  tokens are scoped and short-lived.
- The client trusts only signed/verified profiles and server certificates; the
  backend never disables client-side verification.
- Admin/control-plane access is separated from the public API edge, with its
  own authentication, allow-listing, audit logging, and no shared secrets.

## 3. Control plane

### 3.1 Accounts, authentication, MFA

- Account identity in the control plane; email/password plus an MFA second
  factor (TOTP or WebAuthn/passkeys preferred; SMS only as a fallback with
  documented risk).
- Session tokens: short-lived access tokens (minutes), refresh with rotation,
  revocation on logout/device removal/password change; no long-lived bearer
  tokens in the client.
- Password storage: Argon2id or bcrypt with per-user salt and sane work factors;
  breached-password screening; rate-limited login with generic errors to avoid
  account enumeration.
- Account recovery must not become a bypass: recovery links are single-use,
  short-lived, and require the same MFA/identity checks as login.

### 3.2 Device management

- Each install/device enrolls once, gets a unique device identity
  (key pair stored in Android Keystore / iOS Secure Enclave on a future client)
  and a device-scoped credential.
- Unique, revocable device credentials (no shared client certificates, no
  "universal" API key in the app).
- Device list per account: name, platform, last seen, created; user-initiated
  removal revokes tokens and any device certificate.
- Lost-device flow revokes the device credential and token family server-side;
  the app must handle a revoked credential with an explicit error.
- Server-side authorization is enforced per request (device belongs to
  account, subscription active, credential not revoked); the client is never
  trusted to enforce entitlement.

### 3.3 PKI

- **Offline root CA**, kept offline in an HSM/air-gapped machine; signs only an
  intermediate (or per-purpose intermediates: server auth, client/device
  auth).
- Intermediate keys online but in an HSM/KMS with audit logging and a
  documented rotation/revocation plan.
- Server certificates: short validity (e.g. ≤ 90 days), automated renewal,
  `serverAuth` EKU (the Android client's PR #5 `remote-cert-tls server`
  enforcement makes non-`serverAuth` leaves fail), hostname/SAN correct.
- Per-device client credentials issued by the intermediate (or short-lived
  certificates) with revocation lists/OCSP; the data plane checks
  revocation/authorization at connect.
- Revocation is authoritative server/pipeline-side. The Android client has no
  CRL/OCSP implementation (ADR-009) and must not claim one.
- Key ceremonies, HSM backups, and compromise/rotation runbooks are required
  before production.

### 3.4 Subscriptions/entitlements

- Entitlement is backend state, not a client flag; the gateways/API check it
  server-side.
- Grace period/refunds/chargebacks handled server-side with explicit device
  revocation semantics; the client surfaces the backend error honestly.

## 4. Public API edge

- TLS 1.2+ only (prefer TLS 1.3), HSTS, strong ciphers; certificate pinning is
  a client decision, but the API contract must allow pin rotation without
  bricking old clients (pin sets with overlap and a published rotation plan).
- Authenticated endpoints behind short-lived tokens; per-account and per-IP
  rate limits; request size limits and strict schema validation.
- Generic error responses (no account/device enumeration); structured audit
  logs without secrets/tokens (hash identifiers where needed).
- WAF/bot protection for signup/login; abuse detection for credential
  stuffing; alerting on auth anomaly rates.
- Versioned API; clients send app version/platform (no device fingerprinting
  beyond what the security model requires) and can be forced to upgrade via a
  minimum-version response.

### 4.1 Client contract (future, not implemented)

The Android client would need, at minimum:

1. `POST /v1/enroll` — authenticated enrollment; returns a device id + public
   key registration result.
2. `GET /v1/profiles` — signed profile(s)/gateway list bound to the device,
   with validity window; the client verifies the signature and server trust
   before import/use (same strict trust policy as today).
3. `POST /v1/token/refresh` — rotation; server can revoke on device removal.
4. `POST /v1/devices/{id}/revoke` (user/web, not necessarily app) — revokes the
   device credential.
5. Error contract for revoked credential, expired entitlement, blocked device.

The current app does **not** call any of these; it remains a profile-import
client. Any future integration must preserve the local profile/credential
encryption and the ADR-008/009 fail-closed behaviour.

## 5. Data plane

- VPN gateways run the OpenVPN server (and a future WireGuard/other gateway as
  a separate decision), with:
  - hardened OS images, minimal services, no management SSH exposed to the
    internet (bastion/VPN-admin path instead);
  - kernel/network hardening, no IP forwarding outside the VPN role;
  - TLS/control-channel hardening (tls-crypt/tls-crypt-v2 where supported),
    AEAD data ciphers, compression disabled (`compression no`,
    `allow-compression no`);
  - `remote-cert-tls client`/CRL/OCSP checks against the device PKI;
  - per-gateway resource limits and DDoS protection; no shared hostnames
    beyond the API edge.
- DNS resolvers:
  - dedicated recursive resolvers inside the data plane, reachable only from
    gateways; no open resolvers;
  - DNSSEC validation where feasible; logging off or minimized (no query
    content retention) and documented;
  - response-rate limiting, cache poisoning protections, egress restricted to
    the resolver's upstreams.
- No inbound management endpoints on gateways; configuration changes via
  audited control-plane automation.

## 6. Monitoring, abuse, and incident response

- Central logs/metrics/traces with secret redaction; separate audit log for
  admin/PKI actions (who/what/when, immutable retention).
- Alerts: auth failure spikes, credential stuffing patterns, gateway saturation,
  certificate expiry, revocation pipeline failures, DNS anomalies, unusual
  egress/abuse reports.
- Abuse process: report intake, evidence retention rules, per-device
  throttling/revocation with an appeal path, abuse-policy page.
- Incident response plan: severity levels, on-call, containment (revoke
  credentials/tokens/certs, block gateway), external communication, and
  post-incident review. Align with the client's disclosure SLA in `SECURITY.md`
  only where the same repository is affected; the backend has its own policy.
- Backups: encrypted, tested restores, protected from admin-plane compromise;
  no plaintext credential stores.

## 7. Ownership and runbooks (placeholders)

The following owners/runbooks are **required before production** and are not
currently staffed by this repository:

| Area | Owner (to be assigned) | Runbook status |
|---|---|---|
| Account/auth/MFA API | TBD | not written |
| PKI root/intermediate ceremonies + rotation | TBD | not written |
| Gateway fleet + network/DDoS | TBD | not written |
| DNS resolvers | TBD | not written |
| Monitoring/alerting/on-call | TBD | not written |
| Incident response / abuse handling | TBD | not written |
| Privacy review / DPA with providers | TBD | not written |

A backend ASVS (OWASP Application Security Verification Standard) review and an
independent penetration test are required before launch; see
`docs/security/masvs-gap-assessment.md`.

## 8. Threat model summary (target)

- Account takeover → mitigated by MFA, token rotation, device revocation,
  anomaly detection.
- Stolen device credential → revoked server-side; short-lived tokens limit the
  window; server-side authorization.
- Compromised intermediate CA → revocation/rotation runbook, short-lived
  certs, monitoring; offline root limits blast radius.
- Gateway compromise → segmented management, minimal services, no shared
  secrets with control plane, audited automation.
- DNS abuse/open resolver → egress restrictions, RRL, no open recursion.
- Insider/admin abuse → audit logs, least privilege, no standing production
  access, break-glass process.

## 9. Phasing

1. **Phase 0 — decision.** Keep the client profile-only; write this document
   (done); separate follow-up issues.
2. **Phase 1 — identity + API edge.** Accounts, MFA, device enrollment,
   signed profile delivery, revocation; ASVS L1–L2 target.
3. **Phase 2 — PKI + gateways.** Offline root/intermediate, per-device certs,
   gateway hardening, revocation at connect, monitoring.
4. **Phase 3 — hardening/assurance.** Abuse, IR drills, ASVS L2 review,
   independent pen test, privacy review, operational SLAs.

Each phase needs its own repository/threat model/CI; none of it is claimed by
this Android PR.

## References

- ADR-014 (scope decision)
- Issue [#16](https://github.com/smhasnanmonir/openvpn/issues/16)
- `docs/security/masvs-gap-assessment.md` (ASVS note)
- `SECURITY.md` (client disclosure and patch SLA)
- `docs/architecture/ios-plan.md` (future iOS client contract)
