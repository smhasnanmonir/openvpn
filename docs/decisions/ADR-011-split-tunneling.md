# ADR-011: Per-app split tunneling with explicit leak warnings

## Status

Accepted.

## Context

Android `VpnService` can route only a selected set of apps
(`Builder.addAllowedApplication`) or exclude a selected set
(`Builder.addDisallowedApplication`). Split tunneling is a common feature
request and a common leak source: users enable it for banking apps, local
printers or games and do not realise that excluded apps leave the device
directly, outside the tunnel. Issue #17 asks for the feature with honest
warnings, not just the routing calls.

The app previously had no per-app UI and stated split tunneling as a
non-goal. That was the safest position while the feature did not exist. The
product decision for this PR is to expose it with explicit exposure warnings.

## Decision

1. **Global setting with three modes.** `SplitTunnelMode`:
   - `OFF` (default): every app uses the tunnel. This preserves the existing
     full-tunnel behaviour for current users.
   - `INCLUDE_SELECTED`: only the selected packages use the tunnel; every other
     app bypasses it.
   - `EXCLUDE_SELECTED`: the selected packages bypass the tunnel; every other
     app uses it.
2. **Dedicated per-app picker screen.** The settings card opens a package
   picker that lists installed apps (label, package name, icon), supports
   search, and persists the selected package set. The selection is part of
   `AppSettings` and is applied when the TUN is created, using
   `addAllowedApplication`/`addDisallowedApplication` on the
   `VpnService.Builder`.
   - **Package visibility (Android 11+).** Enumerating arbitrary installed apps
     and passing their package names to the builder requires the Play-restricted
     `QUERY_ALL_PACKAGES` permission. Without it, package-visibility filtering
     hides most apps and `Builder.addAllowedApplication`/
     `addDisallowedApplication` throws `NameNotFoundException`, so split
     tunneling would silently degrade. The permission is declared in the
     manifest, justified in the Play VpnService/restricted-permission
     declaration (`docs/legal/play-store-vpn-disclosure.md`), and used only for
     the local picker and connect-time validation — the package list is never
     transmitted, logged, or used for analytics (`docs/legal/privacy-policy.md`,
     `docs/security.md`).
3. **Leak warnings are always visible when mode is not OFF.** Home and
   Settings show a warning that names the exposure, e.g. "N apps bypass the
   tunnel — their traffic is not protected", with the count and a way to review
   the list. The Connected state is not allowed to imply that bypassing apps
   are protected.
4. **Validation: never create a tunnel that covers zero apps.** An
   `INCLUDE_SELECTED` selection with no usable package would build a VPN that
   routes nothing; that is a silent failure and a confusing state. The service
   calls `SplitTunnelPolicy.validate` at TUN establishment time (fail-closed),
   refuses the connection with a clear error, and asks the user to select at
   least one app. An `EXCLUDE_SELECTED` list that covers every installed app is
   allowed only if it still leaves at least one app in the tunnel; the picker
   and connect-time validation prevent an empty-tunnel configuration.
   The service re-reads `SettingsRepository` at establish time (so a change made
   while the app was backgrounded is honoured), logs the mode and the effective
   app count, and logs the explicit leak warning from `SplitTunnelPolicy.warning`
   when apps bypass the tunnel. It never claims that bypassing apps are
   protected.
5. **Uninstalled packages are handled.** Package names in the persisted set
   may disappear after an uninstall or profile switch. At connect time the
   policy filters to packages that currently resolve through `PackageManager`;
   the picker drops stale entries, and the warning count reflects only the
   effective set. Missing packages never crash the connection.
6. **Changes apply on the next connection, and the UI offers a reconnect.**
   Android's per-app lists are fixed at `establish()` time. Changing the
   setting while connected does not rewrite the live tunnel; the UI says the
   change takes effect on the next connection and offers Reconnect. Selection
   is not changed silently mid-session.
7. **Policy is global, not per-profile.** A per-profile split-tunnel policy was
   considered and deferred: it multiplies the state and validation surface, and
   the warning text would have to explain which profile is active. This ADR
   records the global decision; a per-profile model needs its own ADR.

## Consequences

- Split tunneling is **off by default**; existing users keep full-tunnel
  routing. Enabling it is an explicit, warned choice.
- Excluded/include-bypassing apps genuinely send traffic outside the tunnel.
  This is the feature, not a bug, and the app labels it. The leak-test matrix
  (`docs/security/leak-test-matrix.md`) includes a split-tunnel case with
  packet capture to prove the bypass and the warning.
- `addAllowedApplication` has an Android limit on the number of packages
  (implementation-defined, historically ~100); the picker should surface the
  platform failure instead of pretending the tunnel was set up.
- Per-app routing interacts with profile routes/excluded routes from Core:
  Android's per-app lists decide *which apps* use the VPN; the VPN's routes
  decide *which destinations*. The app does not merge or rewrite
  profile-provided routes.
- A VPN that only covers selected apps can still be a full-tunnel VPN for those
  apps; the warning is about the apps that bypass it.
- The policy is pure Kotlin (`SplitTunnelPolicy`) and unit-tested; actual
  routing must be verified on a device with packet capture (controlled server).

## Alternatives considered

- **No split tunneling (keep the non-goal).** Safest, but issue #17 is an
  accepted product requirement and users otherwise disable the VPN or add
  manual routes.
- **Per-profile split tunneling.** Deferred; documented above.
- **Silent default to exclude common apps (e.g. banking).** Rejected: silently
  bypassing apps is exactly the leak the warnings exist to prevent.
- **Allow include mode with zero apps.** Rejected: an empty tunnel is a
  configuration error and must fail loudly.

## References

- Issue: [#17](https://github.com/smhasnanmonir/openvpn/issues/17)
- `app/src/main/java/com/smhasnan/openvpn/vpn/SplitTunnelPolicy.kt`
- `app/src/main/java/com/smhasnan/openvpn/ui/SplitTunnelScreen.kt`
- `app/src/main/java/com/smhasnan/openvpn/vpn/MyVpnService.kt`
- `app/src/main/java/com/smhasnan/openvpn/settings/SettingsRepository.kt`
- `docs/security.md`, `docs/security/leak-test-matrix.md`
