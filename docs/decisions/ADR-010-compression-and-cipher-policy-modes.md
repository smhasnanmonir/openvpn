# ADR-010: Explicit compression and cipher policy modes

## Status

Accepted.

## Context

Two data-channel weaknesses are commonly found in VPN clients:

1. **Compression.** Symmetric compression combined with CBC ciphers enables
   VORACLE-style attacks. OpenVPN Core 3.9 defaults to
   `compression_mode = COMPRESS_NO`, but that default *fails the connection*
   with `COMPRESS_ERROR` when a server pushes asymmetric LZO (ADR-005). To keep
   real providers working, this app sets `compressionMode = "asym"`: it accepts
   server→client compression only, refuses symmetric compression, and never
   compresses client→server data.
2. **Legacy data ciphers.** The profile (or a server push) can still select
   non-AEAD ciphers such as BF-CBC or AES-CBC. AEAD ciphers
   (AES-GCM/ChaCha20-Poly1305) provide authenticated encryption and are the
   only safe modern choice.

Issue #19 asks for explicit strict modes. The design constraint is that
hardening must not be silent: compatibility mode is a deliberate, documented
trade-off (ADR-005), and users who want strict behaviour must opt in and see
what policy is active.

## Decision

1. **Compression policy setting with a compatibility default.**
   `CompressionPolicy` has two values:
   - `COMPATIBILITY` (default): `config.compressionMode = "asym"`, the
     provider-compatible LZO-ASYM behaviour from ADR-005.
   - `STRICT`: `config.compressionMode = "no"`. The build-time generated Core
     patch (#3, see `docs/openvpn-core-integration.md`) makes a real
     server-pushed compressor fail **loudly** with an explicit
     server-push/`COMPRESS_ERROR` instead of silently degrading to asymmetric
     mode. A compression **stub** (`COMP_STUB`, which carries no compressed
     payload) is still accepted so stub-pushing providers keep working; only a
     real compressor is refused. A server that requires compression cannot be
     used in strict mode; that is the intended outcome, not a bug.
2. **Cipher policy setting with a profile default.**
   `CipherPolicy` has two values:
   - `PROFILE` (default): the cipher directives in the profile are used as-is
     (Core still negotiates and validates them; the app never enables legacy
     algorithms itself).
   - `AEAD_ONLY`: the connect-time configuration is transformed to append
     `pull-filter accept "cipher AES-256-GCM"`, `pull-filter accept "cipher
     AES-128-GCM"`, `pull-filter accept "cipher CHACHA20-POLY1305"` and then
     `pull-filter reject cipher`. This refuses a server-pushed non-AEAD
     cipher and forces the AEAD set. A profile that pins a non-AEAD cipher
     (or a server that only offers one) fails with a clear message rather than
     negotiating a CBC cipher.
3. **Diagnostics state the active policy.** The settings screen and the
   connection diagnostics report the selected compression and cipher policy, so
   a user or support engineer can tell why a connection was refused. The
   chosen policy is not hidden behind "hardened by default".
4. **No silent change of default.** Compatibility compression and profile
   cipher remain the defaults; this ADR changes no existing behaviour for
   users who do not opt in. Only the new MTU/DNS/trust settings have
   fail-closed defaults (ADR-008/ADR-009), because those are leak classes.
5. **Implementation split.** `ProfileSecurityPolicy` (pure Kotlin) owns the
   connect-time configuration transforms and the user-facing policy text;
   `native_bridge.cpp` owns `cfg.compressionMode`; the compression guard for
   strict mode is a generated `proto.hpp` patch with a fail-fast check, exactly
   like the dynamic tls-crypt patches. The submodule stays pinned.

## Consequences

- Strict compression users get an explicit failure against providers that push
  LZO; they can switch back to compatibility mode, which is documented as a
  deliberate provider trade-off.
- AEAD-only users get a stronger data channel, at the cost of compatibility
  with old servers/profiles that pin CBC ciphers. The failure message names the
  cipher policy so it is diagnosable.
- `pull-filter` is evaluated by Core in order; the transform only adds
  `pull-filter` lines when the profile does not already contain conflicting
  ones, and it is applied at connect time (not stored), so importing the same
  profile under a different policy does not require re-import. The exact
  ordering and quoting are covered by JVM tests on `ProfileSecurityPolicy`.
- The generated `proto.hpp` patch is build-time only; no submodule change. If
  upstream moves the compression push-handling text, the build fails and points
  at `parse_pushed_compression()`.
- This ADR does not claim that compatibility mode is safe against VORACLE
  beyond the asymmetric-only restriction; it is a documented compatibility
  relaxation, and the app still refuses symmetric compression.

## Alternatives considered

- **Default to strict modes.** Rejected: it would break real provider profiles
  that the app is explicitly designed to support (ADR-005), and silent
  hardening without a compatibility path violates the project's "provider
  compatibility is explicit" agreement.
- **Remove `compressionMode = "asym"` and only offer strict.** Rejected for the
  same reason; the setting makes the trade-off explicit instead.
- **Implement cipher filtering in native code only.** Rejected: the
  connect-time config is assembled in Kotlin/native boundary already; a pure
  policy object is testable and keeps the native bridge thin.
- **Warn on BF-CBC instead of refusing in AEAD-only.** The warning is part of
  the profile-import diagnostics, but AEAD-only must actually refuse, otherwise
  it is not a security mode.

## References

- Issue: [#19](https://github.com/smhasnanmonir/openvpn/issues/19)
- ADR-005 (provider compatibility: TLS 1.3 + LZO-ASYM)
- `app/src/main/java/com/smhasnan/openvpn/config/ProfileSecurityPolicy.kt`
- `app/src/main/java/com/smhasnan/openvpn/settings/SettingsRepository.kt`
  (`CompressionPolicy`, `CipherPolicy`)
- `app/src/main/cpp/native_bridge.cpp` (`cfg.compressionMode`)
- `app/src/main/cpp/CMakeLists.txt` (generated `proto.hpp` patches)
- `docs/openvpn-core-integration.md` (patch #3)
