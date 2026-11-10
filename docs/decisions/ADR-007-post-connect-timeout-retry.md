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
3. **Resume from the service.** `MyVpnService` handles `PAUSE` by starting one
   retry loop: every 5 seconds, while the state is still `RECONNECTING` and the
   network is online, call the new `NativeSession.resume()` JNI entry point
   (`OpenVPNClient::resume()`). A successful resume emits `RESUME` and starts a
   new client; a new timeout pauses again and starts a fresh loop. The loop is
   cancelled on `RESUME`, `CONNECTED`, `SESSION_FINISHED`, disconnect and service
   destruction.
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
- A genuinely unreachable server after a successful connection is retried every
  5 seconds until the user disconnects or the network changes. The foreground
  notification's Disconnect action remains available, and Android's Doze
  batching limits the wake-up cost while the screen is off.
- A first connection that times out still produces the existing actionable
  error; the change is deliberately scoped to sessions that have already
  reached `CONNECTED`.
- There is a short interval between Core tearing down the paused TUN and the
  resumed client establishing a new one. This is the same kind of gap the app
  already had when a session stopped, and it is documented as a residual risk;
  it is not made longer than the 5-second retry cadence.
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
