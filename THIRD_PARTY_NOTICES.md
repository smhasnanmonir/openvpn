# Third-party notices

The application bundles or links the following third-party components. This
file is a summary; the authoritative license texts are in the referenced
upstream repositories/archives.

| Component | Pinned version | License | Use |
|---|---|---|---|
| OpenVPN 3 Core Library | release/3.9, commit `10c6a56cb17fd015a2d24953c3c42145bbcdea58` | AGPL-3.0-only with OpenSSL linking exception | Protocol, TLS, certificate validation, packet processing |
| asio | 1.24.0 + OpenVPN patches | Boost Software License 1.0 | Header-only async I/O for OpenVPN Core |
| fmt | 10.2.1 | MIT | OpenVPN Core formatting (header-only build) |
| jsoncpp | 1.9.6 | MIT | OpenVPN Core configuration JSON support |
| lz4 | 1.9.4 | BSD-2-Clause | OpenVPN compression support |
| mbedTLS | 3.6.2 | Apache-2.0 | TLS/crypto backend for OpenVPN Core |
| AndroidX / Jetpack Compose | Compose BOM 2024.12.01, Activity 1.9.3, Lifecycle 2.8.7, Biometric 1.1.0 | Apache-2.0 | Android UI, lifecycle, biometric prompt |
| Kotlin / kotlinx.coroutines | Kotlin 2.0.21, coroutines 1.9.0 | Apache-2.0 | Kotlin runtime and coroutines |
| JUnit 4 | 4.13.2 | EPL-1.0 | Unit tests only, not distributed |
| org.json:json | 20240303 | JSON License | JVM unit tests only, not distributed |
| AndroidX Test | 1.2.1 / 1.6.1 / 1.6.2 | Apache-2.0 | Instrumentation tests only, not distributed |

## OpenVPN 3 Core license note

At the pinned 3.9 revision, `third_party/openvpn3/LICENSE.rst` states that the
library is distributed under **GNU Affero General Public License version 3**
with an additional permission to link against OpenSSL. Later upstream revisions
changed the license to a dual MPL-2.0/AGPL-3.0 model; this project does not
claim that later license for the pinned revision. If the core is upgraded, the
license delta must be reviewed and the project license revisited.

The complete AGPL-3.0 text is in `LICENSE`. The OpenVPN Core source is present
as a git submodule at `third_party/openvpn3` and is also available from
<https://github.com/OpenVPN/openvpn3>. The application source is this
repository, satisfying the AGPL corresponding-source requirement.

No third-party analytics, advertising, crash-reporting, or backend SDKs are
included.
