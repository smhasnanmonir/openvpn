# ADR-006: Gate dynamic tls-crypt on TLS keying-material export

## Status

Accepted.

## Context

A real provider session (NCVPN / `wlvpn`, the same `groupedServerList` export
used for ADR-005) connected successfully, stayed up for about 54 minutes, and
then disconnected at the first TLS rekey:

```text
02:12:07 INFO UDP send exception: send: Message too long
02:20:18 INFO Transport Error: TLS Keying material export error
02:20:18 INFO DISCONNECTED
02:20:18 ERROR client_connect_unhandled_exception
```

The server push contained:

```text
[protocol-flags] [cc-exit] [dyn-tls-crypt]
```

and Core logged `control channel: dynamic tls-crypt enabled`. On the first
rekey, `ProtoContext::renegotiate()` calls `set_dynamic_tls_crypt()`, which
calls `KeyContext::export_key_material()`. The app builds Core 3.9 with
`USE_MBEDTLS`, and at 3.9 the mbedTLS backend is explicit about the missing
feature:

```cpp
// openvpn/mbedtls/ssl/sslctx.hpp
virtual bool export_keying_material(const std::string &label, unsigned char *, size_t) override
{
    return false; // not implemented in our mbed TLS implementation
}

constexpr static bool support_key_material_export()
{
    /* mbed TLS 2.18+ can support RFC5705 but the API is painful to use */
    return false;
}
```

`export_key_material()` therefore throws
`ErrorCode(Error::KEY_EXPANSION_ERROR, true, "TLS Keying material export error")`.
`Error::KEY_EXPANSION_ERROR` is not handled by `ClientConnect`'s error switch,
so it reaches `default: throw client_connect_unhandled_exception();`. The native
bridge reports that exception text as the `SESSION_FINISHED` error, which is
what the user sees.

The root cause is the capability advertisement in
`ProtoConfig::peer_info_string()`: Core 3.9 sets `IV_PROTO_DYN_TLS_CRYPT`
whenever SHA-256 and AES-256-CTR are available, without checking
`SSLLib::SSLAPI::support_key_material_export()`. A server is therefore allowed
to enable a feature that the mbedTLS build cannot complete at rekey time.

## Decision

1. **Only advertise dynamic tls-crypt when the TLS backend can complete it.**
   At CMake configure time, generate a patched copy of the pinned
   `openvpn/ssl/proto.hpp` and put it before the submodule include directory.
   The generated header changes the advertisement to:

   ```cpp
   if (SSLLib::SSLAPI::support_key_material_export()
       && CryptoAlgs::lookup("SHA256") != CryptoAlgs::NONE
       && CryptoAlgs::lookup("AES-256-CTR") != CryptoAlgs::NONE)
       iv_proto |= IV_PROTO_DYN_TLS_CRYPT;
   ```

   This mirrors the existing `IV_PROTO_TLS_KEY_EXPORT` guard. The same patched
   header also rejects a server-pushed `dyn-tls-crypt` flag when the backend
   cannot export keying material, so a non-conforming server produces a clear
   `Problem accepting protocol-flags: server requires dynamic tls-crypt …`
   error instead of the delayed `KEY_EXPANSION_ERROR` crash at the first rekey.
   The submodule commit stays pinned at
   `10c6a56cb17fd015a2d24953c3c42145bbcdea58`; the patch is a build artifact,
   not a submodule change. If either pinned upstream text no longer matches, the
   build fails with a message pointing at `peer_info_string()` or
   `parse_pushed_protocol_flags()`.

2. **Explain the failure in the app.** `EngineEventMapper` maps
   `client_connect_unhandled_exception` and `TLS Keying material export error`
   to actionable messages while leaving the raw engine detail in Diagnostics.

3. **Keep the workaround narrow.** No TLS verification option is relaxed, no
   certificate check is disabled, and no other Core capability is changed.

## Consequences

- Servers that support dynamic tls-crypt no longer enable it on the mbedTLS
  build. They fall back to the control-channel protection used for the initial
  handshake (plain TLS, `tls-auth`, or `tls-crypt` as configured), which is the
  same protection that already carried the initial connection.
- The session survives the first rekey instead of ending with
  `KEY_EXPANSION_ERROR`.
- A server that *requires* dynamic tls-crypt cannot be used with this build;
  it could not complete a rekey with the pinned mbedTLS backend either, so this
  turns a delayed disconnect into an honest capability negotiation. A server
  that ignores the missing advertisement and pushes the flag anyway is rejected
  during the server-push parse with the server-push error above.
- The patch is tied to the pinned Core 3.9 header. When upgrading Core, check
  whether upstream has added the same guard (or a working mbedTLS exporter); if
  so, remove the generated patch. A future alternative is to upgrade mbedTLS to
  a release with a public keying-material exporter and implement
  `export_keying_material()` in the Core mbedTLS wrapper, then drop the
  advertisement guard.
- `docs/openvpn-core-integration.md` and `docs/testing.md` record the
  limitation and the verification performed.

## References

- Issue: [#4](https://github.com/smhasnanmonir/openvpn/issues/4)
- `app/src/main/cpp/CMakeLists.txt` (generated header and fail-fast check)
- `app/src/main/java/com/smhasnan/openvpn/vpn/EngineEventMapper.kt`
- `third_party/openvpn3/openvpn/ssl/proto.hpp` (`peer_info_string`)
- `third_party/openvpn3/openvpn/mbedtls/ssl/sslctx.hpp`
  (`export_keying_material`, `support_key_material_export`)
- `third_party/openvpn3/openvpn/ssl/protostack.hpp`
  (`KeyContext::export_key_material`)
- `third_party/openvpn3/openvpn/client/cliconnect.hpp`
  (`client_connect_unhandled_exception`)
