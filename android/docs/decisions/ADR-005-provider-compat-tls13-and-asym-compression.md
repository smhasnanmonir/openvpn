# ADR-005: Provider compatibility for TLS 1.3-only servers and asymmetric compression

## Status

Accepted.

## Context

Real-world provider profiles (tested with the NCVPN `groupedServerList` export)
failed to connect in two distinct places, with valid credentials:

1. **Silent TLS stall.** The profiles pin legacy TLS 1.2 cipher suites:

   ```
   tls-cipher TLS-DHE-RSA-WITH-AES-256-CBC-SHA:TLS-DHE-DSS-WITH-AES-256-CBC-SHA:TLS-RSA-WITH-AES-256-CBC-SHA
   ```

   The provider's servers now answer with TLS 1.3 only. With the pin in place the
   handshake never completes and the connection ends with a silent
   `CONNECTION_TIMEOUT` (no `AUTH_FAILED`). Removing that one line produced
   immediate TLS 1.3 (`TLS1-3-AES-256-GCM-SHA384`), a successful `VERIFY OK`, and
   a valid session.

2. **Compression policy teardown.** After TLS, authentication, and TUN setup the
   server pushes LZO asymmetric compression (`LZO-ASYM ... asym=1`). OpenVPN
   Core 3.9 defaults to `compression_mode = COMPRESS_NO`
   (`openvpn/ssl/proto_context_options.hpp`) and raises `COMPRESS_ERROR` right
   after `CONNECTED` (`openvpn/client/cliproto.hpp`), tearing the session down.
   The app never set `ClientAPI::Config::compressionMode`.

Both failures were also invisible to the user because the core's fatal event
renderers are empty; that separate defect was fixed in the event/logging layer
(see the changelog).

## Decision

1. **`tls-cipher` normalization at import and connect.** When every suite in a
   real `tls-cipher` directive is a legacy non-ECDHE suite, remove that directive
   from the stored merged profile and re-validate with `eval_config`. The same
   normalization is applied at connect time so profiles imported by earlier app
   versions (stored with the pin) also work without re-importing. `tls-cipher`
   only selects TLS 1.2 suites; TLS 1.3 suites are negotiated by the TLS library
   regardless, so the pin cannot help a TLS 1.3-only server. Pins that contain an
   ECDHE suite are kept, commented-out lines are ignored, and the action is
   logged to diagnostics ("removed a legacy-only tls-cipher pin ...").
2. **Asymmetric compression only.** Set `ClientAPI::Config::compressionMode =
   "asym"` in `native_bridge.cpp`. This accepts server-to-client compression
   (the provider's LZO-ASYM push) while symmetric compression stays refused.
3. Keep the changes narrow: no custom TLS stack, no "insecure" verification
   options, no accept-all certificates. Certificate verification, CA checks,
   `remote-cert-tls server`, and `verify-x509-name` remain in force.

## Consequences

- Provider profiles with a legacy-only `tls-cipher` pin can negotiate TLS 1.3
  after import, and profiles whose servers push LZO-ASYM stay connected instead
  of ending with `COMPRESS_ERROR`.
- The stored profile content differs from the selected file by that removed
  directive (in addition to the existing `setenv CLIENT_CERT 0` adaptation for
  certificate-less profiles); this is documented in `docs/profile-import.md` and
  logged on import.
- Compression policy is relaxed for the server-to-client direction only.
  Symmetric compression remains disabled, and the app does not add
  `comp-lzo`/`compress` directives of its own.
- A profile that deliberately pins a legacy cipher list for compliance reasons
  will have that pin removed; the app then uses the bundled mbedTLS defaults. If
  a strict pin is required, the profile cannot connect to a TLS 1.3-only server
  through the pinned OpenVPN 3.9 core either.
- The core's compression and TLS-cipher behavior is tied to the pinned
  `release/3.9` revision; re-verify these settings when upgrading the submodule.
