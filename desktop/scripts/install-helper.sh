#!/usr/bin/env bash
# Install the OpenVPN Desktop privileged helper.
#
# The GUI never runs openvpn itself when it is unprivileged. It launches this
# helper through pkexec (polkit), passwordless sudo, or a setuid bit. The helper
# re-sanitizes the profile, strips every script/plugin/management directive, and
# builds OpenVPN's argv itself, so a tampered profile cannot run a command as
# root.
#
# Usage:
#   sudo ./scripts/install-helper.sh             # auto: polkit, else sudo
#   sudo ./scripts/install-helper.sh polkit
#   sudo ./scripts/install-helper.sh sudo
#   sudo ./scripts/install-helper.sh setuid      # discouraged; see SECURITY.md
#
# Re-run it after rebuilding the helper.
set -euo pipefail

if [[ ${EUID:-$(id -u)} -ne 0 ]]; then
  echo "install-helper.sh: run with sudo or as root" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PREFIX="${PREFIX:-/usr/local}"
LIBEXEC="${PREFIX}/libexec/openvpn-desktop"
HELPER="${LIBEXEC}/openvpn-desktop-helper"
ACTION_ID="com.smhasnanmonir.openvpndesktop.helper"
POLKIT_DIR="/usr/share/polkit-1/actions"
POLKIT_FILE="${POLKIT_DIR}/${ACTION_ID}.policy"
SUDOERS_FILE="/etc/sudoers.d/openvpn-desktop"
MODE="${1:-auto}"

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "install-helper.sh: $1 is required" >&2
    exit 1
  }
}
need go
need openvpn

# Never install a helper that loads a user-writable OpenVPN binary.
OPENVPN="$(command -v openvpn)"
OPENVPN_MODE="$(stat -c '%a' "$OPENVPN")"
if [[ "$(stat -c '%u' "$OPENVPN")" != "0" ]] || (( 8#${OPENVPN_MODE} & 8#022 )); then
  echo "install-helper.sh: $OPENVPN must be root-owned and not group/world writable" >&2
  exit 1
fi

case "$MODE" in
  auto)
    if command -v pkexec >/dev/null 2>&1; then
      MODE=polkit
    else
      MODE=sudo
    fi
    ;;
  polkit|sudo|setuid) ;;
  *)
    echo "usage: sudo $0 [auto|polkit|sudo|setuid]" >&2
    exit 2
    ;;
esac

echo "==> building the helper"
cd "$ROOT"
export PATH="${PATH}:/usr/local/go/bin"
export GOTOOLCHAIN="${GOTOOLCHAIN:-local}"
mkdir -p "$LIBEXEC"
GOFLAGS="${GOFLAGS:-}" go build -trimpath -ldflags "-s -w" -o "${HELPER}.tmp" ./cmd/openvpn-desktop-helper
install -m 0755 "${HELPER}.tmp" "$HELPER"
rm -f "${HELPER}.tmp"

case "$MODE" in
  polkit)
    need pkexec
    echo "==> installing the polkit action ${ACTION_ID}"
    mkdir -p "$POLKIT_DIR"
    cat > "$POLKIT_FILE" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE policyconfig PUBLIC
 "-//freedesktop//DTD PolicyKit Policy Configuration 1.0//EN"
 "http://www.freedesktop.org/standards/PolicyKit/1/policyconfig.dtd">
<policyconfig>
  <vendor>OpenVPN Desktop</vendor>
  <action id="${ACTION_ID}">
    <description>Connect the OpenVPN Desktop VPN</description>
    <message>Authentication is required to run the OpenVPN Desktop privileged helper.</message>
    <defaults>
      <allow_any>auth_admin</allow_any>
      <allow_inactive>auth_admin</allow_inactive>
      <allow_active>auth_admin_keep</allow_active>
    </defaults>
    <annotate key="org.freedesktop.policykit.exec.path">${HELPER}</annotate>
    <annotate key="org.freedesktop.policykit.exec.allow_gui">true</annotate>
  </action>
</policyconfig>
EOF
    chmod 0644 "$POLKIT_FILE"
    rm -f "$SUDOERS_FILE"
    echo "    pkexec will prompt for authentication when connecting."
    ;;
  sudo)
    need visudo
    echo "==> installing the sudoers rule ${SUDOERS_FILE}"
    cat > "$SUDOERS_FILE" <<EOF
# OpenVPN Desktop privileged helper. Only this root-owned binary may run as root.
# The helper sanitizes the profile and builds OpenVPN's argv itself.
ALL ALL=(root) NOPASSWD: ${HELPER}
EOF
    chmod 0440 "$SUDOERS_FILE"
    visudo -cf "$SUDOERS_FILE" >/dev/null
    rm -f "$POLKIT_FILE"
    echo "    sudo -n will launch the helper without a password prompt."
    echo "    This grants every local user the right to run the helper; see SECURITY.md."
    ;;
  setuid)
    echo "==> setting the setuid bit on ${HELPER}"
    chmod 4755 "$HELPER"
    rm -f "$POLKIT_FILE" "$SUDOERS_FILE"
    echo "    WARNING: setuid is the broadest option. Any local user can invoke the helper."
    echo "    The helper sanitizes the profile and controls OpenVPN's argv, but polkit or sudo is safer."
    ;;
esac

case "$MODE" in
  polkit) echo "polkit" > "${LIBEXEC}/privilege-mode" ;;
  sudo)   echo "sudo" > "${LIBEXEC}/privilege-mode" ;;
  setuid) echo "setuid" > "${LIBEXEC}/privilege-mode" ;;
esac
chmod 0444 "${LIBEXEC}/privilege-mode"

echo "==> verifying the helper"
"$HELPER" doctor
echo
echo "Installed: $HELPER (mode $MODE)"
echo "Restart OpenVPN Desktop. The Settings tab shows whether the helper is ready."
