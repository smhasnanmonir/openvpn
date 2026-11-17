#!/usr/bin/env bash
# Verify Linux per-app split tunneling (cgroup v2 + nftables + policy routing)
# in an isolated network namespace.
#
# Requirements: Linux, root (CAP_NET_ADMIN), cgroup v2, ip, nft, Go 1.27+.
set -euo pipefail

if [[ ${EUID:-$(id -u)} -ne 0 ]]; then
  echo "test-per-app-split.sh: run as root" >&2
  exit 1
fi
if [[ ! -e /sys/fs/cgroup/cgroup.controllers ]]; then
  echo "test-per-app-split.sh: cgroup v2 is not available" >&2
  exit 1
fi
command -v nft >/dev/null || { echo "nft is required" >&2; exit 1; }

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d /tmp/openvpn-desktop-split.XXXXXX)"
NS="openvpn-desktop-split"
VETH_H="ovpnd-split-h"
VETH_N="ovpnd-split-n"

cleanup() {
  set +e
  ip netns pids "${NS}" 2>/dev/null | xargs -r kill 2>/dev/null
  ip netns del "${NS}" 2>/dev/null
  ip link del "${VETH_H}" 2>/dev/null
  umount "${TMP}/cgroup" 2>/dev/null
  rm -rf "${TMP}"
}
trap cleanup EXIT

echo "==> creating ${NS} with a physical veth default and a dummy VPN device"
ip netns add "${NS}"
ip link add "${VETH_H}" type veth peer name "${VETH_N}"
ip link set "${VETH_N}" netns "${NS}"
ip addr add 10.200.0.1/24 dev "${VETH_H}"
ip link set "${VETH_H}" up
ip netns exec "${NS}" ip link set lo up
ip netns exec "${NS}" ip addr add 10.200.0.2/24 dev "${VETH_N}"
ip netns exec "${NS}" ip link set "${VETH_N}" up
ip netns exec "${NS}" ip link add tun-test type dummy
ip netns exec "${NS}" ip addr add 10.99.0.2/24 dev tun-test
ip netns exec "${NS}" ip link set tun-test up
ip netns exec "${NS}" ip route add default via 10.200.0.1 dev "${VETH_N}"

mkdir -p "${TMP}/bin"
cp /bin/sleep "${TMP}/bin/sleep"
chmod 0755 "${TMP}/bin/sleep"

# ip netns exec remounts /sys for the new net namespace, hiding the cgroup
# filesystem. Bind the host cgroup tree at a normal path and point the test at
# it; nft/ip rules still apply inside the network namespace.
mkdir -p "${TMP}/cgroup"
mount --bind /sys/fs/cgroup "${TMP}/cgroup"

export PATH="${PATH}:/usr/local/go/bin"
export GOTOOLCHAIN=local
cd "${ROOT}"
export GOPATH="${GOPATH:-$(go env GOPATH)}"
export GOMODCACHE="${GOMODCACHE:-$(go env GOMODCACHE)}"
export GOCACHE="${GOCACHE:-$(go env GOCACHE)}"

echo "==> running the per-app integration test inside ${NS}"
ip netns exec "${NS}" env \
  HOME="${HOME}" PATH="${PATH}" GOPATH="${GOPATH}" GOMODCACHE="${GOMODCACHE}" GOCACHE="${GOCACHE}" \
  OPENVPN_DESKTOP_INTEGRATION=1 \
  OPENVPN_DESKTOP_SPLIT_VETH="${VETH_N}" \
  OPENVPN_DESKTOP_SPLIT_GATEWAY="10.200.0.1" \
  OPENVPN_DESKTOP_SPLIT_SLEEPER="${TMP}/bin/sleep" \
  OPENVPN_DESKTOP_CGROUP_ROOT="${TMP}/cgroup" \
  go test -tags integration ./internal/helper -run TestPerAppSplit -v -count=1 -timeout 120s

echo "==> per-app split tunneling verification passed"
