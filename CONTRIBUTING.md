# Contributing

## Before changing code

Read:

- `docs/architecture.md`
- `docs/security.md`
- `docs/openvpn-core-integration.md`

Do not invent OpenVPN Core APIs. Inspect the pinned submodule headers and
source. Do not add custom protocol, TLS, certificate-validation, or crypto code.

## Workflow

1. Create a focused change and keep it compiling.
2. Add/update unit tests for pure policy logic; add instrumentation tests for
   device-only behavior.
3. Run:
   ```bash
   ./scripts/build-native-deps.sh arm64-v8a
   ./gradlew -Pabis=arm64-v8a :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
   ```
4. Update docs if behavior, security, or limitations change.
5. Use a descriptive conventional commit, e.g.
   `feat(vpn): protect transport sockets via VpnService.protect`.

## Security rules

- Never disable certificate verification or add "accept all" options.
- Never log credentials, private keys, full profiles, or auth payloads.
- Never put secrets in Intent extras, notification text, filenames, or
  world-readable files.
- Never claim a tunnel is working based on UI state alone.
- Keep profile/credential storage encrypted and out of backups.
- Preserve the file/ownership rules for TUN fds and native sessions.

## License

Contributions are accepted under AGPL-3.0-only, the project license. The pinned
OpenVPN Core 3.9 revision is AGPL-3.0-only with an OpenSSL linking exception.
