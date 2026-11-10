# Architecture

## Goals

The design keeps three concerns separate:

1. Android lifecycle and TUN ownership (`MyVpnService`).
2. VPN protocol, TLS, certificate validation, and packet processing (OpenVPN 3
   Core through JNI).
3. User-facing profile management and presentation (Kotlin repositories and
   Compose UI).

No layer fabricates connection state. The UI observes
`VpnController.snapshot`, which is written by service/native callbacks only.

## Runtime components

| Component | Responsibility |
|---|---|
| `MainActivity` | Compose host, edge-to-edge, biometric app lock, theme |
| `AppViewModel` | Profile actions, credential prompt state, IP check, self-test |
| `VpnController` | Process-wide StateFlow of engine state, details, stats, logs |
| `MyVpnService` | Foreground service, `VpnService.Builder`, TUN fd, native session, socket protect |
| `VpnTileService` | Quick Settings tile control |
| `VpnNotification` | Foreground notification, chronometer, actions, error notification |
| `NativeSession` | Owns one native session handle; safe connect/disconnect/close |
| `OpenVpnNative` | JNI declarations only |
| `NativeEngine` | Stateless eval/merge/self-test/parse helpers |
| `ProfileRepository` | Profile cache, selection, rename/delete/emoji, credentials |
| `ProfileStore` / `CredentialStore` | Encrypted file persistence |
| `KeystoreSecretBox` | AES-256-GCM with Android Keystore key |
| `ConfigImporter` | SAF import, companion files, merge/eval, duplicate detection |
| `NetworkMonitor` | Default network changes and underlying-network updates |
| `PublicIpRepository` | HTTPS public-IP lookup through the active network |

## Connection lifecycle

```
user taps Connect
  → VpnService.prepare() consent if needed
  → CredentialHandoff (in-process, never an Intent extra)
  → MyVpnService.startForegroundService(ACTION_CONNECT)
  → foreground notification "Preparing"
  → load encrypted profile + optional stored credentials
  → NativeSession created
  → native session starts a worker thread
  → OpenVPNClient.eval_config / provide_creds / connect
  → TunBuilderBase collects addresses, routes, DNS, MTU
  → tun_builder_establish()
      → JNI callback onTunEstablish(json)
      → Kotlin VpnService.Builder.establish()
      → ParcelFileDescriptor.detachFd()
  → native Core performs all TUN packet I/O
  → socket_protect() callback → VpnService.protect(fd)
  → core events: CONNECTING / CONNECTED / RECONNECTING / AUTH_FAILED / ...
  → VpnController updates UI + notification
```

Disconnect cancels stats polling, calls native `stop()`, joins the worker,
closes the native session (which destroys the JNI global reference), removes
the foreground notification, and stops the service. `onRevoke()` and
`onDestroy()` follow the same ownership rules.

## Threading model

- UI: main thread; no blocking native/file calls.
- Profile and credential I/O: `Dispatchers.IO`.
- Native Core `connect()`: owned worker thread created by `JniSession`.
- JNI callbacks: attach to the JVM on the native worker and use a global ref to
  `NativeCallbacks`.
- Stats polling: service `Dispatchers.IO` coroutine, 1.5 s interval.
- `VpnController`: StateFlow updates are thread-safe and observed on main.

## State machine

`VpnState`:

```
DISCONNECTED ──prepare──> PREPARING ──connect──> CONNECTING ──CONNECTED
                                                  │  ▲
                                                  │  └── RECONNECTING
                                                  ├── AUTH_FAILED
                                                  ├── NEEDS_CHALLENGE ──response──> CONNECTING
                                                  └── ERROR
CONNECTED / CONNECTING / RECONNECTING ──disconnect──> DISCONNECTING ──> DISCONNECTED
```

Transitions are produced by `EngineEventMapper` from actual OpenVPN Core event
names. `SESSION_FINISHED` (native `connect()` return status) settles the final
state. Unknown non-fatal events are logged but do not change state.

The only exception is a native `DYNAMIC_CHALLENGE`: the core's blocking
`connect()` call ends, but `SESSION_FINISHED` is suppressed so the service
keeps the session alive until the user submits a response. A new connect is
then started with the challenge cookie.

## TUN and descriptor ownership

- Kotlin creates the TUN via `VpnService.Builder` and calls `detachFd()`.
- The integer fd is returned to native `TunBuilderBase::tun_builder_establish`.
- OpenVPN Core owns the fd for the session and closes it during teardown.
- Kotlin does not close the detached ParcelFileDescriptor and never reuses it.
- `tun_builder_persist()` returns false, so every reconnect creates a new TUN
  builder session instead of reusing a stale descriptor.
- The service is `START_NOT_STICKY`; no fd or native pointer is restored after
  process recreation.

## Native session ownership

- `JniSession` is allocated by `createSession` and destroyed only by
  `destroySession`.
- `disconnect()` calls `OpenVPNClient::stop()` and joins the worker before
  clearing the client pointer.
- `~JniSession` disconnects, deletes the JNI global reference, and deletes the
  session.
- Kotlin `NativeSession.close()` is idempotent and sets the handle to 0 before
  calling native destroy.

## Persistence

```
noBackupFilesDir/
  profiles/<uuid>.profile        AES-256-GCM encrypted JSON
  credentials/<uuid>.credentials AES-256-GCM encrypted JSON
```

Whole-profile encryption includes the raw unified profile, display name,
emoji, and non-secret metadata. Credential files contain optional username,
password, and private-key password only. Static/dynamic challenge responses are
never persisted. File names are random UUIDs and contain no server or profile
names. Writes go to a temp file, fsync, then rename.

Backups are disabled in the manifest and a `data_extraction_rules.xml` excludes
all domains from cloud backup and device transfer.

## SOLID / DRY choices

- Single responsibility: storage, import, state, transport, and UI are separate
  classes with narrow APIs.
- Dependency inversion: `SecretBox` abstracts Keystore for tests; native code
  depends on `EngineCallbacks`, not JNI.
- One source of truth for state (`VpnController`) and one writer (service/native
  callbacks).
- Shared UI primitives in `ui/components` rather than duplicating cards,
  banners, validation, and empty states.
- Pure policies (`ImportFilePolicy`, `ProfileImportPolicy`,
  `ProfileNameValidator`, `LogSanitizer`, `EngineEventMapper`) are unit-tested
  without Android.

## Deliberate non-goals

- No custom VPN protocol, TLS, certificate validation, or cryptography.
- No app-controlled guaranteed kill switch.
- No per-app split tunneling UI, WINS, TAP mode, or external PKI provider
  support. Certificate-less username/password profiles are supported by adding
  Core's `setenv CLIENT_CERT 0`; see `docs/profile-import.md` and ADR-004.
- No analytics/backend/DI framework/Room/Retrofit.
