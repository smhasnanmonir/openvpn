#!/usr/bin/env bash
# Local desktop gate: the same checks as .github/workflows/desktop.yml.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
export PATH="${PATH}:/usr/local/go/bin"
export GOTOOLCHAIN="${GOTOOLCHAIN:-local}"

go vet ./...
go test ./... -count=1
go build ./...
go tool mygo build

echo "Desktop checks passed."
echo "Live tunnel (root, Linux, TUN): sudo ./scripts/test-live-tunnel.sh"
