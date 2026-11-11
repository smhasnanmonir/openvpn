# Privacy policy — OpenVPN Client for Android

**App:** OpenVPN Client for Android, package `com.smhasnan.openvpn`
(debug variant `com.smhasnan.openvpn.debug`)
**Policy version:** 1.0
**Last updated:** 2026-11-11
**Source of truth:** this document (`docs/legal/privacy-policy.md`) in the
project repository. Publish it at a stable public HTTPS URL and use that URL in
Google Play Console and in the app's store listing before distribution.

## 1. Summary

This app is an **open-source OpenVPN client**, not a VPN service. It lets you
import your own `.ovpn` profile, store it encrypted on your device, and open an
encrypted OpenVPN tunnel to **the VPN server you configured**. The app has no
user accounts, no backend of its own, no analytics, no advertising SDK, no crash
reporting SDK, and no developer-operated server that receives your data. The
maintainer does not collect or sell personal data.

The only app-initiated request to a third party, other than the VPN tunnel you
configure, is the **optional public-IP check** (section 5). Everything else the
app stores stays on your device.

## 2. Who is responsible

The app is maintained by the maintainer of the project's public repository
(<https://github.com/smhasnanmonir/openvpn>). For privacy questions, open an
issue in that repository or use the repository's private security reporting
channel for sensitive matters (see `SECURITY.md`). Do not include real
credentials, private keys, or full profiles in any report.

The project does not operate a company, account system, or data-processing
backend. If you use the app against a commercial VPN provider's server, **that
provider is a separate data controller** and its own privacy policy applies to
the VPN traffic and any authentication data it receives.

## 3. What the app stores on your device

All of the following is stored in the app's private storage and is not uploaded
to the maintainer:

- **VPN profiles you import.** A profile can contain server addresses, routes,
  DNS settings, and inline certificates, keys, and other profile material. The
  full profile content is encrypted with AES-256-GCM using a non-exportable key
  held in the Android Keystore, and stored in the app's private
  `noBackupFilesDir`.
- **Credentials you choose to save.** Optional VPN username, password, and
  private-key passphrase are encrypted with a separate Android Keystore key.
  Static and dynamic challenge responses are never persisted.
- **App settings.** Selected profile, theme, app-lock preference, DNS/MTU/
  split-tunnel/compression/cipher/security preferences, and similar local
  preferences. If you use split tunneling, the settings also store the package
  names you selected (see section 6).
- **Installed-app list (split tunneling only).** To let you choose which apps
  use the VPN, the app asks Android's `PackageManager` for the installed apps'
  package names and user-visible labels and shows them in the per-app picker.
  This list is read on the device to populate the picker and to validate the
  selection when the tunnel is created; the selected package names are stored
  in the app's private settings. **The package list is never transmitted to the
  maintainer or to any third party, is not used for analytics, advertising or
  profiling, and the app does not access other apps' data — only the package
  metadata Android exposes.**
- **Diagnostics/logs.** Sanitized engine log lines are kept **in memory only**
  for the current app session; they can be cleared by you and are not written to
  disk by the app.
- **App-lock biometric state.** The app uses Android's `BiometricPrompt`; your
  fingerprint/face data never leaves the Android system and is not accessible to
  the app.

Android backup is disabled for this app (`allowBackup=false` and data-extraction
rules that exclude all domains), so this local data is not transferred to your
Google account or to a new device by Android backup.

## 4. What the app does not do

- No analytics, telemetry, advertising, tracking, or profiling.
- No Firebase, crash-reporting SDK, push messaging, or third-party SDK that
  receives app data. The app's only third-party network destination is the
  optional public-IP service described below and the VPN server from your
  profile.
- No developer backend: the maintainer does not receive your profiles,
  credentials, logs, traffic, or identifiers.
- No collection of location, contacts, SMS, call logs, photos, files (outside
  the `.ovpn`/companion files you explicitly select through Android's document
  picker), microphone, camera, or advertising identifiers.
- No sale, rental, or sharing of personal data for advertising or any other
  commercial purpose.

## 5. Network connections the app makes

1. **Your VPN tunnel.** When you connect, the app and OpenVPN 3 Core connect to
   the VPN server specified in your imported profile, using the transport
   (UDP/TCP), TLS settings, and credentials in that profile. Traffic inside the
   tunnel is encrypted between your device and that server. The app does not
   inspect, log, or forward the contents of your traffic. The VPN server
   operator processes your traffic and connection metadata under its own
   policy; the app cannot control that.
2. **Optional public-IP check.** If "Automatic IP check" is enabled (it can be
   disabled in Settings), the app makes an HTTPS request to
   `https://api.ipify.org` through the currently active network (through the
   tunnel when the VPN is connected) to display your current public IP address.
   The service necessarily sees your device's public IP address and standard
   HTTPS connection metadata (such as the request time), and its operator's
   privacy policy applies. The app sends no profile, credential, account, or
   advertising data, does not add cookies, does not store the response beyond
   the current UI check, and uses the result only to display the address and
   label the egress path ("via tunnel" / "direct"). The check is a convenience
   signal, not a leak test.
3. **Android platform services.** The app interacts with Android system
   components (VPN consent, notifications, Quick Settings tile, biometrics,
   document picker). Those interactions are governed by Android/your device
   settings.

No other network requests are made by the app.

## 6. Permissions and why they are used

| Permission | Purpose |
|---|---|
| `INTERNET` | Open the VPN tunnel and perform the optional public-IP check. |
| `ACCESS_NETWORK_STATE` | Detect network changes and metered status for reconnection and diagnostics. |
| `POST_NOTIFICATIONS` | Show the foreground VPN notification (Android 13+). Optional; the app works without it but the control surface is reduced. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the VPN session alive while the app is in the background (declared as `specialUse` with subtype `vpn`). |
| `USE_BIOMETRIC`, `USE_FINGERPRINT` | Optional app-lock. No biometric data is accessed by the app. |
| `QUERY_ALL_PACKAGES` | Split tunneling needs to list installed apps and pass their package names to Android's `VpnService` per-app allow/deny lists (Android 11+ otherwise hides most packages). Used only for the local per-app picker and connect-time validation; the package list is never transmitted, logged, or used for tracking. This is a Play-restricted permission and is declared for the VPN split-tunneling use case. |

VPN permission itself is granted by Android through `VpnService.prepare()`;
the app never auto-consents.

## 7. Data sharing

- **No sharing with the maintainer.** The maintainer receives nothing.
- **Public-IP service:** the optional check contacts `api.ipify.org` as
  described above. That is the only third-party service the app itself selects.
- **Your VPN provider:** the server in your profile receives your VPN connection
  and traffic, as any VPN server does.
- **Legal requirements:** the maintainer has no server-side data to disclose.
  The app itself does not disclose on-device data to third parties except as
  described in this policy.

## 8. Retention

- Profiles, credentials, and settings remain on your device until you delete
  them in the app, clear the app's storage, or uninstall the app.
- In-memory diagnostics are discarded when the app process ends or when you
  clear the logs.
- The maintainer retains no data because it receives none.

## 9. Your choices and rights

- Delete a profile and its saved credentials at any time in the app.
- Uninstall the app or clear its storage to remove all local data. Because
  backup is disabled, no copy is kept by Android backup.
- Disable the automatic public-IP check in Settings.
- Disable or uninstall the app at any time; no account deletion is needed
  because there is no account.
- Depending on your jurisdiction you may have rights to access, correct, delete
  or port personal data, or to object to processing. As the maintainer holds no
  personal data, those requests cannot be fulfilled by the maintainer; exercise
  them locally by deleting app data. If your concern relates to your VPN
  provider, contact that provider.

## 10. Security

The app is designed to keep local material private and to fail visibly rather
than silently weaken protection:

- profiles/credentials encrypted at rest with Android Keystore-backed
  AES-256-GCM; backup disabled;
- TLS and certificate verification performed by OpenVPN 3 Core/mbedTLS; the app
  never disables certificate verification;
- transport sockets protected from the tunnel with `VpnService.protect()`;
- sanitized, in-memory-only diagnostics; no secrets in logs;
- fail-closed DNS and strict profile trust policy (see `docs/security.md`).

No method of storage or transmission is perfectly secure. A rooted or
compromised device, a malicious VPN server, or a profile that deliberately
weakens its own configuration is outside what the app can protect against. See
`docs/security.md` for the full threat model.

## 11. Children

The app is a VPN networking tool and is not directed at children. It does not
knowingly collect personal data from children (it collects no data at all for
the maintainer).

## 12. International transfers

The maintainer operates no backend, so there is no transfer of personal data to
the maintainer. The optional public-IP request goes to the third-party service
`api.ipify.org`, which may operate servers outside your country; its operator's
policy applies. Your VPN provider may process data in other countries under its
own policy.

## 13. Legal basis (where GDPR applies)

Where the GDPR applies, the maintainer processes no personal data. The optional
public-IP request is initiated by you by enabling the feature and is used solely
to display the current public IP; the app's use of the third-party service is
covered by your consent to enable the feature (which you can withdraw by
disabling it). VPN connections are made to the server you configured, under your
relationship with that provider.

## 14. Changes to this policy

If the app's data behaviour changes (for example if a future backend or
analytics is added), this policy will be updated in the repository and the
policy version and date changed before the change ships. The current policy URL
is the one registered in the store listing; material changes will be reflected
there.

## 15. Contact and complaints

Open an issue at <https://github.com/smhasnanmonir/openvpn/issues> for ordinary
questions, or use the repository's private security-reporting channel for
sensitive matters (see `SECURITY.md`). Do not send credentials, private keys, or
full profiles. If you believe your data has been mishandled by your VPN
provider, contact that provider; if you believe this app has mishandled it,
contact the maintainer through the repository.

## 16. Open-source licence

The app is licensed under AGPL-3.0-only; the source is the public repository and
its pinned OpenVPN 3 Core submodule. The licence governs the software, not your
personal data.
