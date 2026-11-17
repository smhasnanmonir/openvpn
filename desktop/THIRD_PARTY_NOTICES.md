# Third-party notices

OpenVPN Desktop is licensed under the GNU Affero General Public License v3.0
only (`AGPL-3.0-only`). The full text is in [`LICENSE`](LICENSE). The source is
the `desktop/` directory of this repository.

The desktop app is built with the following Go modules. Modules marked
“build/test only” are not part of the shipped application.

| Module | Version | License |
|---|---|---|
| `github.com/egoist/mygo` | 0.9.2 | MIT |
| `github.com/ebitengine/purego` | 0.11.1 | Apache-2.0 |
| `github.com/go-text/typesetting` | 0.3.5 | BSD-3-Clause / Unlicense |
| `github.com/go-text/typesetting-utils` | `v0.0.0-20260419141703-4ffe8874dabc` | BSD-3-Clause / Unlicense |
| `github.com/ProtonMail/go-crypto` | 1.4.1 | BSD-3-Clause |
| `github.com/cloudflare/circl` | 1.6.2 | BSD-3-Clause |
| `github.com/klauspost/compress` | 1.18.6 | BSD-3-Clause |
| `github.com/ulikunitz/xz` | 0.5.15 | BSD-3-Clause |
| `go.digitalxero.dev/rpm` | 0.3.4 | MIT |
| `golang.org/x/crypto` | 0.57.0 | BSD-3-Clause |
| `golang.org/x/image` | 0.46.0 | BSD-3-Clause |
| `golang.org/x/net` | 0.59.0 | BSD-3-Clause |
| `golang.org/x/sys` | 0.48.0 | BSD-3-Clause |
| `golang.org/x/text` | 0.43.0 | BSD-3-Clause |
| `github.com/stretchr/testify` | 1.11.1 | MIT (build/test only) |
| `github.com/davecgh/go-spew` | 1.1.1 | ISC (build/test only) |
| `github.com/pmezard/go-difflib` | 1.0.0 | BSD-3-Clause (build/test only) |
| `gopkg.in/yaml.v3` | 3.0.1 | MIT / Apache-2.0 (build/test only) |

The license text for each module is in its module directory under
`$(go env GOMODCACHE)` and is available from the module's repository.

## OpenVPN

OpenVPN Desktop is a client, not a fork of OpenVPN.

- Linux: it runs a root-owned `openvpn` from the system package manager.
- macOS: the installer copies the installed OpenVPN binary to a root-owned
  location; it remains governed by its own license (GPLv2 for OpenVPN 2.x).
- Windows: the package ships the official OpenVPN installer
  (`OpenVPN-2.7.1-I001-amd64.msi`, likewise `arm64`) as
  `openvpn-installer.msi`, fetched from
  <https://swupdate.openvpn.net/community/releases/> by
  `scripts/fetch-openvpn-msi.sh` and pinned by SHA-256. The elevated helper
  verifies that digest and runs it silently, so a Windows user does not install
  OpenVPN separately.

OpenVPN is Copyright (C) OpenVPN Inc. and contributors, licensed under the GNU
General Public License version 2. On Windows it is redistributed unmodified as
a separate program (mere aggregation): it is not linked with this application
and is not a derivative work of it. Corresponding source for the bundled build
is available from <https://github.com/OpenVPN/openvpn/releases> and
<https://github.com/OpenVPN/tap-windows6/releases> for at least three years
after this release, or on request. The installer's own license files are
installed under `C:\Program Files\OpenVPN`.

## Trademark

“OpenVPN” is a trademark of OpenVPN Inc. This project is not affiliated with or
endorsed by OpenVPN Inc. Use of the name here is descriptive; verify trademark
permission before a public product launch, or rename the product.
