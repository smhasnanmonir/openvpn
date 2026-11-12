# Remaining-work audit: what else this client needs — 2026-11-12

Companion to `docs/security/code-audit-2026-11-12.md` (code findings). This file is the
gap list: what the project still needs before it can honestly be called a finished,
secure, lightweight Android OpenVPN client. Sources: this week's code audit, `AGENTS.md`
status snapshot, `docs/security-readiness.md`, and the open issue tracker (#15/#16/#20/#21).

Ordered by what blocks release first.

---

## A. Release-blocking (process, not code)

### 1. The v1.5.0 signed APK + SBOM were never published

- **Problem:** The `v1.5.0` tag job failed: build-tools 37 `apksigner` prints
  `V3.0 Signer: certificate SHA-256 digest:` but the release gate only matched the old
  `Signer #1 certificate SHA-256 digest:` format. The GitHub Release was never created.
  The fix sits unmerged on `fix/ci-release-gate-and-backlog`.
- **Where:** `.github/workflows/android.yml` (release job certificate gate).
- **Fix:** Merge `fix/ci-release-gate-and-backlog`, then re-run the tag job — either
  recreate `v1.5.0` or cut `v1.5.1`. After that, verify the Release has both the signed
  APK (both ABIs, 16 KB-aligned) and the CycloneDX SBOM attached.
- **If we skip it:** There is no shippable artifact for the current toolchain; users and
  the Play track are stuck on v1.4.0.

### 2. Privacy policy is not yet a real public URL

- **Problem:** `BuildConfig.PRIVACY_POLICY_URL` (`app/build.gradle.kts` line 43–47)
  points to a GitHub `blob/main` markdown URL. That technically renders in a browser,
  but the Play Console Data safety section and in-app Settings link should point at a
  stable published page, and the Play declarations are not submitted.
- **Where:** `app/build.gradle.kts`, `docs/legal/README.md` (publication runbook),
  `docs/legal/play-store-vpn-disclosure.md`.
- **Fix:** Follow `docs/legal/README.md`: publish the policy (GitHub Pages on the same
  repo is the lightest option — no new infrastructure), update `PRIVACY_POLICY_URL`,
  submit the Play VpnService/specialUse/`QUERY_ALL_PACKAGES` declarations and the Data
  safety form. Then issue #15 closes with external evidence.

## B. Verification gaps — claims currently made without evidence

These are all already documented as open; they are repeated here because they are the
difference between "tested on an emulator" and "secure".

### 3. Physical-device leak-test matrix has never been run

- **Problem:** Every runtime security claim (no DNS leak, no IPv6 leak, kill switch via
  Always-on lockdown, network switch, airplane mode, captive portal, process death,
  reboot, other-VPN takeover) is verified only by unit tests and one emulator tunnel.
  The matrix exists (`docs/security/leak-test-matrix.md`) but has no recorded results.
- **Fix:** Execute the matrix on at least two physical devices (one Android 15+ / 16 KB
  page-size device), with packet capture at the network boundary (e.g. a router-level
  tcpdump or a Wireshark on the AP). Record results per row in `docs/testing.md`. Issue
  #21 stays open until this exists.
- **If we skip it:** The app's core promise ("never silently leak") is asserted, not
  shown.

### 4. 16 KB-page app launch is unverified

- **Problem:** ELF alignment of every packaged `.so` is verified (`p_align 0x4000`,
  CI gate), but the app has never actually launched on a 16 KB device — the
  `google_apis_ps16k` emulator image crash-loops SurfaceFlinger on this host.
- **Fix:** Run one launch/connect cycle on any real 16 KB device (Pixel 8+ with the 16
  KB kernel) or a working CI emulator image. One afternoon; closes the last Android 15+
  requirement (issue #12 residual).

### 5. Instrumentation tests have not run since v1.4.0

- **Problem:** 9 instrumentation tests (import, native lifecycle, Keystore, user/pass)
  passed on emulator API 36.1 for v1.4.0. v1.5.0 changed the toolchain (AGP 9.5.1,
  Kotlin 2.4.21) and CI does not run instrumentation at all.
- **Fix:** Re-run `:app:connectedDebugAndroidTest` once on a local emulator for the
  current release candidate. Longer term: add a single emulator job to CI
  (`androidTest` on every push is the next step up; start with scheduled/nightly to
  keep the "lightweight" CI bill down).

### 6. Independent penetration test not commissioned

- **Problem:** The scope document is complete
  (`docs/security/pen-test-scope.md`: rules of engagement, release gate) but no test
  has been performed. Issue #21 is open on exactly this.
- **Fix:** Commission it against the release gate in that document. If budget is the
  blocker, scope it to the two areas automated tests cannot reach: the native JNI
  boundary (`native_bridge.cpp`) and profile parsing of hostile `.ovpn` input.

## C. Code-level additions found by this audit

These overlap with `docs/security/code-audit-2026-11-12.md` — one-line summaries here,
full detail there.

### 7. FLAG_SECURE on MainActivity (must-fix #1 in the audit)

- **Problem:** Recents/screenshot exposure of server host, public IP, and logs; AGENTS.md
  documents an invariant that does not exist.
- **Fix:** Two lines in `MainActivity.onCreate` + AGENTS.md correction.

### 8. Log redaction markers + parity test (audit #2)

- **Problem:** `auth-user-pass` inline credentials can reach the diagnostics buffer;
  marker list duplicated between Kotlin and C++.
- **Fix:** Add markers to both lists; add one `LogSanitizer` unit test.

### 9. Challenge-response handoff consistency (audit #3)

- **Fix:** Route the response through `CredentialHandoff` instead of an Intent extra.

### 10. Encrypt or formally accept plaintext split-tunnel metadata (audit #4)

- **Fix:** Decision needed; encrypting via the existing `SecretBox` is ~15 lines.

## D. Deliberate absences — recommend keeping them absent

These come up on every "what else should we add" list. They are consciously out of the
product; adding them would make it heavier or weaker, and each is documented:

- **In-app kill switch:** Android's Always-on + "Block connections without VPN" is the
  only reliable system-level kill switch. The app already fails visibly and never lies
  about protection while paused. A custom kill switch (firewall rules inside the app)
  duplicates the platform badly. Keep: guide users to lockdown (`ui/SettingsScreen`
  readiness card, issue #9, done).
- **Crash reporting / analytics SDK:** absent on purpose (privacy stance, AGPL product,
  no backend). The sanitized in-app diagnostics log with clipboard export is the
  replacement. Do not add a crash SDK without a new product decision; if field crash
  data ever becomes necessary, make it opt-in, local-only, share-via-clipboard.
- **CRL/OCSP in the client:** revocation is server/PKI-side; documented in ADR-009.
- **Built-in public VPN provider list / auto-config fetch:** would require a backend and
  a control plane (#16 is an open scope decision, deliberately not code).
- **WireGuard / multi-protocol:** out of scope; this is an OpenVPN 3 client with one
  pinned core.

## E. Platform decisions that remain open (not this repo's work)

- **#16 backend/control plane:** fully documented plan
  (`docs/architecture/backend-control-plane.md`, ADR-014); needs a business decision and
  its own repository before any code.
- **#20 iOS client:** documented plan (`docs/architecture/ios-plan.md`, ADR-015,
  App Store 5.4 checklist); separate product decision.

Both stay open issues until a decision owner accepts them; nothing to add here.

---

## Suggested order

1. Merge `fix/ci-release-gate-and-backlog` → re-tag v1.5.0 (or v1.5.1) → verify Release
   assets. (§1)
2. Ship the audit's three small code fixes (FLAG_SECURE, log markers, challenge handoff)
   as one tiny PR; they belong in the same release. (§7–§9)
3. Publish privacy policy + submit Play declarations. (§2)
4. Run the physical-device matrix + one 16 KB launch + re-run instrumentation. (§3–§5)
5. Commission the pen test. (§6)

## Verdict

The code is in good shape — the remaining work is evidence, not engineering: publish the
release, run the tests on real hardware, submit the store declarations, and buy the
pen-test. Only two of the ten items above write new app code, and all three code changes
together are under ~40 lines.
