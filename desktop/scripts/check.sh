#!/usr/bin/env bash
# Local desktop gate: the same checks as .github/workflows/desktop.yml.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
export PATH="${PATH}:/usr/local/go/bin"
export GOTOOLCHAIN="${GOTOOLCHAIN:-local}"

python3 scripts/check-signing-config.py
python3 scripts/check-openvpn-bundle.py
go vet ./...
go test ./... -count=1
go test -race ./... -count=1
go build ./...

./scripts/build-helpers.sh
python3 scripts/gen-sbom.py -o build/sbom.cdx.json
go tool mygo build

for deb in build/linux-amd64/*.deb; do
  dpkg-deb -c "$deb" | grep -F 'openvpn-desktop-helper' >/dev/null
done
for tar in build/linux-amd64/*.tar.gz; do
  tar tzf "$tar" | grep -F 'openvpn-desktop-helper' >/dev/null
done

echo "Desktop checks passed."
echo "Live tunnel (root, Linux, TUN): sudo ./scripts/test-live-tunnel.sh"
