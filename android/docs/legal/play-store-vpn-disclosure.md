# Google Play VpnService disclosure and store answers

This document records the truthful Play Console answers for the Android
OpenVPN client (`com.smhasnan.openvpn`). It is a working document for the
maintainer; Play Console labels change over time, so map each section to the
current equivalent field and keep the answers consistent with the app.

> **Prerequisite:** publish `docs/legal/privacy-policy.md` at a stable public
> HTTPS URL first and use that URL everywhere a privacy-policy URL is required.
> This repository is the source of truth for the text; do not link to a
> repository file path from Play.

## 1. What the app is (and is not)

- The app is an **OpenVPN client**: a user imports a `.ovpn` profile and the app
  uses Android `VpnService` plus OpenVPN 3 Core 3.9 to open an encrypted tunnel
  to **the VPN server configured in that profile**.
- The app **does not operate VPN servers**, does not sell VPN service, has no
  accounts, no subscriptions, and no developer backend.
- There are no ads, no analytics, no crash-reporting SDK, no tracking, and no
  data sale.

## 2. `VpnService` declaration (App content → VPN service)

Suggested declaration text:

> This app is an OpenVPN client. It uses Android's `VpnService` API only to
> create and manage a device-wide VPN tunnel using an `.ovpn` configuration
> that the user imports. The app does not provide or operate its own VPN
> servers; the user selects the VPN server through their own profile. The
> `VpnService` is used exclusively for VPN functionality: creating the TUN
> interface, applying the profile's routes/DNS/MTU, and protecting the OpenVPN
> transport socket so it does not route into its own tunnel.

Answers:

| Console item | Answer |
|---|---|
| Does the app use `VpnService`? | Yes — the app is a VPN client and its core function requires it. |
| Does the app operate a VPN service / sell VPN service? | No. It is a client for user-supplied profiles; no server infrastructure is operated by the developer. |
| Does the app collect user data through the VPN? | No. Traffic contents are not inspected, logged, stored, or transmitted to the developer. Profiles and credentials stay encrypted on the device. |
| Does the app redirect, manipulate, or inject ads/traffic? | No. The app does not modify traffic and does not inject content. |
| Does the app use the VPN to bypass platform billing/security? | No. |
| Does the app share data with third parties? | Only the optional public-IP check contacts `api.ipify.org`; the VPN server is the one in the user's profile. See the privacy policy and Data safety answers. |
| Is the VPN permission requested with user consent? | Yes, through the Android VPN consent dialog (`VpnService.prepare()`); the app never auto-consents. |

## 3. Foreground service type declaration

The VPN service declares `android:foregroundServiceType="specialUse"` with
`android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE="vpn"` and requests
`FOREGROUND_SERVICE_SPECIAL_USE`. It does **not** claim `systemExempted`.

**specialUse justification (suggested):**

> The app is a VPN client. Its core function is a long-running OpenVPN tunnel;
> Android requires a foreground service to keep the tunnel and its notification
> alive while the app is in the background or the screen is off. Android has no
> dedicated "vpn" foreground-service type, so the service uses `specialUse`
> with the required subtype `vpn`. The service is not used for any purpose
> other than keeping the VPN session and its user-visible notification alive;
> it starts only after the user requests a connection (or after the system
> starts it for Always-on VPN) and stops when the user disconnects.

Attach a short screen recording or screenshots if the Console asks for
evidence, showing: the app open, a profile imported, the user tapping Connect,
the VPN consent dialog, and the foreground notification.

## 4. Restricted permission: QUERY_ALL_PACKAGES

The app declares `android.permission.QUERY_ALL_PACKAGES` (a Play
**restricted permission**). It is required for split tunneling:
`VpnService.Builder.addAllowedApplication`/`addDisallowedApplication` accept
arbitrary package names, but on Android 11+ (API 30+) package-visibility rules
hide installed packages from the app unless it is granted broad visibility.
Without the permission, the per-app picker cannot reliably enumerate installed
apps and the builder throws `NameNotFoundException` for packages the app cannot
see, so split tunneling would silently fail/degrade. The permission is core
functionality for the split-tunnel feature, not for advertising or analytics.

**Declaration wording (suggested):**

> This app is a VPN client that offers per-app split tunneling. The
> `QUERY_ALL_PACKAGES` permission is used solely to enumerate installed apps for
> the in-app per-app selector and to pass the user-selected package names to
> Android's `VpnService.Builder.addAllowedApplication`/
> `addDisallowedApplication` when creating the VPN tunnel. The package list and
> selection are used only on-device for this core VPN functionality; they are
> never transmitted off the device, never used for advertising, analytics,
> profiling, or tracking, and the app does not access other apps' data.
> Android 11+ package visibility otherwise prevents the app from seeing or
> applying arbitrary installed packages, which would break split tunneling.

Also state in the declaration/justification:

- The permission is requested because the VPN's `VpnService` per-app routing is
  the product's core function, not for general app-inventory collection.
- The app reads package metadata only (package name and user-visible label)
  through `PackageManager`; it does not read other apps' contents or usage.
- Uninstalled/stale package names are filtered locally; no inventory is stored
  beyond the user's selected package set.
- Data safety: the package list is processed on-device only and is not collected
  or shared; do not declare "Installed apps" as collected for the developer.
  Keep the privacy policy and Data safety answers consistent with this (both
  disclose local-only use).

> Play's restricted-permission review may request a short video or written
> explanation of the user flow. Prepare evidence: Settings → Split tunneling →
> per-app picker → select apps → Connect, showing that the permission is used
> for the VPN's per-app allow/deny lists.

## 5. Data safety form

The app itself has no developer backend and no SDK that collects data. The only
third-party network request the app initiates is the optional public-IP check
to `api.ipify.org`. Recommended primary answers:

| Question | Recommended answer | Rationale |
|---|---|---|
| Does your app collect or share any of the required user data types? | **No app-collected data** (see note) | The developer receives no data; no analytics/crash/backend; traffic is not inspected. |
| Data types collected | None by the developer. | Profiles/credentials are stored only on-device, encrypted. |
| Data shared with third parties | No data shared by the developer. The optional public-IP request contacts `api.ipify.org` (see note). | The VPN provider is the user's own choice; the developer is not a party. |
| Is all user data encrypted in transit? | Yes | VPN traffic is TLS/OpenVPN-encrypted to the user's server; the public-IP check uses HTTPS. |
| Do you provide a way for users to request data deletion? | Yes — in-app profile deletion and uninstall/clear-storage; no account exists. | No server-side data to delete. |
| Is data collection optional? | Yes — the public-IP check can be disabled; the app works without it. | |
| Families policy / children | Not designed for children. | Utility/security tool, no child-directed content. |

**Note on IP addresses (answer conservatively):** the optional public-IP check
necessarily sends the device's public IP address to `api.ipify.org` as part of
the HTTPS request, and that service sees standard connection metadata. This is
transient, not linked to an account or advertising identifier, not retained by
the developer, and used only to display the current address. If Google review
treats this as a "Device or other IDs" collection/sharing, declare it as:
*collected: no by the developer; shared with `api.ipify.org`; purpose: app
functionality; optional; ephemeral; not linked to the user; not used for
tracking*. Keep the answer internally consistent with the privacy policy, which
discloses the same behaviour; do not describe the app as collecting nothing if
the form is instead answered with the IP declared — consistency between policy,
form, and code is what matters.

Also confirm in the form:

- **No ads** (the app has no advertising SDK).
- **No account creation / login** (there is no app account; "app access" can be
  "all functionality available without special access").
- **No in-app purchases / subscriptions.**
- **Target audience:** 18+ / general utility, not child-directed; complete the
  content rating questionnaire truthfully (utility/security app, no user
  content, no ads).

## 6. Store listing disclosure text

Play's VPN policy expects VPN apps to be transparent. Suggested listing
snippets (adapt to the 80/4000-character limits):

Short description:

> Open-source OpenVPN client. Import your `.ovpn` profile and connect through
> your own VPN server.

Full description section:

> **Your server, your profile.** This is an OpenVPN client, not a VPN service.
> It does not include or sell VPN servers, accounts, or subscriptions. You
> import an `.ovpn` profile from a VPN provider or your own server; the app
> stores it encrypted on your device and uses Android VpnService to open an
> encrypted tunnel to that server.
>
> **Privacy.** No analytics, no ads, no tracking, no developer backend. Profiles
> and credentials stay encrypted on your device and are excluded from Android
> backup. Diagnostics are kept in memory only. The only optional third-party
> request is a public-IP check to api.ipify.org, which can be disabled in
> Settings. See the privacy policy: <PRIVACY_POLICY_URL>.
>
> **Security.** Certificate verification is never disabled. DNS fails closed by
> default ("Require tunnel DNS"), profiles without a CA/fingerprint are
> rejected, and transport sockets are protected from the tunnel. Compression
> compatibility mode and strict mode are explicit user choices. Android
> Always-on VPN/lockdown is the supported system kill switch; the app does not
> promise an app-level kill switch.
>
> **What the app cannot do.** It cannot protect a rooted/compromised device or
> a malicious VPN server, and it cannot guarantee leak-free operation during
> every Android network transition. The app reports what the engine reports and
> does not fabricate "Protected" states.

## 7. Target-market notes

- VPN apps are **restricted or illegal in some countries/regions** (for
  example, rules vary in China, Russia, Turkmenistan, Belarus, the UAE, Oman and
  others). Distribution and use must comply with local law; Play may not make
  the app available in restricted markets. The app is a client for a
  user-selected server, has no developer backend, and cannot enforce legal
  geofencing — the maintainer must decide distribution scope with legal advice.
- The app is not intended for use to violate sanctions, to bypass lawful
  controls, or to commit fraud. The store listing should not advertise
  "access blocked content" or similar.
- The AGPL-3.0-only licence of the pinned OpenVPN 3 Core 3.9 must be respected
  for source availability; the app is open source. Do not add proprietary
  closed-source libraries without a licensing review.
- Keep the privacy-policy URL and Data safety answers in sync with any new
  dependency, backend, or SDK. This repository currently has none of those.

## 8. Release-time compliance checklist

The full step-by-step list is in
[`docs/compliance/release-checklist.md`](../compliance/release-checklist.md).
At minimum, for every store upload:

- [ ] privacy policy published at the canonical URL in
      [`README.md`](README.md) §1, set in Console and listing, and reachable
      from the app (Settings → About → Privacy policy);
- [ ] `VpnService` declaration and `specialUse` justification present;
- [ ] Data safety answers re-confirmed against the shipped `AndroidManifest`
      and network calls (no new SDK/backend);
- [ ] target-device/16 KB note in the release notes;
- [ ] signed release APK from CI, `apksigner verify` passed;
- [ ] permissions and FGS type unchanged unless re-declared.
