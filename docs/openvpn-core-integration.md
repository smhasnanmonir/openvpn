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

#### Certificate-less username/password profiles

Core 3.9 reports `EvalConfig.externalPki = true` whenever the parsed profile has
no client `cert` or no client `key` option, even for a legitimate
username/password-only profile:

```cpp
// openvpn/client/cliopthelper.hpp at 10c6a56
externalPki_ = (clientCertEnabled_ && is_external_pki(options));
...
static bool is_external_pki(const OptionList &options)
{
    const Option *epki = options.get_ptr("EXTERNAL_PKI");
    if (epki)
        return string::is_true(epki->get_optional(1, 16));
    else
    {
        const Option *cert = options.get_ptr("cert");
        const Option *key = options.get_ptr("key");
        return !cert || !key;
    }
}
```

The same header parses the OpenVPN 2 `setenv CLIENT_CERT 0` setting into
`clientCertEnabled_ = false`, which makes `externalPki_` false as well; at connect
time `ClientOptions` calls
`cc->set_local_cert_enabled(pcc.clientCertEnabled() && !config.clientconf.disableClientCert)`,
so the SSL layer loads without a client certificate and no external-PKI callback
is requested (`openvpn/client/cliopt.hpp`). That is the supported Core path for
username/password-only profiles.

Because `EvalConfig` exposes only the combined boolean, the app inspects the
merged profile text in `ProfileImportPolicy`:

- an explicit `EXTERNAL_PKI` (or `setenv opt EXTERNAL_PKI`) marker means the
  profile really needs a certificate/key provider → rejected;
- a certificate-less profile that declares username/password authentication
  (`auth-user-pass`, `<auth-user-pass>`, `setenv opt auth-user-pass`) gets Core's
  own `setenv CLIENT_CERT 0` directive appended to the stored merged profile, is
  re-validated with `eval_config`, and is only stored if Core then reports
  `externalPki == false`;
- a certificate-less profile with no sign-in material is rejected (fail closed).
  See `docs/profile-import.md` and ADR-004.

The directive is parsed by Core from the stored profile when the service
connects, so no JNI, C++ adapter, or `ClientAPI::Config` change is required. The
app never disables server certificate verification: `remote-cert-tls server`,
`verify-x509-name`, and the CA stay in force.

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

### Compatibility settings

Compatibility settings keep older provider profiles connectable with the pinned
3.9 core:

- `Config.compressionMode = "asym"` (set in `native_bridge.cpp`). Core's default
  is `"no"`, which raises `COMPRESS_ERROR` when a server pushes LZO asymmetric
  compression right after `CONNECTED`; `"asym"` accepts exactly the
  server-to-client direction and still refuses symmetric compression (see
  `openvpn/ssl/proto_context_options.hpp` and ADR-005).
- Legacy `tls-cipher` pins are dropped at import time when every suite in the
  directive is a non-ECDHE (TLS 1.2 legacy) suite; those servers now negotiate
  TLS 1.3 and the pinned list otherwise stalls the handshake until the timeout.
  The same normalization is applied at connect time so profiles stored by
  earlier app versions work after an update. This is profile normalization in
  the app, not a core API; Core still parses and validates the modified profile
  (see `docs/profile-import.md`).
- Dynamic tls-crypt is only advertised when the TLS backend can export keying
  material. Core 3.9 advertises `IV_PROTO_DYN_TLS_CRYPT` unconditionally, but
  the pinned mbedTLS backend returns `false` from
  `export_keying_material()`. A server would then enable dynamic tls-crypt and
  the first rekey would abort with `KEY_EXPANSION_ERROR` /
  `client_connect_unhandled_exception`. The build generates a patched copy of
  `openvpn/ssl/proto.hpp` at configure time (the submodule commit is unchanged)
  that gates the advertisement on
  `SSLLib::SSLAPI::support_key_material_export()`, mirroring the existing
  `IV_PROTO_TLS_KEY_EXPORT` guard. See ADR-006.

### Events

The app maps actual core event names including `CONNECTING`, `CONNECTED`,
`RECONNECTING`, `DISCONNECTED`, `AUTH_FAILED`, `DYNAMIC_CHALLENGE`,
`TUN_SETUP_FAILED`, `TUN_ERROR`, `CONNECTION_TIMEOUT`, `PAUSE`, `RESUME`, and
logs. The native bridge also emits `PROFILE_REJECTED`, `CREDENTIALS_REJECTED`,
and `SESSION_FINISHED` based on `OpenVPNClient` return statuses.

`pause_on_connection_timeout()` returns `true` only after this client has
reached `CONNECTED`. A post-connect timeout then becomes Core's `PAUSE` state
instead of a fatal `CONNECTION_TIMEOUT`; the service maps `PAUSE`/`RESUME` to
`RECONNECTING` and retries through `OpenVPNClient::resume()` every 5 seconds
while the network is online. An initial connection timeout still returns
`false` and is shown as an error. See ADR-007.

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

- **External PKI provider** profiles (an explicit `EXTERNAL_PKI` marker, where
  the certificate and key come from an alias/provider at runtime) are rejected;
  the callbacks return an error rather than pretending to support an alias/key
  provider. Profiles that merely have no client certificate (username/password
  only) are supported through Core's `setenv CLIENT_CERT 0` directive, see
  "Certificate-less username/password profiles" above.
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
- **Compression:** symmetric compression is refused (VORACLE mitigation). The
  app sets `compressionMode = "asym"` so providers that push asymmetric
  server-to-client LZO compression still work instead of failing right after
  `CONNECTED` with `COMPRESS_ERROR`; see ADR-005.
- **Dynamic tls-crypt:** not advertised on the mbedTLS build because the pinned
  Core 3.9 mbedTLS backend cannot export keying material. Servers fall back to
  the control-channel protection used for the initial handshake; a server that
  requires dynamic tls-crypt cannot complete a rekey with this build. See
  ADR-006.
- **Paused reconnect gap:** a post-connect connection timeout enters Core's
  `PAUSE` state, which tears down the old TUN. The service retries every 5
  seconds and creates a fresh TUN on resume; `tunPersist` remains disabled by
  design. See ADR-007.
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
- Crypto self-test, native session lifecycle, Keystore, and profile import were
  executed on an Android 11 x86_64 emulator (5 instrumentation tests, all pass).
- A real tunnel was established against a controlled OpenVPN 2.7 server:
  TLS 1.3 / AES-256-GCM, TUN `10.8.0.2`, MTU 1500, ICMP to `10.8.0.1` through
  the tunnel, and clean disconnect removing the TUN route. See
  `docs/testing.md`. Physical-device and leak tests remain pending.
- Certificate-less username/password import (ADR-004): 36 JVM unit tests pass;
  the full instrumentation suite (9 tests) passes on an Android 16 (API 36)
  x86_64 emulator, including `UserPasswordProfileInstrumentedTest`, which proves
  with the pinned native Core that a real provider profile evaluates with
  `externalPki=true` before adaptation and `externalPki=false` after
  `setenv CLIENT_CERT 0`, and that the production importer stores the adapted
  profile and dedupes re-imports. A live handshake against the provider was not
  run (no credentials available).
