# Legal and store documents

This directory holds the user-facing privacy policy and the Google Play answers
that accompany a release. The documents are part of the release evidence: a
tag must not be published if the policy no longer matches the shipped build.

| File | Purpose | Owner | Status |
|---|---|---|---|
| [`privacy-policy.md`](privacy-policy.md) | Public privacy policy (source of truth). | Maintainer | Complete, 1.0 |
| [`play-store-vpn-disclosure.md`](play-store-vpn-disclosure.md) | Play `VpnService`, Data safety, `specialUse` and `QUERY_ALL_PACKAGES` answers plus listing text. | Maintainer | Complete, ready to submit |
| [`../compliance/release-checklist.md`](../compliance/release-checklist.md) | Per-release compliance gate (build, signing, store, supply chain). | Maintainer | Complete |

## 1. Canonical privacy-policy URL

Until a custom domain is configured, the canonical public URL is the rendered
document on the default branch:

<https://github.com/smhasnanmonir/openvpn/blob/main/docs/legal/privacy-policy.md>

The app links to it from **Settings → About → Privacy policy**. The URL lives in
`app/build.gradle.kts` as `PRIVACY_POLICY_URL`; change it there (and here) if the
policy moves to a custom domain.

The URL is public, reachable without login, and is the one that must be entered
in Play Console and the store listing. Do not use a draft branch URL, a raw file
host, or a redirect that requires authentication.

## 2. Publishing the policy (required before distribution)

1. Confirm `privacy-policy.md` still matches the shipped build: permissions,
   foreground-service types, network destinations (only the optional
   `api.ipify.org` check and the user's VPN server), and the no-analytics /
   no-backend statements.
2. Merge the change to `main`, then open the canonical URL above in a private
   browser window (no GitHub login) and confirm that it renders.
3. Enter the same URL in **Play Console → Store settings → Privacy policy** and
   in the store listing.
4. Install the release APK and open **Settings → About → Privacy policy**; the
   browser must reach the same page.
5. If a custom domain replaces the GitHub URL, keep the old URL redirecting for
   at least one release and update `PRIVACY_POLICY_URL`, this file, the release
   checklist and the Console entry together.

Do not mark the policy as "published" in issue #15 until step 2 passes on
`main`.

## 3. Play Console submission checklist

All answers are derived from `play-store-vpn-disclosure.md`; that document has
the exact wording. Submit the items in this order so a rejected answer does not
block the rest.

- [ ] **App content → VPN service**: declare that the app uses `VpnService` to
      run a user-configured OpenVPN tunnel, that traffic is not inspected or
      monetised, and that it is the app's core functionality.
- [ ] **App content → Data safety**: no data collected or shared by the
      developer; optional public-IP check disclosed; app data (profiles,
      credentials) stored on-device only and encrypted.
- [ ] **App content → Foreground service types**: `specialUse` justification,
      plus the `vpn` type for the tunnel service.
- [ ] **App content → Restricted permissions**: `QUERY_ALL_PACKAGES`
      declaration for the per-app split-tunnel selector, used on-device only.
- [ ] **Store listing → Privacy policy**: canonical URL from section 1.
- [ ] **Store listing → App access**: no login or account is required.
- [ ] **Store listing → Ads**: no ads. **Content rating / target audience**:
      not child-directed. **Government apps**: no.
- [ ] **Release**: upload the signed CI APK, confirm the version name/code and
      certificate match the tag, and keep the SBOM with the release record.

## 4. Status of issue #15

| Acceptance criterion | Repository deliverable | Status |
|---|---|---|
| Privacy policy published and linked from the app/store listing | `privacy-policy.md`, canonical URL in section 1, Settings → About link | URL is public on `main`; Play Console entry and store listing are manual (section 3) |
| Play `VpnService` and Data safety declarations complete | `play-store-vpn-disclosure.md` sections 2–5 | Wording ready; Console submission is manual |
| Release checklist covers store compliance | `docs/compliance/release-checklist.md` section 4 | Done |
| Public-IP provider disclosed | `privacy-policy.md` sections 5 and 7; in-app About text | Done |

The remaining work is account-holder action in Play Console and the store
listing; it cannot be completed from this repository.
