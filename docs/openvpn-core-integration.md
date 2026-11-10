# OpenVPN 3 Core integration

This document records exactly what was inspected and used. If upstream
documentation disagrees with the checked-out revision, the checked-out source at
the pinned commit is authoritative.

## Pinned revision

| Item | Value |
|---|---|
| Repository | <https://github.com/OpenVPN/openvpn3> |
| Tag | `release/3.9` |
| Commit | `10c6a56cb17fd015a2d24953c3c42145bbcdea58` |
| Commit date | 2024-06-05 |
| Core version macro | `OPENVPN_VERSION "3.9"` |
| Submodule path | `third_party/openvpn3` |
| License at this revision | AGPL-3.0-only with OpenSSL linking exception |

The revision is immutable and present as a git submodule. Update it only by
changing the submodule commit, re-running the native build, and reviewing the
license delta.

## Actual source files used

| Path (inside `third_party/openvpn3`) | Purpose |
|---|---|
| `client/ovpncli.hpp` | Public client API: `OpenVPNClient`, `OpenVPNClientHelper`, `Config`, `ProvideCreds`, events, stats |
| `client/ovpncli.cpp` | Implementation of the blocking client loop and helper functions |
| `openvpn/tun/builder/base.hpp` | `TunBuilderBase` callback interface |
| `openvpn/tun/builder/client.hpp` | Core-side generic TUN client that calls `tun_builder_establish()` and performs TUN I/O |
| `openvpn/tun/client/tunprop.hpp` | Translates negotiated options into builder calls |
| `openvpn/client/dns.hpp` | `DnsOptions`, `DnsServer`, `DnsDomain` at 3.9 |
| `openvpn/client/clievent.hpp` | Event type/name definitions |
| `openvpn/log/sessionstats.hpp` | Stats names (`BYTES_IN`, `TUN_BYTES_IN`, …) |
| `deps/asio/patches/*.patch` | Required OpenVPN patches to asio 1.24.0 |
| `LICENSE.rst`, `COPYRIGHT.AGPLV3` | License at this revision |

## APIs actually used

### Profile parsing and validation

`openvpn::ClientAPI::OpenVPNClientHelper`:

- `merge_config(path, follow_references=true)` → `MergeConfig`
  - returns `status == "MERGE_SUCCESS"` and unified `profileContent`
  - resolves externally referenced files relative to the imported temp path
- `merge_config_string(content)` was inspected but not used
- `eval_config(Config)` → `EvalConfig`
  - `error`, `message`, `autologin`, `userlockedUsername`, `staticChallenge`,
    `staticChallengeEcho`, `privateKeyPasswordRequired`, `allowPasswordSave`,
    `remoteHost`, `remotePort`, `remoteProto`, `externalPki`
  - `vpnCa` is deliberately not copied into JVM metadata
- `parse_dynamic_challenge(info, DynamicChallenge&)`
- `platform()`, `copyright()`, `crypto_self_test()`

`OpenVPNClient` (session):

- `eval_config(Config)`
- `provide_creds(ProvideCreds)`
- `connect()` (blocking; runs on the native worker thread)
- `stop()`, `reconnect(seconds)`
- `connection_info()`
- `stats_bundle()`, static `stats_n()`, `stats_name(index)`
- virtual `event(Event)`, `log(LogInfo)`
- virtual `socket_protect(socket, remote, ipv6)`
- virtual `pause_on_connection_timeout()`
- virtual `external_pki_cert_request` / `external_pki_sign_request` (declared
  unsupported and filled with an error)
- constructor/destructor

### TUN builder

`AndroidTunBuilder : openvpn::TunBuilderBase` implements the callbacks called
at 3.9:

- `tun_builder_new`, `tun_builder_set_layer`
- `tun_builder_set_remote_address`
- `tun_builder_add_address`
- `tun_builder_set_route_metric_default`
- `tun_builder_reroute_gw`
- `tun_builder_add_route`, `tun_builder_exclude_route`
- `tun_builder_add_dns_options`, `tun_builder_add_dns_server`,
  `tun_builder_add_search_domain`
- `tun_builder_set_mtu`, `tun_builder_set_session_name`
- `tun_builder_add_proxy_bypass`, `tun_builder_set_proxy_auto_config_url`,
  `tun_builder_set_proxy_http`, `tun_builder_set_proxy_https`
- `tun_builder_add_wins_server`
- `tun_builder_set_allow_family`, `tun_builder_set_allow_local_dns`,
  `tun_builder_set_adapter_domain_suffix`
- `tun_builder_establish` → Kotlin JNI callback returns detached TUN fd
- `tun_builder_persist` → false (fresh TUN per session)
- `tun_builder_establish_lite`, `tun_builder_teardown`

`tun_builder_reroute_gw` adds `0.0.0.0/0` and/or `::/0` to the collected routes
when the core requests redirect-gateway.

### Android TUN and packet I/O

At 3.9, `openvpn/tun/builder/client.hpp` constructs
`TunBuilderClient::Tun<TunIO<posix::stream_descriptor>>` with the fd returned by
`tun_builder_establish()` and starts asynchronous reads/writes. **OpenVPN Core
performs both directions of packet I/O**; Android does not proxy packets in
Kotlin. Kotlin only creates the TUN and hands over fd ownership via
`ParcelFileDescriptor.detachFd()`.

The core closes the fd during teardown. `tun_builder_persist()` returns false so
persisted descriptor reuse is disabled by this app.

### Socket protection

`OvpnClient::socket_protect()` forwards `(fd, remote, ipv6)` through JNI to
`MyVpnService.protect(fd)`. This prevents the OpenVPN transport socket from
being routed into its own tunnel. The callback runs on the core's connection
thread.

### Credentials and challenge

`ProvideCreds.username`, `.password`, `.response`,
`.dynamicChallengeCookie` are used. `Config.privateKeyPassword` is passed for
profiles that need a key passphrase.

- Static challenge: `EvalConfig.staticChallenge` is shown before connecting;
  the response is passed in `ProvideCreds.response` and is never persisted.
- Dynamic challenge: the core event `DYNAMIC_CHALLENGE` is parsed with
  `parse_dynamic_challenge`; the cookie is retained in native state. The
  blocking connect call is allowed to return, but `SESSION_FINISHED` is
  suppressed so the service remains alive. Submitting a response starts a new
  native connect with the cookie.
- AUTH_FAILED and credential errors are reported as engine events.

### Events

The app maps actual core event names including `CONNECTING`, `CONNECTED`,
`RECONNECTING`, `DISCONNECTED`, `AUTH_FAILED`, `DYNAMIC_CHALLENGE`,
`TUN_SETUP_FAILED`, `TUN_ERROR`, `CONNECTION_TIMEOUT`, and logs. The native
bridge also emits `PROFILE_REJECTED`, `CREDENTIALS_REJECTED`, and
`SESSION_FINISHED` based on `OpenVPNClient` return statuses.

## Native dependency build

The app does not use OpenVPN Core's top-level CMake project. It builds a static
`ovpn3` target from `client/ovpncli.cpp` plus the pinned dependencies, because
the core is intended to be compiled into the consuming target (per-tag
`cmake/findcoredeps.cmake` comments about per-target defines).

Pinned dependencies:

| Dependency | Version | Source archive SHA-256 |
|---|---|---|
| asio | 1.24.0 + 7 OpenVPN patches | `cbcaaba0f66722787b1a7c33afe1befb3a012b5af3ad7da7ff0f6b8c9b7a8a5b` |
| fmt | 10.2.1 | `1250e4cc58bf06ee631567523f48848dc4596133e163f02615c97f78bab6c811` |
| jsoncpp | 1.9.6 | `f93b6dd7ce796b13d02c108bc9f79812245a82e577581c4c9aabe57075c90ea2` |
| lz4 | 1.9.4 | `0b0e3aa07c8c063ddf40b082bdf7e37a1562bda40a0ff5272957f3e987e0e54b` |
| mbedTLS | 3.6.2 | `8b54fb9bcf4d5a7078028e0520acddefb7900b3e66fec7f7175bb5b7d85ccdca` |

Commands:

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
./scripts/build-native-deps.sh arm64-v8a x86_64
./gradlew :app:assembleDebug
```

CMake defines used for the core target:

```
ASIO_STANDALONE USE_ASIO HAVE_LZ4 USE_MBEDTLS FMT_HEADER_ONLY
```

C++ standard: C++20 (required by OpenVPN Core 3.9 CMake).

Android toolchain:

- NDK `27.2.12479018`
- CMake `3.22.1`
- STL `c++_static`
- `minSdk 26`

ABIs built from source: `arm64-v8a`, `x86_64`.

## License and notices

At the pinned revision the core is **AGPL-3.0-only** with an OpenSSL linking
exception. The app consequently uses AGPL-3.0-only. Full text: root `LICENSE`.
Notices: `THIRD_PARTY_NOTICES.md`. The corresponding source is this repository
plus the submodule.

## Known limitations and unsupported features

- **External PKI** profiles are rejected; the callbacks return an error rather
  than pretending to support an alias/key provider.
- **TAP / layer 2** is not supported by Android `VpnService`; the builder only
  accepts layer 3.
- **HTTPS proxy and PAC** are unsupported. HTTP proxy is applied only on Android
  10+; Android's `ProxyInfo` has no per-host bypass list.
- **WINS** is not supported on Android; the callback returns false.
- **`--dns` with DoH/DoT or a non-zero port** is rejected because
  `VpnService.Builder` accepts only plain numeric DNS addresses without ports.
- **Local LAN access / per-app split tunneling** is not exposed by the UI.
- **Android family blocking:** Android blocks an address family by default when
  the VPN has no address/route/DNS of that family. `allowFamily()` only
  *unblocks*; it cannot block a family that has a configured address. A
  profile's `block-ipv6`/`block-ipv4` directive is therefore not enforceable
  when that family has an address; the app logs a warning instead of claiming
  success.
- **Compression:** the app does not force legacy compression modes. Profiles
  that depend on removed/legacy compression may fail with a core error rather
  than weakening policy.
- **DCO** is not enabled for Android; packet processing uses the core's TUN
  path.
- **`tunPersist`** is false; TUN is recreated per connection.
- Android 14+ foreground service uses `specialUse`/`vpn` subtype. This requires
  a Play Console declaration for distribution; it does not use
  `systemExempted`.

## Documentation vs checked-out source discrepancies

1. **License:** current upstream `README.md`/`LICENSE.md` describe MPL-2.0 or
   AGPL-3.0. At 3.9 the repository contains `LICENSE.rst` and
   `COPYRIGHT.AGPLV3` only (AGPL-3.0). This project follows the checked-out 3.9
   files.
2. **DNS callback:** current upstream `TunBuilderBase` uses
   `tun_builder_set_dns_options(const DnsOptions&)`; 3.9 declares
   `tun_builder_add_dns_options(const DnsOptions&)` plus
   `tun_builder_add_dns_server`/`add_search_domain`. The adapter implements the
   3.9 signatures.
3. **`data_epoch.cpp`:** present in newer revisions and referenced by
   `findcoredeps.cmake`; it does not exist at 3.9, so the app compiles only
   `client/ovpncli.cpp`.
4. **Android `allowFamily`:** the current Android SDK signature is
   `allowFamily(int family)` and it *allows* a family. It is not a two-argument
   allow/deny setter. The adapter uses Android's default family blocking and only
   calls `allowFamily` when the user explicitly opts into untunneled fallback.

## Verification performed

- `scripts/build-native-deps.sh` completed for `arm64-v8a` and `x86_64`.
- Gradle CMake build produced `libnativevpn.so` for both ABIs.
- Debug, release (R8), lint, unit tests, and instrumentation-test APK builds
  completed.
- Crypto self-test and live engine behavior are covered by instrumentation
  tests that require a device/emulator; see `docs/testing.md`. They have not
  been executed in this environment.
