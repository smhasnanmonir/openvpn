#!/usr/bin/env bash
# Generate a CycloneDX 1.5 JSON SBOM for the Android app.
#
# The SBOM enumerates:
#   * the app version/build (from APP_VERSION / APP_VERSION_CODE / git commit),
#   * the pinned OpenVPN 3 Core version + commit and the native dependency
#     versions parsed from docs/openvpn-core-integration.md. The core commit is
#     read from the git submodule gitlink (never scraped out of the doc) and the
#     script fails when the submodule is missing or is not at the release pin,
#   * the Android NDK version from app/build.gradle.kts,
#   * the Gradle `releaseRuntimeClasspath` Maven coordinates.
#
# Native upstream dependencies use pkg:github/<org>/<repo> purls so OSV can map
# them to advisories; the app component stays pkg:generic (it is not an upstream
# package). The asio pin is recorded as its upstream version plus the
# openvpn:patches property for the OpenVPN patch count.
#
# Usage:
#   scripts/generate-sbom.sh [--output <file>] [--skip-gradle] [--dependencies-file <file>]
#
# Environment:
#   APP_VERSION, APP_VERSION_CODE, GITHUB_SHA, GRADLE_BIN, GRADLE_ARGS
#
# Requires bash, python3 (JSON assembly), git, and (unless --skip-gradle)
# Gradle (an installed `gradle` command or the project wrapper) plus an
# installed Android SDK. CI runs it after the build.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

OUTPUT="build/sbom/sbom.cdx.json"
SKIP_GRADLE=0
DEPS_FILE=""

# Resolution order: explicit GRADLE_BIN, then a Gradle installation already on
# PATH (CI's setup-gradle provides one), then the project wrapper. The wrapper
# is last on purpose: in network-restricted CI it would try to download the
# Gradle distribution, while an installed `gradle` command works offline from
# the warm dependency cache.
if [[ -n "${GRADLE_BIN:-}" ]]; then
  GRADLE="$GRADLE_BIN"
elif command -v gradle >/dev/null 2>&1; then
  GRADLE="gradle"
else
  GRADLE="./gradlew"
fi

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output)
      [[ $# -ge 2 ]] || { echo "error: --output needs a path" >&2; exit 2; }
      OUTPUT="$2"; shift 2 ;;
    --output=*)
      OUTPUT="${1#--output=}"; shift ;;
    --skip-gradle)
      SKIP_GRADLE=1; shift ;;
    --dependencies-file)
      [[ $# -ge 2 ]] || { echo "error: --dependencies-file needs a path" >&2; exit 2; }
      DEPS_FILE="$2"; shift 2 ;;
    --dependencies-file=*)
      DEPS_FILE="${1#--dependencies-file=}"; shift ;;
    -h|--help)
      sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *)
      echo "error: unknown argument: $1" >&2; exit 2 ;;
  esac
done

err() { printf 'error: %s\n' "$*" >&2; }
log() { printf '%s\n' "$*" >&2; }

# --- Python -----------------------------------------------------------------
PYTHON=""
for candidate in python3 python; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'import sys; assert sys.version_info >= (3, 6)' >/dev/null 2>&1; then
    PYTHON="$candidate"
    break
  fi
done
if [[ -z "$PYTHON" ]]; then
  err "python3 is required to assemble the SBOM JSON"
  exit 2
fi

mkdir -p "$(dirname "$OUTPUT")"

# --- App metadata -----------------------------------------------------------
APP_VERSION="${APP_VERSION:-0.0.0-dev}"
APP_VERSION_CODE="${APP_VERSION_CODE:-0}"
GIT_COMMIT="${GITHUB_SHA:-$(git rev-parse HEAD 2>/dev/null || echo unknown)}"
REPO_URL="https://github.com/smhasnanmonir/openvpn"
BUILD_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# --- Pinned native components (docs/openvpn-core-integration.md) ------------
CORE_DOC="docs/openvpn-core-integration.md"
if [[ ! -f "$CORE_DOC" ]]; then
  err "missing $CORE_DOC (required for pinned native component versions)"
  exit 2
fi

# OpenVPN Core commit: the gitlink recorded in HEAD, not a 40-hex scrape out of
# the doc. A populated submodule must also be checked out at that exact commit;
# either mismatch means the SBOM would name a different core than the one that
# was built, so both are release blockers (non-zero exit), not warnings.
EXPECTED_CORE_COMMIT="10c6a56cb17fd015a2d24953c3c42145bbcdea58"
SUBMODULE_PATH="third_party/openvpn3"
CORE_COMMIT="$(git ls-tree HEAD -- "$SUBMODULE_PATH" 2>/dev/null | awk '$2 == "commit" { print $3; exit }' || true)"
if [[ -z "$CORE_COMMIT" ]]; then
  err "could not read the $SUBMODULE_PATH submodule commit from git (run inside a git checkout that has .gitmodules)"
  exit 1
fi
if [[ "$CORE_COMMIT" != "$EXPECTED_CORE_COMMIT" ]]; then
  err "$SUBMODULE_PATH gitlink is $CORE_COMMIT but the release pin is $EXPECTED_CORE_COMMIT; bump the pin deliberately or restore the submodule"
  exit 1
fi
if ! git -C "$SUBMODULE_PATH" rev-parse --verify HEAD >/dev/null 2>&1; then
  err "$SUBMODULE_PATH is not checked out; run 'git submodule update --init --recursive' (CI checks out submodules for this reason)"
  exit 1
fi
ACTUAL_CORE_COMMIT="$(git -C "$SUBMODULE_PATH" rev-parse HEAD)"
if [[ "$ACTUAL_CORE_COMMIT" != "$EXPECTED_CORE_COMMIT" ]]; then
  err "$SUBMODULE_PATH is checked out at $ACTUAL_CORE_COMMIT but the release pin is $EXPECTED_CORE_COMMIT (stale submodule checkout)"
  exit 1
fi

# Version: "release/3.9" tag row.
CORE_VERSION="$(grep -E '^\| *Tag *\|' "$CORE_DOC" | head -n 1 | sed -E 's/.*`release\/([^`]+)`.*/\1/' || true)"
if [[ -z "$CORE_VERSION" ]]; then
  err "could not parse the OpenVPN Core tag from $CORE_DOC"
  exit 2
fi

# Dependency versions from the "Pinned dependencies" markdown table:
# | mbedTLS | 3.6.2 | `sha` |
dep_version() {
  local name="$1"
  awk -F'|' -v name="$name" '
    $0 ~ "^\\| *" name " *\\|" {
      v = $3
      gsub(/[[:space:]]/, "", v)
      print v
      exit
    }' "$CORE_DOC"
}

MBEDTLS_VERSION="$(dep_version mbedTLS)"
ASIO_RAW_VERSION="$(dep_version asio)"
FMT_VERSION="$(dep_version fmt)"
JSONCPP_VERSION="$(dep_version jsoncpp)"
LZ4_VERSION="$(dep_version lz4)"

# The doc pins asio as "1.24.0 + 7 OpenVPN patches" (whitespace stripped above).
# The SBOM needs the upstream semantic version for vulnerability matching and
# keeps the OpenVPN patch count as the separate openvpn:patches property.
ASIO_VERSION="$(printf '%s' "$ASIO_RAW_VERSION" | grep -oE '^[0-9]+(\.[0-9]+)+' || true)"
ASIO_PATCHES="$(printf '%s' "$ASIO_RAW_VERSION" | sed -E 's/^[0-9]+(\.[0-9]+)+//' | grep -oE '[0-9]+' | head -n 1 || true)"

for pair in "mbedTLS:$MBEDTLS_VERSION" "asio:$ASIO_VERSION" "fmt:$FMT_VERSION" "jsoncpp:$JSONCPP_VERSION" "lz4:$LZ4_VERSION"; do
  if [[ -z "${pair#*:}" ]]; then
    err "could not parse ${pair%%:*} version from $CORE_DOC"
    exit 2
  fi
done

# --- NDK version ------------------------------------------------------------
NDK_VERSION="$(sed -nE 's/^[[:space:]]*ndkVersion[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' app/build.gradle.kts | head -n 1)"
[[ -n "$NDK_VERSION" ]] || NDK_VERSION="27.2.12479018"

# --- Gradle runtime classpath ----------------------------------------------
DEPS_TMP="$(mktemp "${TMPDIR:-/tmp}/sbom-deps.XXXXXX")"
cleanup() { rm -f "$DEPS_TMP"; }
trap cleanup EXIT

if [[ -n "$DEPS_FILE" ]]; then
  cp "$DEPS_FILE" "$DEPS_TMP"
elif [[ "$SKIP_GRADLE" -eq 1 ]]; then
  log "WARNING: --skip-gradle set; SBOM will not include Gradle Maven coordinates"
  : > "$DEPS_TMP"
else
  if [[ ! -x "$GRADLE" && ! -f "$GRADLE" ]]; then
    err "Gradle wrapper not found: $GRADLE (set GRADLE_BIN or pass --skip-gradle for a partial SBOM)"
    exit 2
  fi
  log "Collecting Gradle releaseRuntimeClasspath coordinates..."
  # shellcheck disable=SC2086
  "$GRADLE" --no-daemon -q :app:dependencies --configuration releaseRuntimeClasspath ${GRADLE_ARGS:-} \
    | sed -nE 's/.*[+\\|]--- *([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([A-Za-z0-9_.+-]+).*/\1:\2:\3/p' \
    | sort -u > "$DEPS_TMP"
  if [[ ! -s "$DEPS_TMP" ]]; then
    err "Gradle dependency report produced no Maven coordinates; refusing to write an incomplete SBOM"
    exit 1
  fi
fi
DEP_COUNT="$(wc -l < "$DEPS_TMP" | tr -d ' ')"
log "Collected $DEP_COUNT Gradle runtime coordinates"

# --- Assemble CycloneDX JSON -----------------------------------------------
"$PYTHON" - \
  "$OUTPUT" \
  "$APP_VERSION" \
  "$APP_VERSION_CODE" \
  "$GIT_COMMIT" \
  "$BUILD_TIME" \
  "$REPO_URL" \
  "$CORE_VERSION" \
  "$CORE_COMMIT" \
  "$MBEDTLS_VERSION" \
  "$ASIO_VERSION" \
  "$ASIO_PATCHES" \
  "$FMT_VERSION" \
  "$JSONCPP_VERSION" \
  "$LZ4_VERSION" \
  "$NDK_VERSION" \
  "$DEPS_TMP" <<'PY'
import json
import sys
import uuid

(
    output,
    app_version,
    app_version_code,
    git_commit,
    build_time,
    repo_url,
    core_version,
    core_commit,
    mbedtls_version,
    asio_version,
    asio_patches,
    fmt_version,
    jsoncpp_version,
    lz4_version,
    ndk_version,
    deps_file,
) = sys.argv[1:]

app_ref = "pkg:generic/openvpn-client-android@{}".format(app_version)
components = []
dependencies = []


def add(component):
    components.append(component)
    dependencies.append({"ref": component["bom-ref"]})


# Pinned native components.
add({
    "type": "library",
    "bom-ref": "pkg:github/OpenVPN/openvpn3@{}".format(core_commit),
    "name": "openvpn3",
    "version": core_version,
    "description": "OpenVPN 3 Core (pinned git submodule)",
    "purl": "pkg:github/OpenVPN/openvpn3@{}".format(core_commit),
    "externalReferences": [
        {"type": "vcs", "url": "https://github.com/OpenVPN/openvpn3"},
        {"type": "website", "url": repo_url},
    ],
    "properties": [{"name": "openvpn:commit", "value": core_commit}],
})
for name, version, upstream, desc, patches in (
    ("mbedtls", mbedtls_version, "Mbed-TLS/mbedtls", "mbedTLS (TLS/crypto backend)", ""),
    (
        "asio",
        asio_version,
        "chriskohlhoff/asio",
        "asio standalone (upstream release; OpenVPN patch set applied in the native build)",
        asio_patches,
    ),
    ("fmt", fmt_version, "fmtlib/fmt", "fmt", ""),
    ("jsoncpp", jsoncpp_version, "open-source-parsers/jsoncpp", "jsoncpp", ""),
    ("lz4", lz4_version, "lz4/lz4", "lz4", ""),
):
    # pkg:github/<org>/<repo> purls are mappable to OSV advisories; pkg:generic
    # is not. These are all upstream OSS repositories.
    purl = "pkg:github/{}@{}".format(upstream, version)
    component = {
        "type": "library",
        "bom-ref": purl,
        "name": name,
        "version": version,
        "description": desc,
        "purl": purl,
        "externalReferences": [
            {"type": "vcs", "url": "https://github.com/{}".format(upstream)},
        ],
    }
    if patches:
        component["properties"] = [{"name": "openvpn:patches", "value": patches}]
    add(component)

# Android NDK toolchain.
add({
    "type": "framework",
    "bom-ref": "pkg:generic/android-ndk@{}".format(ndk_version),
    "name": "android-ndk",
    "version": ndk_version,
    "description": "Android NDK toolchain used for native builds",
    "purl": "pkg:generic/android-ndk@{}".format(ndk_version),
})

# Gradle/Maven runtime classpath coordinates.
seen = set()
with open(deps_file, "r", encoding="utf-8") as handle:
    for line in handle:
        line = line.strip()
        if not line or line.count(":") != 2:
            continue
        group, artifact, version = line.split(":")
        if not group or not artifact or not version:
            continue
        purl = "pkg:maven/{}/{}@{}".format(group, artifact, version)
        if purl in seen:
            continue
        seen.add(purl)
        add({
            "type": "library",
            "bom-ref": purl,
            "group": group,
            "name": artifact,
            "version": version,
            "purl": purl,
        })

bom = {
    "$schema": "http://cyclonedx.org/schema/bom-1.5.schema.json",
    "bomFormat": "CycloneDX",
    "specVersion": "1.5",
    "serialNumber": "urn:uuid:{}".format(uuid.uuid4()),
    "version": 1,
    "metadata": {
        "timestamp": build_time,
        "tools": [{"vendor": "openvpn-client-android", "name": "generate-sbom.sh", "version": "1.0"}],
        "component": {
            "type": "application",
            "bom-ref": app_ref,
            "name": "openvpn-client-android",
            "version": app_version,
            "purl": app_ref,
            "properties": [
                {"name": "android:versionCode", "value": str(app_version_code)},
                {"name": "git:commit", "value": git_commit},
            ],
            "externalReferences": [{"type": "vcs", "url": repo_url}],
        },
    },
    "components": components,
    "dependencies": [{"ref": app_ref, "dependsOn": [c["bom-ref"] for c in components]}] + dependencies,
}

with open(output, "w", encoding="utf-8") as handle:
    json.dump(bom, handle, indent=2, sort_keys=False)
    handle.write("\n")

print("app:", app_version, "components:", len(components))
PY

# Validate the generated file.
"$PYTHON" -c 'import json,sys; json.load(open(sys.argv[1], encoding="utf-8"))' "$OUTPUT"
log "SBOM written to $OUTPUT ($(wc -c < "$OUTPUT" | tr -d ' ') bytes)"
printf '%s\n' "$OUTPUT"
