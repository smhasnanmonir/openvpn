#!/usr/bin/env bash
# Fetch the official OpenVPN installer that the Windows app bundles, so a
# Windows user does not have to install OpenVPN separately.
#
# The installer is not committed: it is a build input, like the helper
# binaries. The Windows release job runs this before `mygo build`, and MyGo
# copies resources/<platform> next to the executable.
#
# The SHA-256 values are pinned here and in internal/helper/openvpn_bundle.go.
# scripts/check-openvpn-bundle.py fails when the two disagree.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="2.7.1-I001"
BASE="https://swupdate.openvpn.net/community/releases/OpenVPN-${VERSION}"

# sha256 of OpenVPN-${VERSION}-<arch>.msi
sha256_for() {
  case "$1" in
    amd64) echo "821415899fda40063b2740fa239c557435965c5c9df5a6260cc6f684f02e65b8" ;;
    arm64) echo "039191156e49c964ed9250c0a191177a15ef963f2d5553bb94038ca711771ae7" ;;
    *) return 1 ;;
  esac
}

check_only=0
[[ ${1:-} == "--check" ]] && check_only=1

for arch in amd64 arm64; do
  out="resources/windows-${arch}/openvpn-installer.msi"
  want="$(sha256_for "$arch")"
  if [[ -f "$out" ]]; then
    got="$(sha256sum "$out" | cut -d' ' -f1)"
    if [[ "$got" == "$want" ]]; then
      echo "ok: $out (OpenVPN $VERSION $arch)"
      continue
    fi
    echo "mismatch: $out has $got, want $want" >&2
    [[ $check_only -eq 1 ]] && exit 1
    rm -f "$out"
  fi
  if [[ $check_only -eq 1 ]]; then
    echo "missing: $out" >&2
    exit 1
  fi
  mkdir -p "$(dirname "$out")"
  echo "==> fetching OpenVPN $VERSION $arch"
  tmp="$out.part"
  curl -fsSL --retry 3 --max-time 300 -o "$tmp" "${BASE}-${arch}.msi"
  got="$(sha256sum "$tmp" | cut -d' ' -f1)"
  if [[ "$got" != "$want" ]]; then
    rm -f "$tmp"
    echo "sha256 mismatch for ${BASE}-${arch}.msi: got $got, want $want" >&2
    exit 1
  fi
  mv "$tmp" "$out"
  echo "ok: $out"
done
