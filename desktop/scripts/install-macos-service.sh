#!/usr/bin/env bash
# Install (or remove) the OpenVPN Desktop privileged helper daemon on macOS.
#
# The GUI's "Install privileged helper" button runs the same helper command
# through osascript; this script is the terminal/package fallback.
#
# Requirements: macOS, OpenVPN 2.7+ (Homebrew or official), and an
# administrator account. A prebuilt helper may be passed with --helper;
# otherwise the script builds it (Go 1.27+ required).
set -euo pipefail

LABEL="com.smhasnanmonir.openvpndesktop.helper"
HELPER="/Library/PrivilegedHelperTools/${LABEL}"
USER_NAME="${SUDO_USER:-${USER}}"
USER_HOME="$(dscl . -read "/Users/${USER_NAME}" NFSHomeDirectory 2>/dev/null | awk '{print $2}')"
USER_CONFIG="${USER_HOME}/Library/Application Support/openvpn-desktop/service.json"

if [[ ${EUID:-$(id -u)} -ne 0 ]]; then
  echo "install-macos-service.sh: run with sudo" >&2
  exit 1
fi

if [[ ${1:-} == "--uninstall" ]]; then
  if [[ -x "${HELPER}" ]]; then
    "${HELPER}" uninstall-service --purge --user-config "${USER_CONFIG}" || true
  fi
  launchctl bootout "system/${LABEL}" 2>/dev/null || true
  rm -f "/Library/LaunchDaemons/${LABEL}.plist" "${HELPER}" \
    "/Library/Application Support/OpenVPN Desktop/service.json" "${USER_CONFIG}"
  rmdir "/Library/Application Support/OpenVPN Desktop" 2>/dev/null || true
  echo "Removed ${LABEL} and its configuration."
  exit 0
fi

if [[ -z "${USER_HOME}" || ! -d "${USER_HOME}" ]]; then
  echo "install-macos-service.sh: cannot find the home directory of ${USER_NAME}" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HELPER_SOURCE=""
ARGS=("$@")
for ((i = 0; i < ${#ARGS[@]}; i++)); do
  if [[ "${ARGS[i]}" == "--helper" && $((i + 1)) -lt ${#ARGS[@]} ]]; then
    HELPER_SOURCE="${ARGS[i + 1]}"
  fi
done

if [[ -z "${HELPER_SOURCE}" ]]; then
  echo "==> building the helper"
  export PATH="${PATH}:/usr/local/go/bin:/opt/homebrew/bin"
  export GOTOOLCHAIN="${GOTOOLCHAIN:-local}"
  mkdir -p "${ROOT}/bin"
  VERSION="$(cd "${ROOT}" && python3 -c 'import json; print(json.load(open("mygo.json"))["version"])' 2>/dev/null || echo 1.0.0)"
  (
    cd "${ROOT}"
    go build -trimpath -ldflags "-s -w -X github.com/smhasnanmonir/openvpn/desktop/internal/helper.Version=${VERSION}" \
      -o "bin/openvpn-desktop-helper" ./cmd/openvpn-desktop-helper
  )
  HELPER_SOURCE="${ROOT}/bin/openvpn-desktop-helper"
fi
if [[ ! -x "${HELPER_SOURCE}" ]]; then
  echo "install-macos-service.sh: helper not found at ${HELPER_SOURCE}" >&2
  exit 1
fi

echo "==> installing the helper (this also makes a root-owned OpenVPN copy)"
"${HELPER_SOURCE}" install-service \
  --helper "${HELPER_SOURCE}" \
  --user-config "${USER_CONFIG}" \
  --user "${USER_NAME}"

echo
echo "Installed ${LABEL}."
echo "  helper: ${HELPER}"
echo "  config: /Library/Application Support/OpenVPN Desktop/service.json (root only)"
echo "  token:  ${USER_CONFIG} (${USER_NAME} only)"
echo "Restart OpenVPN Desktop, then connect. Remove with:"
echo "  sudo $0 --uninstall"
