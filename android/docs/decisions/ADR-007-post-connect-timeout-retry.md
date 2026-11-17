# ADR-007: Pause and retry post-connect connection timeouts

## Status

Accepted.

## Context

A connected session ended with a fatal banner after the phone was locked and
unlocked:

```text
03:04:11 ERROR Connection timed out before the TLS handshake completed. Check the profile's tls-cipher / tls-version-min options and that the server is reachable.
```

This was not a first connection. The device had been asleep, the network had
likely transitioned, and OpenVPN Core was retrying the connection when its
`connTimeout` expired. Core's behaviour at that point is controlled by
`ClientAPI::OpenVPNClient::pause_on_connection_timeout()`:

- returning `false` makes Core emit `CONNECTION_TIMEOUT` and stop the session;
- returning `true` makes Core enter its `PAUSE` state, keep the blocking
  `connect()` call alive, and emit a `PAUSE` event.

The app previously returned `false` unconditionally. That is correct for a
first connection (the user should see why the profile cannot connect), but it
is wrong for a reconnect after an established tunnel. A transient sleep or
network transition should not turn into a permanent error banner.

## Decision

1. **Track whether this client has ever reached `CONNECTED`.**
   `OvpnClient` sets `reached_connected_` on the `CONNECTED` event and returns it
   from `pause_on_connection_timeout()`. An initial timeout still returns
   `false` and surfaces as `CONNECTION_TIMEOUT`; a post-connect timeout returns
   `true` and enters Core's pause state.
2. **Map `PAUSE` and `RESUME` to `RECONNECTING`.**
   `EngineEventMapper` maps both events to `VpnState.RECONNECTING` and clears any
   previous error. The notification and tile already render that state as
   "Reconnecting" and offer Disconnect.
3. **Resume from the service with a bounded, backed-off retry.** `MyVpnService`
   handles `PAUSE` by starting one retry loop. It waits 5 s before the first
   resume, then backs off exponentially (5, 10, 20, 40, 60 s, capped at 60 s)
   while the state is still `RECONNECTING` and the network is online, and calls
   the new `NativeSession.resume()` JNI entry point (`OpenVPNClient::resume()`).
   `resume()` itself is a `void` Core API and the JNI wrapper cannot report
   whether Core actually left the paused state, so the loop does not trust the
   JNI return value; it keeps retrying until an observed `RESUME`, `CONNECTED`
   or failure event cancels it (see `PauseRetryPolicy`).
   The number of resume attempts is counted per connected session (reset on
   `CONNECTED`). After `PauseRetryPolicy.MAX_ATTEMPTS` (6) failed attempts the
   session is failed visibly with an explicit "traffic is no longer protected"
   error instead of retrying forever. The loop is cancelled on `RESUME`,
   `CONNECTED`, `SESSION_FINISHED`, disconnect and service destruction.
4. **Keep the TUN policy unchanged.** The app still uses `tunPersist = false`
   and `tun_builder_persist()` returns `false`. Pausing tears down the old TUN;
   resuming creates a fresh one through the existing `tun_builder_establish`
   callback. Enabling persistence would need a separate design review because
   the app deliberately avoids reusing stale descriptors across sessions.

## Consequences

- Locking the phone or losing the network for longer than `connTimeout` no
  longer leaves the app on a fatal timeout banner after an established
  connection. The UI stays in "Reconnecting" and retries when the network is
  usable.
- A first connection that times out still produces the existing actionable
  error; the change is deliberately scoped to sessions that have already
  reached `CONNECTED`.
- **The VPN is down while paused, so traffic is not protected during the retry
  window.** Core's `pause()` closes the TUN (the app keeps `tunPersist = false`),
  and Android restores the underlying network when the TUN descriptor is closed.
  On devices without Always-on lockdown, traffic can therefore use the
  underlying network while the UI says "Reconnecting". Retries use backoff and
  are capped, after which the app reports an explicit error rather than
  implying the tunnel is coming back. Always-on lockdown keeps its own
  system-level block in this state.
- Retry cadence is driven by Core, not by the service: each resume starts a new
  connection attempt that runs until Core's `connTimeout` (60 s) expires before
  pausing again. The delays in `PauseRetryPolicy` are the waits before asking
  Core to resume, so a dead server is retried roughly once per connection-attempt
  timeout, with growing delays, for at most six attempts.
- A genuinely unreachable server after a successful connection (including one
  selected through the notification's manual Reconnect action) now ends with an
  explicit "could not reconnect / not protected" error after the bounded retry
  budget, instead of an endless Reconnecting state. The foreground notification
  and tile expose Disconnect throughout.
- Between Core tearing down the paused TUN and the resumed client establishing a
  new one there is a real unprotected interval; it lasts until a reconnect
  succeeds, not merely 5 s. This is the same class of gap the app already had
  when a session stopped, but it is longer here and is called out explicitly.
- The new JNI `resume` method is additive; `reconnect` remains unchanged for the
  notification and tile actions.

## References

- Issue: [#4](https://github.com/smhasnanmonir/openvpn/issues/4)
- `third_party/openvpn3/openvpn/transport/reconnect_notify.hpp`
- `third_party/openvpn3/openvpn/client/cliconnect.hpp`
  (`conn_timer_callback`, `pause`, `resume`)
- `app/src/main/cpp/engine_adapter/ovpn_client.cpp`
  (`pause_on_connection_timeout`)
- `app/src/main/java/com/smhasnan/openvpn/vpn/MyVpnService.kt`
  (`onPaused`, resume loop)
- `app/src/main/java/com/smhasnan/openvpn/vpn/EngineEventMapper.kt`
