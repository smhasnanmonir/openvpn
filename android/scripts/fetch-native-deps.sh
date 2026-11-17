#!/usr/bin/env bash
# Download and verify the third-party native dependencies used to build the
# OpenVPN 3 Core integration. Nothing downloaded here is committed.
#
# Usage: scripts/fetch-native-deps.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="${DEPS_CACHE:-$ROOT/.native-deps/cache}"
mkdir -p "$CACHE"

fetch() {
  local name="$1" url="$2" sha256="$3"
  if [[ ! -f "$CACHE/$name" ]]; then
    echo "Downloading $name"
    curl -fL --retry 3 --connect-timeout 30 -o "$CACHE/$name.part" "$url"
    mv "$CACHE/$name.part" "$CACHE/$name"
  fi
  echo "$sha256  $CACHE/$name" | sha256sum -c - >/dev/null
}

# Pinned versions. These match the dependency versions used by OpenVPN 3 Core 3.9
# (asio 1.24.0 plus the OpenVPN patches shipped in the core tree).
fetch "asio-1-24-0.tar.gz" \
  "https://github.com/chriskohlhoff/asio/archive/refs/tags/asio-1-24-0.tar.gz" \
  "cbcaaba0f66722787b1a7c33afe1befb3a012b5af3ad7da7ff0f6b8c9b7a8a5b"
fetch "fmt-10.2.1.tar.gz" \
  "https://github.com/fmtlib/fmt/archive/refs/tags/10.2.1.tar.gz" \
  "1250e4cc58bf06ee631567523f48848dc4596133e163f02615c97f78bab6c811"
fetch "jsoncpp-1.9.6.tar.gz" \
  "https://github.com/open-source-parsers/jsoncpp/archive/refs/tags/1.9.6.tar.gz" \
  "f93b6dd7ce796b13d02c108bc9f79812245a82e577581c4c9aabe57075c90ea2"
fetch "lz4-1.9.4.tar.gz" \
  "https://github.com/lz4/lz4/archive/refs/tags/v1.9.4.tar.gz" \
  "0b0e3aa07c8c063ddf40b082bdf7e37a1562bda40a0ff5272957f3e987e0e54b"
# The official release archive is used because the GitHub source archive does
# not contain the "framework" submodule required by mbedTLS' CMake build.
fetch "mbedtls-3.6.2.tar.bz2" \
  "https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-3.6.2/mbedtls-3.6.2.tar.bz2" \
  "8b54fb9bcf4d5a7078028e0520acddefb7900b3e66fec7f7175bb5b7d85ccdca"

echo "Native dependency sources are present and checksum-verified in $CACHE"
