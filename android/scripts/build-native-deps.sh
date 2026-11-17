#!/usr/bin/env bash
# Build static Android native dependencies for one or more ABIs.
#
# Usage: scripts/build-native-deps.sh [arm64-v8a] [x86_64]
# Requires: ANDROID_NDK_HOME or ANDROID_SDK_ROOT/ndk/<version>, cmake, ninja.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="${DEPS_CACHE:-$ROOT/.native-deps/cache}"
BUILD_ROOT="${DEPS_BUILD_DIR:-$ROOT/.native-deps/build}"
PREFIX_ROOT="$ROOT/app/src/main/cpp/deps"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-26}"
JOBS="${JOBS:-$(nproc)}"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  if [[ -z "$SDK" ]]; then
    echo "Set ANDROID_NDK_HOME or ANDROID_SDK_ROOT" >&2
    exit 1
  fi
  NDK="$SDK/ndk/27.2.12479018"
else
  NDK="$ANDROID_NDK_HOME"
fi
[[ -f "$NDK/build/cmake/android.toolchain.cmake" ]] || {
  echo "Android NDK not found at $NDK" >&2
  exit 1
}

CMAKE="${CMAKE_BIN:-}"
if [[ -z "$CMAKE" ]]; then
  if [[ -n "$SDK" && -x "$SDK/cmake/3.22.1/bin/cmake" ]]; then
    CMAKE="$SDK/cmake/3.22.1/bin/cmake"
  else
    CMAKE="$(command -v cmake)"
  fi
fi

"$ROOT/scripts/fetch-native-deps.sh"

if [[ $# -eq 0 ]]; then
  set -- arm64-v8a x86_64
fi

extract() {
  local archive="$1" dirname="$2"
  if [[ ! -d "$BUILD_ROOT/$dirname" ]]; then
    mkdir -p "$BUILD_ROOT"
    tar -xf "$archive" -C "$BUILD_ROOT"
  fi
}

# Extract sources once and apply the OpenVPN-patched asio headers.
extract "$CACHE/mbedtls-3.6.2.tar.bz2" "mbedtls-3.6.2"
extract "$CACHE/jsoncpp-1.9.6.tar.gz" "jsoncpp-1.9.6"
extract "$CACHE/lz4-1.9.4.tar.gz" "lz4-1.9.4"
extract "$CACHE/fmt-10.2.1.tar.gz" "fmt-10.2.1"
extract "$CACHE/asio-1-24-0.tar.gz" "asio-asio-1-24-0"

ASIO_SRC="$BUILD_ROOT/asio-asio-1-24-0"
if [[ ! -f "$ASIO_SRC/.openvpn-patched" ]]; then
  for p in "$ROOT"/third_party/openvpn3/deps/asio/patches/*.patch; do
    echo "Applying $(basename "$p")"
    patch -p1 -d "$ASIO_SRC" < "$p"
  done
  touch "$ASIO_SRC/.openvpn-patched"
fi

build_mbedtls() {
  local abi="$1" prefix="$2" src="$BUILD_ROOT/out-mbedtls-$abi"
  rm -rf "$src"
  "$CMAKE" -S "$BUILD_ROOT/mbedtls-3.6.2" -B "$src" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_PLATFORM="android-$ANDROID_PLATFORM" -DANDROID_ABI="$abi" \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DCMAKE_C_FLAGS="-DMBEDTLS_SELF_TEST" \
    -DCMAKE_INSTALL_PREFIX="$prefix" \
    -DENABLE_PROGRAMS=OFF -DENABLE_TESTING=OFF \
    -DUSE_SHARED_MBEDTLS_LIBRARY=OFF -DUSE_STATIC_MBEDTLS_LIBRARY=ON \
    -DMBEDTLS_FATAL_WARNINGS=OFF
  "$CMAKE" --build "$src" --target install -j "$JOBS"
}

build_jsoncpp() {
  local abi="$1" prefix="$2" src="$BUILD_ROOT/out-jsoncpp-$abi"
  rm -rf "$src"
  "$CMAKE" -S "$BUILD_ROOT/jsoncpp-1.9.6" -B "$src" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_PLATFORM="android-$ANDROID_PLATFORM" -DANDROID_ABI="$abi" \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DCMAKE_INSTALL_PREFIX="$prefix" -DBUILD_SHARED_LIBS=OFF \
    -DJSONCPP_WITH_TESTS=OFF -DJSONCPP_WITH_POST_BUILD_UNITTEST=OFF \
    -DJSONCPP_WITH_CMAKE_PACKAGE=OFF
  "$CMAKE" --build "$src" --target install -j "$JOBS"
}

build_lz4() {
  local abi="$1" prefix="$2" src="$BUILD_ROOT/out-lz4-$abi"
  rm -rf "$src"
  "$CMAKE" -S "$BUILD_ROOT/lz4-1.9.4/build/cmake" -B "$src" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_PLATFORM="android-$ANDROID_PLATFORM" -DANDROID_ABI="$abi" \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DCMAKE_INSTALL_PREFIX="$prefix" -DBUILD_SHARED_LIBS=OFF \
    -DLZ4_BUILD_CLI=OFF -DLZ4_BUILD_LEGACY_LZ4C=OFF
  "$CMAKE" --build "$src" --target install -j "$JOBS"
}

install_headers() {
  local prefix="$1"
  mkdir -p "$prefix/include"
  cp -R "$BUILD_ROOT/fmt-10.2.1/include/fmt" "$prefix/include/"
  cp -R "$ASIO_SRC/asio/include/asio" "$prefix/include/"
  cp "$ASIO_SRC/asio/include/asio.hpp" "$prefix/include/"
}

for abi in "$@"; do
  prefix="$PREFIX_ROOT/$abi"
  if [[ -f "$prefix/lib/libmbedtls.a" && -f "$prefix/include/asio.hpp" && "${FORCE_DEPS:-0}" != "1" ]]; then
    echo "==> Reusing native dependencies for $abi (set FORCE_DEPS=1 to rebuild)"
    continue
  fi
  echo "==> Building native dependencies for $abi"
  mkdir -p "$prefix"
  install_headers "$prefix"
  build_mbedtls "$abi" "$prefix"
  build_jsoncpp "$abi" "$prefix"
  build_lz4 "$abi" "$prefix"
  echo "==> Done: $prefix"
done
