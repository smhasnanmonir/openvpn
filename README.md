# OpenVPN Clients

A monorepo for two production-minded OpenVPN clients that share the same
security model:

| Client | Platform | Stack | Location |
|---|---|---|---|
| **OpenVPN for Android** | Android 8+ | Kotlin, Jetpack Compose, `VpnService`, OpenVPN 3 Core 3.9 via JNI | [`android/`](android/) |
| **OpenVPN Desktop** | Linux (Windows/macOS compile) | Go 1.27+, [MyGo](https://mygo.run/) native UI, system OpenVPN 2.7+ | [`desktop/`](desktop/) |

Both clients import user-supplied `.ovpn` profiles, require a verifiable server
trust anchor, store profiles/credentials encrypted, fail closed on DNS leaks,
expose honest connection state, and have no account system, backend, analytics
or crash reporter.

## Quick start

### Android

```bash
cd android
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Details, toolchain, native build, 16 KB alignment, release signing and the
device/store checklist: [`android/README.md`](android/README.md) and
[`android/AGENTS.md`](android/AGENTS.md).

### Desktop

```bash
cd desktop
go test ./... && go vet ./... && go build ./...
go tool mygo build              # build/linux-amd64/openvpn-desktop
sudo ./scripts/install-helper.sh   # polkit (recommended) or sudo
./build/linux-amd64/openvpn-desktop
```

A real tunnel can be verified without a provider:

```bash
cd desktop
sudo ./scripts/test-live-tunnel.sh
```

Details, security model, parity matrix and verification:
[`desktop/README.md`](desktop/README.md),
[`desktop/SECURITY.md`](desktop/SECURITY.md),
[`desktop/docs/`](desktop/docs/).

## Repository layout

```
android/    Android client (Gradle, Kotlin, native OpenVPN 3 Core submodule)
desktop/    Linux/desktop client (Go, MyGo, privileged helper)
.github/    CI: android.yml, desktop.yml, security.yml, dependabot.yml
LICENSE     AGPL-3.0
```

The pinned OpenVPN 3 Core submodule lives at
`android/third_party/openvpn3`; do not modify it. The desktop client uses the
distribution's OpenVPN and does not build the submodule.

## Security

- Android: [`android/docs/security.md`](android/docs/security.md),
  [`android/SECURITY.md`](android/SECURITY.md), and the readiness/leak-test
  material under [`android/docs/security/`](android/docs/security/).
- Desktop: [`desktop/SECURITY.md`](desktop/SECURITY.md) and
  [`desktop/docs/architecture.md`](desktop/docs/architecture.md).

Report vulnerabilities privately as described in
[`android/SECURITY.md`](android/SECURITY.md) (the repository-wide policy) or via
a GitHub security advisory. Do not include credentials or private keys in a
public issue.

## License

AGPL-3.0. See [`LICENSE`](LICENSE). The Android client embeds OpenVPN 3 Core
under its upstream license; desktop packages use the distribution's OpenVPN.
