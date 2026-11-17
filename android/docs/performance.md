# Performance and power

This document records the memory/battery audit performed for the
`feat/home-network-profiles-performance` change set and the resulting fixes. It
is a design/audit record, not a benchmark report: no Macrobenchmark or
Battery Historian numbers are claimed here. The verification that was actually
run is listed at the end.

## Method

The whole app was reviewed for work that is avoidable on a phone that keeps a
VPN service alive for hours:

1. **Composition cost** — which flows are collected, how often state changes,
   and whether a change only affects one visible surface.
2. **Timers and polling** — every loop, delay, callback and chronometer, and
   whether it keeps running while nobody can see the result.
3. **Cross-process work** — `NotificationManager.notify`, Quick Settings tile
   updates, network callbacks, and Binder/PendingIntent churn.
4. **Retained memory** — caches and buffers that hold decrypted profile
   material or diagnostics, plus startup work that is duplicated.
5. **Radio time** — network requests that can be skipped (offline, no visible
   UI, no changed egress path) or bounded with shorter timeouts.

The rule applied throughout: *do nothing unless something visible changes*.
That is also why the redesigned network card deliberately uses a static
skeleton instead of a shimmer: an animation would keep the frame clock awake
for the whole request.

## Fixes

| Area | Before | After |
|---|---|---|
| Logs observation | `AppRoot` collected the 500-line log `StateFlow` for the whole app, so every engine log line recomposed the entire UI tree on every tab | Collected by `LogsScreen` only, with lifecycle awareness, so log traffic touches one screen |
| Diagnostics publication | Every `appendLog` allocated a new 500-element list (`existing + entry`) on native callback threads | Bounded buffer plus one coalesced immutable snapshot per 250 ms; error/fatal lines still publish immediately |
| Session chronometer | One-second timer ran for as long as the tunnel was connected, including while backgrounded | Runs inside `repeatOnLifecycle(RESUMED)`; the notification's system chronometer covers the background case |
| Compose stability | `VpnSnapshot`, stats, details, profiles, settings and log entries were treated as unstable | Value models marked `@Immutable`, so unchanged state no longer invalidates readers |
| Stats polling | A 1.5 s loop ran forever, re-reading connection details and stats even while disconnected/backgrounded | State-driven: polls only while `CONNECTED`/`RECONNECTING`, 1.5 s while interactive, 10 s while the screen is off; details are read once on connect and on real network changes; native calls are guarded against a closing session |
| Notification updates | Notification rebuilt and `notify()` called on every engine event, stats change and network callback; PendingIntents re-created each time | Cheap content signature skips unchanged posts, posts are coalesced to at most one pending main-thread update, reusable PendingIntents are cached |
| Quick Settings tile | Tile rebuilt on every snapshot emission (every 1.5 s from stats) | Last-rendered `(state, label, subtitle)` cached; stats-only updates never cross into SystemUI |
| Network callbacks | `onCapabilitiesChanged` published a "network changed" event (and notification/tile update) on every signal-strength/bandwidth update | Only network identity, online state or transport description changes are published; underlying network and connection details refresh only then |
| Profile memory | Repository retained every profile, including the full decrypted `.ovpn` config for each one, for the process lifetime | Retains a summary index plus at most one decrypted profile body, loading bodies from encrypted storage on demand |
| Public IP check | Failure kept the previous address in state; retry could surface a stale IP; request ran even when offline with 8 s timeouts | State carries exactly one outcome (loading/error/address); retries show a loading skeleton, stale results are dropped when the egress path changes, offline short-circuits before any request, 6 s timeouts, no HTTP cache |
| Startup | Profiles were decrypted/loaded twice per cold start (`AppContainer` and `AppViewModel`) | One refresh per process (`AppContainer`); the ViewModel only observes state |

## Verification performed

- `:app:testDebugUnitTest` covers the new policies and buffers:
  `EmojiCatalogTest`, `StatsRefreshPolicyTest`, `VpnControllerLogBufferTest`,
  `ProfileStoreLoadTest`, plus the existing engine/state/store/format tests.
- `:app:compileDebugKotlin`, `:app:lintDebug`, `:app:assembleDebug` on the
  branch (and the same checks in `Android CI` for the PR).
- Manual checks on an emulator are described in the PR: retry-after-failure on
  the Network card, profile switching from Home, custom emoji picker, and
  background/screen-off connect-disconnect cycles.

## Known remaining hotspots (deliberately not changed here)

These were found during the audit but are larger/structural changes than this
PR should carry; they are recorded so the next pass has a starting point.

- **Profile enumeration peak**: `ProfileStore.loadAll()` decrypts every profile
  blob at once to build the summary index (large bundles with inline certs).
  A metadata-only index would cap the transient peak but needs a storage format
  change. The retained *steady-state* memory is already summaries-only.
- **Connect-path config copy**: `MyVpnService` copies the whole profile config
  through `ProfileImportPolicy.withNegotiableTlsCiphers()` on every connect
  (kept for upgrades of profiles imported before 1.1.2). A one-time persisted
  migration would remove the copy from the connect hot path.
- **Import peak**: `ConfigImporter` keeps several full copies of the merged
  profile while adapting/evaluating it. A single-buffer path would reduce GC
  pressure during import (a rare, user-initiated operation).
- **Log retention budget**: diagnostics are bounded by line count (500) and
  per-line length (2048 chars), which is still ~1 MB worst case. A total
  character budget would bound memory harder.
- **Engine event snapshots**: `VpnController.engineEvent` copies the whole
  snapshot (including details/stats) per native event. Batching non-terminal
  events would cut allocations during connect handshakes.
- **Auto IP check frequency**: one lookup per `CONNECTED` transition when the
  setting is on. A short "last checked" grace period would save a few radio
  wake-ups on flapping networks.
- **Settings writes**: `SettingsRepository.update` rewrites all preference keys
  on any setting change; writing only changed keys reduces I/O churn.

## Guidelines for future changes

- Collect a `StateFlow` only in the screen that renders it.
- If a visible surface does not change, do not call the system (notification,
  tile, radio).
- Prefer state-driven loops (`collect`, `collectLatest`) over `while (true)`
  polling, and pick the cadence from screen power state.
- Keep native/engine callbacks off the main thread and guard them against a
  closing session.
- Do not retain decrypted profile material longer than the operation needs it.
