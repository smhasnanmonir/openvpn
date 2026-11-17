#!/usr/bin/env bash
# Build the privileged helper for every release target and place it in the
# MyGo platform resource directories. `mygo build` then ships the right helper
# next to the app (or in Contents/Resources on macOS).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export PATH="${PATH}:/usr/local/go/bin"
export GOTOOLCHAIN="${GOTOOLCHAIN:-local}"
export CGO_ENABLED=0

VERSION="$(python3 -c 'import json; print(json.load(open("mygo.json"))["version"])')"
LDFLAGS="-s -w -X github.com/smhasnanmonir/openvpn/desktop/internal/helper.Version=${VERSION}"

TARGETS=(
  "linux/amd64"
  "linux/arm64"
  "windows/amd64"
  "windows/arm64"
  "darwin/arm64"
  "darwin/amd64"
)

if [[ ${1:-} == "--clean" ]]; then
  rm -rf resources/linux-amd64 resources/linux-arm64 \
    resources/windows-amd64 resources/windows-arm64 \
    resources/darwin-arm64 resources/darwin-amd64
  echo "removed helper resource directories"
  exit 0
fi

for target in "${TARGETS[@]}"; do
  goos="${target%/*}"
  goarch="${target#*/}"
  name="openvpn-desktop-helper"
  if [[ "$goos" == "windows" ]]; then
    name+=".exe"
  fi
  out="resources/${goos}-${goarch}/${name}"
  mkdir -p "$(dirname "$out")"
  echo "==> ${goos}/${goarch}"
  GOOS="$goos" GOARCH="$goarch" go build -trimpath -ldflags "$LDFLAGS" \
    -o "$out" ./cmd/openvpn-desktop-helper
  printf '%s\n' "$VERSION" > "resources/${goos}-${goarch}/helper.version"
done

echo "built:"
ls -l resources/*/openvpn-desktop-helper resources/*/openvpn-desktop-helper.exe 2>/dev/null || true
