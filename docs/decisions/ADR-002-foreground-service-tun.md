# ADR-002: Foreground service and TUN ownership

## Status

Accepted.

## Context

Android 14+ requires a foreground service type. VPN apps are not eligible for
the `systemExempted` type unless they meet system-app rules. The app needs a
visible foreground notification and a TUN interface.

## Decision

- Use `VpnService` with a foreground notification.
- Declare `android:foregroundServiceType="specialUse"` with
  `FOREGROUND_SERVICE_SPECIAL_USE` and
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE="vpn"`.
- Use `START_NOT_STICKY`; never restore stale fd/native pointers.
  *(Partially superseded by ADR-013: the service returns `START_STICKY` for the
  Android Always-on/system-restart path and restores the persisted **desired
  connected profile** with a fresh native session. Stale fds/pointers are still
  never restored.)*
- `VpnService.Builder` creates TUN; `ParcelFileDescriptor.detachFd()` transfers
  ownership to OpenVPN Core, which performs all packet I/O and closes the fd.
- `tun_builder_persist()` returns false; a new TUN is created per session.

## Consequences

- Play distribution requires declaring the `specialUse`/`vpn` subtype in the
  Play Console.
- The notification is required and may be hidden from the drawer if the user
  denies `POST_NOTIFICATIONS`, but Android still tracks the FGS.
- No seamless TUN handover across network changes; core reconnects and a new
  TUN is established.
