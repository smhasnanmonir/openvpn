# ADR-013: Desired-state connection lifecycle (process death, sticky restart, backoff)

## Status

Accepted.

## Context

PR #5 made Always-on starts work and bounded the post-connect pause retry
(ADR-007), but the service still had no model of the user's *intent*:

- the only persisted state was the selected profile id, not "the user asked to
  be connected";
- a process death left no way to distinguish "the VPN was killed while
  connected" from "the user disconnected";
- `START_NOT_STICKY` meant the system did not restart a killed service, even
  for an Always-on VPN;
- retries were deterministic (5 s, 10 s, ...), so many clients with the same
  failure retried in lockstep;
- force-stop, reboot, and another VPN app taking over were undocumented.

Issue #14 asks for a lifecycle model that restores **intent**, never a stale
native pointer, and that behaves predictably across process death, reboot,
force-stop, and VPN takeover.

## Decision

1. **Persist the desired connected profile separately from the native session.**
   `SettingsRepository.desiredConnectedProfileId` records the profile the user
   last asked to connect to (set on a user/Always-on connect, cleared on a user
   disconnect). It is a persisted id, not an fd or a native handle. The native
   session remains process-local and is always created fresh.
2. **`START_STICKY` restores intent, not pointers.** For an Always-on system
   start and a handled null-intent redelivery the service returns
   `START_STICKY`. On restart it reads the desired connected profile, decrypts
   the stored profile/credentials, and creates a brand-new native session. A
   stale fd, native handle, or notification is never reused. If the profile or
   credentials are unavailable (for example Keystore locked before first
   unlock), the restore fails with a visible error and clears the desired
   state rather than looping.
3. **No boot receiver.** Android Always-on VPN handles boot and starts the
   service with `VpnService.SERVICE_INTERFACE`; the app must not add a second
   boot path. Before the first unlock the app cannot decrypt its Keystore
   material (it is not direct-boot aware), so a boot receiver would only
   produce a guaranteed failure. Reboot recovery therefore requires Always-on
   to be enabled; this is documented.
4. **Bounded exponential backoff with jitter.** `PauseRetryPolicy` keeps
   the PR #5 budget — 5 s base delay, 60 s cap, 6 attempts per connected
   session, then an explicit "traffic is no longer protected" error — and adds
   **bounded jitter**: each delay is a uniformly random value in
   `[base / 2, base]`, where `base` is the deterministic exponential delay
   (5, 10, 20, 40, 60 s, capped at 60 s). Jitter reduces lockstep reconnect
   storms after a provider-wide failure while keeping a lower bound so a device
   that just woke up does not retry instantly; the attempt budget and cap are
   unchanged so the unprotected window remains bounded.
5. **Force-stop, reboot, and other-VPN behaviour is documented, not guessed.**
   - Force-stop disables background starts until the user opens the app; the
     VPN does not come back and the app shows the idle/disconnected state.
   - Reboot: if Always-on is enabled and the device has been unlocked, the
     system starts the service; pre-unlock it cannot decrypt credentials.
   - Another VPN app taking over: Android stops this app's `VpnService`; the
     app cleans up and shows the disconnected/revoked state. It does not try to
     steal the VPN back.
6. **No duplicate sessions or stale notifications.** The desired state is the
   single source of truth; a connect request while a session is active is
   ignored/deduplicated, and teardown clears the desired state and notification
   exactly once. The service's existing `finishing` guard is kept.
7. **Tests and evidence split.** JVM tests pin the jitter/budget policy and the
   desired-state transitions that can be tested without Android; process
   death and reboot need device tests (`adb shell am kill`, reboot + Always-on)
   listed in `docs/testing.md`.

## Consequences

- The app can honestly say what it will do after a crash: reconnect only when
  Android restarts the service (Always-on) and only with the profile the user
  asked for; otherwise stay disconnected.
- Users who never enabled Always-on are not surprised by a background restart;
  the "desired connected" state is not a boot service.
- Full jitter changes retry timing; the maximum number of attempts and the cap
  remain the same, so the worst-case unprotected window is unchanged in
  duration class (bounded) and is still reported explicitly when exhausted.
- Restoring a session requires the encrypted profile + credentials; a
  credential prompt cannot be shown from a background restart, so profiles
  without stored credentials fail the restore visibly (documented).
- The lifecycle model does not claim a kill switch: while reconnecting or
  disconnected there is no tunnel, and only Android lockdown protects traffic.

## Alternatives considered

- **`START_STICKY` always.** Rejected: it would resurrect sessions the user
  intentionally disconnected and surprise non-Always-on users.
- **Boot receiver.** Rejected: duplicates Always-on, cannot run before first
  unlock, and adds an exported surface.
- **Persist and restore the native session/fd.** Impossible safely across
  process death; this is exactly the stale-pointer bug class the issue names.
- **Keep deterministic backoff.** Rejected: the issue explicitly asks for
  jitter; bounded jitter (uniform in `[base/2, base]`) reduces lockstep
  reconnects while keeping a lower bound on the wait.
- **Retry forever.** Rejected by ADR-007's bounded-budget decision; an
  unbounded loop hides protection loss.

## References

- Issue: [#14](https://github.com/smhasnanmonir/openvpn/issues/14)
- ADR-007 (post-connect timeout pause and bounded retry)
- `app/src/main/java/com/smhasnan/openvpn/vpn/PauseRetryPolicy.kt`
- `app/src/main/java/com/smhasnan/openvpn/vpn/MyVpnService.kt`
- `app/src/main/java/com/smhasnan/openvpn/settings/SettingsRepository.kt`
- `docs/testing.md`, `docs/security.md`
