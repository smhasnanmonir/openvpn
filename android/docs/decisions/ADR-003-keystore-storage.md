# ADR-003: Use Android Keystore AES-GCM, not custom crypto

## Status

Accepted.

## Context

Profiles and optional credentials must be protected at rest. A homemade
encryption format or a plain preferences store would be unsafe. Jetpack Security
was deprecated.

## Decision

- Generate a 256-bit AES key in Android Keystore for profiles and a separate key
  for credentials.
- Encrypt whole JSON blobs with `AES/GCM/NoPadding`, a random 12-byte IV, and a
  128-bit tag.
- Store `version || ivLength || iv || ciphertext` in app-private
  `noBackupFilesDir`.
- Write atomically (temp, fsync, rename).
- Exclude all storage from cloud backup/device transfer.
- Keep the biometric app lock as a separate UI gate, not a Keystore auth-bound
  key, so the VPN can reconnect in the background.

## Consequences

- Profiles/credentials are unreadable from an offline filesystem copy without
  the app/Keystore.
- A rooted or compromised OS can still access decrypted data while the app is
  running; this is documented.
- Changing key aliases or clearing app data makes existing blobs unreadable.
- Lost keys are not recoverable; profiles must be re-imported.
