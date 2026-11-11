#!/usr/bin/env bash
# Verify that every native shared library packaged in an APK is compatible with
# Android 15+ 16 KB memory pages:
#   * every ELF LOAD segment must have p_align >= 0x4000 (16 KB), and
#   * the APK itself must pass `zipalign -c -P 16 4` (page-aligned zip entries).
#
# Usage:
#   scripts/check-16kb-alignment.sh [--apk <path>] [--elf <path>]... [--skip-zipalign]
#
# Environment:
#   ANDROID_NDK_HOME / ANDROID_NDK_ROOT / ANDROID_SDK_ROOT / ANDROID_HOME
#   SKIP_ZIPALIGN=1     skip the zipalign check (useful when only ELF checks run)
#
# Exits non-zero on any misaligned LOAD segment, missing library, missing
# toolchain, or (unless skipped) a failed zipalign verification.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

APK="app/build/outputs/apk/debug/app-debug.apk"
APK_EXPLICIT=0
ELFS=()
SKIP_ZIPALIGN="${SKIP_ZIPALIGN:-0}"
NDK_VERSION="${ANDROID_NDK_VERSION:-27.2.12479018}"
MIN_ALIGN=$((0x4000)) # 16 KB
failures=0
checked=0

usage() {
  cat >&2 <<'USAGE'
Verify that every native shared library packaged in an APK is compatible with
Android 15+ 16 KB memory pages (every ELF LOAD segment p_align >= 0x4000) and
that the APK passes `zipalign -c -P 16 4`.

Usage:
  scripts/check-16kb-alignment.sh [APK] [--apk APK] [--elf PATH]... [--skip-zipalign]

Environment:
  ANDROID_NDK_HOME / ANDROID_NDK_ROOT / ANDROID_SDK_ROOT / ANDROID_HOME
  SKIP_ZIPALIGN=1     skip the zipalign check (useful when only ELF checks run)

Exits non-zero on any misaligned LOAD segment, missing library, missing
toolchain, or (unless skipped) a failed zipalign verification.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk)
      [[ $# -ge 2 ]] || { echo "error: --apk needs a path" >&2; exit 2; }
      APK="$2"
      APK_EXPLICIT=1
      shift 2
      ;;
    --apk=*)
      APK="${1#--apk=}"
      APK_EXPLICIT=1
      shift
      ;;
    --elf)
      [[ $# -ge 2 ]] || { echo "error: --elf needs a path" >&2; exit 2; }
      ELFS+=("$2")
      shift 2
      ;;
    --elf=*)
      ELFS+=("${1#--elf=}")
      shift
      ;;
    --skip-zipalign)
      SKIP_ZIPALIGN=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      echo "error: unknown argument: $1" >&2
      usage
      exit 2
      ;;
    *)
      # Positional argument: the APK path (convenience for CI/release scripts).
      if [[ "$APK" != "app/build/outputs/apk/debug/app-debug.apk" ]]; then
        echo "error: more than one APK path given: $APK and $1" >&2
        exit 2
      fi
      APK="$1"
      APK_EXPLICIT=1
      shift
      ;;
  esac
done

log() { printf '%s\n' "$*"; }
err() { printf 'error: %s\n' "$*" >&2; }

# --- locate llvm-readelf -----------------------------------------------------
find_readelf() {
  local candidate dirs dir
  dirs=()
  [[ -n "${ANDROID_NDK_HOME:-}" ]] && dirs+=("$ANDROID_NDK_HOME")
  [[ -n "${ANDROID_NDK_ROOT:-}" ]] && dirs+=("$ANDROID_NDK_ROOT")
  local sdk=""
  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    sdk="$ANDROID_SDK_ROOT"
  elif [[ -n "${ANDROID_HOME:-}" ]]; then
    sdk="$ANDROID_HOME"
  fi
  # Prefer the pinned NDK, then any installed NDK (sorted).
  if [[ -n "$sdk" && -d "$sdk/ndk" ]]; then
    if [[ -d "$sdk/ndk/$NDK_VERSION" ]]; then
      dirs+=("$sdk/ndk/$NDK_VERSION")
    fi
    while IFS= read -r dir; do
      [[ "$dir" == "$sdk/ndk/$NDK_VERSION" ]] && continue
      dirs+=("$dir")
    done < <(find "$sdk/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V)
  fi
  for dir in "${dirs[@]}"; do
    candidate="$(find "$dir/toolchains/llvm/prebuilt" -mindepth 3 -maxdepth 3 \
      -type f -name 'llvm-readelf*' 2>/dev/null | head -n 1 || true)"
    if [[ -n "$candidate" && -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  if command -v llvm-readelf >/dev/null 2>&1; then
    command -v llvm-readelf
    return 0
  fi
  return 1
}

READELF="$(find_readelf || true)"
if [[ -z "$READELF" ]]; then
  err "llvm-readelf not found. Set ANDROID_NDK_HOME (e.g. \$ANDROID_SDK_ROOT/ndk/$NDK_VERSION) or install the NDK."
  exit 2
fi
log "Using readelf: $READELF"

# --- locate zipalign ---------------------------------------------------------
find_zipalign() {
  local sdk="" dir
  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    sdk="$ANDROID_SDK_ROOT"
  elif [[ -n "${ANDROID_HOME:-}" ]]; then
    sdk="$ANDROID_HOME"
  fi
  if [[ -n "$sdk" && -d "$sdk/build-tools" ]]; then
    local candidate
    for candidate in "$sdk/build-tools/35.0.0/zipalign" "$sdk/build-tools/35.0.0/zipalign.exe"; do
      if [[ -x "$candidate" ]]; then
        printf '%s\n' "$candidate"
        return 0
      fi
    done
    while IFS= read -r dir; do
      for candidate in "$dir/zipalign" "$dir/zipalign.exe"; do
        if [[ -x "$candidate" ]]; then
          printf '%s\n' "$candidate"
          return 0
        fi
      done
    done < <(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V -r)
  fi
  if command -v zipalign >/dev/null 2>&1; then
    command -v zipalign
    return 0
  fi
  return 1
}

# --- ELF check ---------------------------------------------------------------
check_elf() {
  local so="$1"
  local display="$2"
  local loads align count=0
  if [[ ! -f "$so" ]]; then
    err "$display: file not found: $so"
    failures=$((failures + 1))
    return
  fi
  loads="$("$READELF" -lW "$so" 2>/dev/null | awk '$1 == "LOAD" { print $NF }')"
  if [[ -z "$loads" ]]; then
    err "$display: no LOAD segments found (not an ELF shared object?)"
    failures=$((failures + 1))
    return
  fi
  while IFS= read -r align; do
    [[ -z "$align" ]] && continue
    count=$((count + 1))
    # Accept 0x... and decimal forms; bash arithmetic understands 0x-prefixed.
    local numeric
    numeric="$(printf '%d' "$((align))" 2>/dev/null || printf '%d' "$align" 2>/dev/null || echo -1)"
    if [[ "$numeric" -lt "$MIN_ALIGN" ]]; then
      err "$display: LOAD[$((count - 1))] p_align=$align (< 0x4000) — not 16 KB compatible"
      failures=$((failures + 1))
    else
      log "  $display: LOAD[$((count - 1))] p_align=$align OK"
    fi
  done <<< "$loads"
  checked=$((checked + 1))
}

TMP_DIR=""
cleanup() {
  if [[ -n "$TMP_DIR" && -d "$TMP_DIR" ]]; then
    rm -rf "$TMP_DIR"
  fi
}
trap cleanup EXIT

if [[ ${#ELFS[@]} -gt 0 ]]; then
  for so in "${ELFS[@]}"; do
    check_elf "$so" "$so"
  done
fi

# Check APK contents when no --elf was given (normal CI/release mode), or when
# an APK path was explicitly requested alongside --elf libraries.
if [[ ${#ELFS[@]} -eq 0 || "$APK_EXPLICIT" -eq 1 ]]; then
  APK_CHECK=1
else
  APK_CHECK=0
fi

if [[ "$APK_CHECK" -eq 1 ]]; then
  if [[ ! -f "$APK" ]]; then
    err "APK not found: $APK (build it first, or pass --elf <path> for a direct library check)"
    exit 1
  fi
  TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/openvpn-16kb.XXXXXX")"
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$APK" 'lib/*/*.so' -d "$TMP_DIR"
  elif command -v python3 >/dev/null 2>&1; then
    python3 - "$APK" "$TMP_DIR" <<'PY'
import sys, zipfile
apk, dest = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk) as z:
    for name in z.namelist():
        if name.startswith("lib/") and name.endswith(".so"):
            z.extract(name, dest)
PY
  else
    err "neither unzip nor python3 is available to read the APK"
    exit 2
  fi
  mapfile -t packaged < <(find "$TMP_DIR/lib" -type f -name '*.so' 2>/dev/null | sort)
  if [[ ${#packaged[@]} -eq 0 ]]; then
    err "no .so files found in $APK under lib/<abi>/ — refusing to report success"
    exit 1
  fi
  log "Checking ${#packaged[@]} packaged native library/libraries in $APK"
  for so in "${packaged[@]}"; do
    rel="${so#"$TMP_DIR"/}"
    check_elf "$so" "$rel"
  done
fi

if [[ "$checked" -eq 0 ]]; then
  err "no native libraries were checked"
  exit 1
fi

# --- APK zip alignment -------------------------------------------------------
if [[ "$SKIP_ZIPALIGN" -eq 1 ]]; then
  log "Skipping zipalign check (SKIP_ZIPALIGN=1)"
elif [[ "$APK_CHECK" -eq 1 ]]; then
  ZIPALIGN="$(find_zipalign || true)"
  if [[ -z "$ZIPALIGN" ]]; then
    err "zipalign not found (Android SDK build-tools). Set ANDROID_SDK_ROOT/ANDROID_HOME or install build-tools 35.0.0."
    exit 2
  fi
  log "Using zipalign: $ZIPALIGN"
  if "$ZIPALIGN" -c -P 16 4 "$APK"; then
    log "zipalign -c -P 16 4: OK"
  else
    err "zipalign -c -P 16 4 failed: APK zip entries are not 16 KB page aligned"
    exit 1
  fi
else
  log "APK not checked (--elf mode); skipping zipalign"
fi

if [[ "$failures" -gt 0 ]]; then
  err "$failures 16 KB alignment failure(s)"
  exit 1
fi
log "16 KB alignment OK ($checked native library/libraries checked)"
