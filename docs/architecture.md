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
| `ConfigImporter` | SAF import, companion files, merge/eval, trust/cipher validation, duplicate detection, CA-change warning |
| `ProfileTrustPolicy` | Strict server-trust acceptance/rejection (CA / peer-fingerprint / `tls-remote`), CA-change detection |
| `ProfileSecurityPolicy` | Connect-time cipher/compression policy transforms (AEAD-only pull filters, policy text) |
| `DnsLeakPolicy` | Pushed-DNS usability, IPv4/IPv6 validation, fallback DNS, fail-closed decision |
| `MtuPolicy` | Fixed-MTU connect-time config transform |
| `SplitTunnelPolicy` | Per-app mode validation, package filtering, leak warning |
| `CaptivePortalPolicy` | Portal capability interpretation and paused-state honesty |
| `AlwaysOnVpnPolicy` / `AlwaysOnVpnStatus` | Best-effort Always-on/lockdown readiness detection (UNKNOWN when unreadable) |
| `NetworkMonitor` | Default network changes, underlying-network updates, portal/validated capabilities |
| `PublicIpRepository` | HTTPS public-IP lookup through the active network |

## Policy layer

The security/reliability decisions that can be evaluated without Android live in
pure Kotlin objects (`vpn/DnsLeakPolicy`, `vpn/MtuPolicy`,
`vpn/SplitTunnelPolicy`, `vpn/CaptivePortalPolicy`,
`config/ProfileTrustPolicy`, `config/ProfileSecurityPolicy`,
`config/AlwaysOnVpnPolicy`, `vpn/PauseRetryPolicy`). They are unit-tested on the
JVM and wired into `MyVpnService`/`ConfigImporter`. The service applies the
policy at TUN-establishment time (fresh evaluation on every connect/resume) so a
setting change is honoured without stale cached decisions, and it fails closed
when a policy refuses the configuration (no TUN is created). Native code stays a
thin bridge; the only build-time core change for policy is the generated
`proto.hpp` compression guard (patch #3).

## Connection lifecycle

```
user taps Connect
  → VpnService.prepare() consent if needed
  → CredentialHandoff (in-process, never an Intent extra)
  → SettingsRepository records the desired connected profile
  → MyVpnService.startForegroundService(ACTION_CONNECT)
  → foreground notification "Preparing"
  → load encrypted profile + optional stored credentials
  → connect-time policy transforms (trust re-check, MTU override, AEAD-only pull filters, strict compression flag)
  → NativeSession created
  → native session starts a worker thread
  → OpenVPNClient.eval_config / provide_creds / connect
  → TunBuilderBase collects addresses, routes, DNS, MTU
  → establishTun():
      → DnsLeakPolicy: usable pushed DNS / validated fallback / refuse (fail closed)
      → SplitTunnelPolicy: validate + addAllowed/DisallowedApplication
      → MtuPolicy/engine: fixed MTU applied via pull-filter + tun-mtu
      → VpnService.Builder.establish() → ParcelFileDescriptor.detachFd()
  → native Core performs all TUN packet I/O
  → socket_protect() callback → VpnService.protect(fd)
  → core events: CONNECTING / CONNECTED / RECONNECTING / AUTH_FAILED / ...
  → VpnController updates UI + notification
```

On disconnect the desired connected profile is cleared. On process death the
service can be restarted by the system (Always-on, `START_STICKY`) and rebuilds a
fresh native session from the persisted desired profile; **no native session,
fd, or notification is restored across process death** (ADR-013).

Disconnect cancels stats polling, calls native `stop()`, joins the worker,
closes the native session (which destroys the JNI global reference), removes
the foreground notification, and stops the service. `onRevoke()` and
`onDestroy()` follow the same ownership rules.

Captive-portal pause is a deliberate, user-triggered teardown: the tunnel is
stopped, state carries `pausedForPortal = true`, the UI says traffic is not
protected, and a validated network triggers a fresh connect of the previous
profile (ADR-012).

## Threading model

- UI: main thread; no blocking native/file calls.
- Profile and credential I/O: `Dispatchers.IO`.
- Native Core `connect()`: owned worker thread created by `JniSession`.
- JNI callbacks: attach to the JVM on the native worker and use a global ref to
  `NativeCallbacks`.
- Stats polling: service coroutine, only while `CONNECTED`/`RECONNECTING`,
  every 1.5 s while the screen is interactive and every 10 s while it is off
  (`StatsRefreshPolicy`). Connection details are refreshed on connect and on
  real network changes, not on every poll.
- `VpnController`: StateFlow updates are thread-safe and observed on main. Log
  lines are buffered and published as one coalesced snapshot per 250 ms; errors
  publish immediately.

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
state. Unknown non-fatal events are logged but do not change state. A
`pausedForPortal` flag on `VpnSnapshot` marks the deliberate captive-portal
pause: while it is true the tunnel is down, the snapshot is not `CONNECTED`, and
the UI must not render the session as protected (ADR-012).

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
- The builder is configured from the policy layer before `establish()`:
  `DnsLeakPolicy` decides DNS (refusal means no TUN at all),
  `SplitTunnelPolicy` adds the per-app allow/deny lists, and a fixed MTU is
  applied through the connect-time configuration (`pull-filter ignore tun-mtu`
  + `tun-mtu N`). No policy value is hardcoded in the native adapter.
- The service is `START_STICKY` only for the Always-on/system restart path; the
  desired-state model recreates a fresh TUN/native session after process death.
  No fd or native pointer is restored.

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

The repository keeps only the profile summary index resident plus at most one
decrypted profile body (the most recently used); other bodies are decrypted
from their encrypted file on demand (`ProfileStore.load(id)`).

`SettingsRepository` (unencrypted `SharedPreferences`) holds non-secret
settings: selected profile, theme/lock, DNS requirement + fallback DNS string,
MTU mode, split-tunnel mode + selected package names, compression/cipher
policy, and `desiredConnectedProfileId` — the profile the user last asked to
connect, used to restore intent (never a native handle) after a system restart.
Package names in the split-tunnel set are used only locally for the picker and
`VpnService` per-app lists; see `docs/security.md`.

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
- No app-controlled guaranteed kill switch (Android Always-on/lockdown is the
  supported mechanism; the app only reports readiness best-effort).
- No per-profile split-tunnel policy (the per-app policy is a global setting;
  a per-profile model would need its own ADR).
- No WINS, TAP mode, or external PKI provider support. Certificate-less
  username/password profiles are supported by adding Core's
  `setenv CLIENT_CERT 0`; see `docs/profile-import.md` and ADR-004.
- No client-side CRL/OCSP: revocation is enforced server/PKI-side (ADR-009).
- No backend/accounts/PKI/gateways (ADR-014) and no iOS target (ADR-015);
  both are documented plans, not code in this repository.
- No analytics/backend/DI framework/Room/Retrofit.
