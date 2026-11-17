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

OpenVPN Desktop is a client; it does **not** bundle the OpenVPN program. It
executes an OpenVPN 2.7+ binary installed by the user:

- Linux: a root-owned `openvpn` from the system package manager.
- macOS: the installer copies the installed OpenVPN binary to a root-owned
  location; it remains governed by its own license (GPLv2 for OpenVPN 2.x).
- Windows: the OpenVPN Community installer's `openvpn.exe` in Program Files.

If you redistribute OpenVPN itself with this app, comply with its GPLv2 terms
and include its source or a written offer as required.

## Trademark

“OpenVPN” is a trademark of OpenVPN Inc. This project is not affiliated with or
endorsed by OpenVPN Inc. Use of the name here is descriptive; verify trademark
permission before a public product launch, or rename the product.
