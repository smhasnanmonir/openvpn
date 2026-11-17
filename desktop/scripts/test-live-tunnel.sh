#!/usr/bin/env bash
# End-to-end verification of the desktop client against a real OpenVPN server.
#
# It creates a throwaway CA/server/client certificate set, runs the server on the
# host side of a veth pair, puts the client (and this test) in a dedicated
# network namespace, and drives the real service/helper/management path.
#
# Requirements: Linux, root (CAP_NET_ADMIN + TUN), openvpn, openssl, ip,
# go 1.27+. Nothing is installed and no host route is changed; the namespace is
# removed on exit.
set -euo pipefail

if [[ ${EUID:-$(id -u)} -ne 0 ]]; then
  echo "test-live-tunnel.sh: run as root (it creates a TUN device and a netns)" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d /tmp/openvpn-desktop-live.XXXXXX)"
SRV_NS="openvpn-desktop-srv"
CLI_NS="openvpn-desktop-cli"
VETH_SRV="ovpnd-srv"
VETH_CLI="ovpnd-cli"
SERVER_PID=""

cleanup() {
  local status=$?
  set +e
  if [[ ${status} -ne 0 && -f "${TMP}/pki/server.log" ]]; then
    echo "==> server log (last 80 lines)" >&2
    tail -80 "${TMP}/pki/server.log" >&2
  fi
  if [[ -n "${SERVER_PID}" ]]; then
    kill "${SERVER_PID}" 2>/dev/null
    wait "${SERVER_PID}" 2>/dev/null
  fi
  for ns in "${SRV_NS}" "${CLI_NS}"; do
    ip netns pids "${ns}" 2>/dev/null | xargs -r kill 2>/dev/null
    ip netns del "${ns}" 2>/dev/null
  done
  ip link del "${VETH_SRV}" 2>/dev/null
  ip link del "${VETH_CLI}" 2>/dev/null
  if [[ "${KEEP_TMP:-0}" != "1" ]]; then
    rm -rf "${TMP}"
  else
    echo "kept ${TMP}" >&2
  fi
  exit ${status}
}
trap cleanup EXIT

echo "==> generating throwaway PKI in ${TMP}/pki"
mkdir -p "${TMP}/pki"
cd "${TMP}/pki"
openssl genrsa -out ca.key 2048 >/dev/null 2>&1
openssl req -x509 -new -key ca.key -sha256 -days 2 -subj "/CN=OpenVPN Desktop Live Test CA" -out ca.crt >/dev/null 2>&1

openssl genrsa -out server.key 2048 >/dev/null 2>&1
openssl req -new -key server.key -subj "/CN=server" -out server.csr >/dev/null 2>&1
cat > server.ext <<'EOF'
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:server,IP:127.0.0.1,IP:192.168.123.1
EOF
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out server.crt -days 2 -sha256 -extfile server.ext >/dev/null 2>&1

openssl genrsa -out client.key 2048 >/dev/null 2>&1
openssl req -new -key client.key -subj "/CN=client" -out client.csr >/dev/null 2>&1
cat > client.ext <<'EOF'
basicConstraints=CA:FALSE
keyUsage=digitalSignature
extendedKeyUsage=clientAuth
EOF
openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out client.crt -days 2 -sha256 -extfile client.ext >/dev/null 2>&1

openssl dhparam -dsaparam -out dh.pem 2048 >/dev/null 2>&1
openvpn --genkey secret ta.key >/dev/null 2>&1

cat > server.conf <<'EOF'
local 192.168.123.1
port 1194
proto udp
dev tun
topology subnet
server 10.8.0.0 255.255.255.0
dh dh.pem
ca ca.crt
cert server.crt
key server.key
tls-crypt ta.key
cipher AES-256-GCM
data-ciphers AES-256-GCM
auth SHA256
keepalive 10 60
persist-key
persist-tun
verb 4
status status.log 5
log-append server.log
push "route 10.8.0.0 255.255.255.0"
push "dhcp-option DNS 10.8.0.1"
EOF

{
  echo "client"
  echo "dev tun"
  echo "proto udp"
  echo "remote 192.168.123.1 1194"
  echo "remote-cert-tls server"
  echo "cipher AES-256-GCM"
  echo "data-ciphers AES-256-GCM"
  echo "auth SHA256"
  echo "verb 4"
  echo "<ca>"; cat ca.crt; echo "</ca>"
  echo "<cert>"; cat client.crt; echo "</cert>"
  echo "<key>"; cat client.key; echo "</key>"
  echo "<tls-crypt>"; cat ta.key; echo "</tls-crypt>"
} > client.ovpn

echo "==> creating server and client network namespaces"
ip netns add "${SRV_NS}"
ip netns add "${CLI_NS}"
ip link add "${VETH_SRV}" type veth peer name "${VETH_CLI}"
ip link set "${VETH_SRV}" netns "${SRV_NS}"
ip link set "${VETH_CLI}" netns "${CLI_NS}"
ip netns exec "${SRV_NS}" ip link set lo up
ip netns exec "${SRV_NS}" ip addr add 192.168.123.1/24 dev "${VETH_SRV}"
ip netns exec "${SRV_NS}" ip link set "${VETH_SRV}" up
ip netns exec "${CLI_NS}" ip link set lo up
ip netns exec "${CLI_NS}" ip addr add 192.168.123.2/24 dev "${VETH_CLI}"
ip netns exec "${CLI_NS}" ip link set "${VETH_CLI}" up

echo "==> starting OpenVPN server in ${SRV_NS}"
ip netns exec "${SRV_NS}" openvpn --config server.conf --daemon --writepid "${TMP}/server.pid" --cd "${TMP}/pki"
SERVER_PID="$(cat "${TMP}/server.pid")"
for _ in $(seq 1 30); do
  if grep -q "Initialization Sequence Completed" server.log 2>/dev/null; then
    break
  fi
  sleep 0.5
done
if ! grep -q "Initialization Sequence Completed" server.log 2>/dev/null; then
  echo "server did not start:" >&2
  cat server.log >&2
  exit 1
fi
echo "    server log: $(grep -m1 'UDPv4 link local' server.log || true)"

echo "==> building the privileged helper"
cd "${ROOT}"
export PATH="${PATH}:/usr/local/go/bin"
export GOTOOLCHAIN=local
mkdir -p bin
go build -trimpath -o bin/openvpn-desktop-helper ./cmd/openvpn-desktop-helper

echo "==> checking the fail-closed DNS refusal"
if env -i OPENVPN_DESKTOP_REQUIRE_DNS=1 dev=test0 \
    "${ROOT}/bin/openvpn-desktop-helper" dns-up >/tmp/openvpn-desktop-dns.out 2>/tmp/openvpn-desktop-dns.err; then
  echo "dns-up unexpectedly accepted a connection with no resolver" >&2
  exit 1
fi
if ! grep -q "Require tunnel DNS" /tmp/openvpn-desktop-dns.err; then
  echo "dns-up refusal did not explain the policy:" >&2
  cat /tmp/openvpn-desktop-dns.err >&2
  exit 1
fi
echo "    refused as expected"

echo "==> running the integration test inside ${CLI_NS}"
export GOPATH="${GOPATH:-$(go env GOPATH)}"
export GOMODCACHE="${GOMODCACHE:-$(go env GOMODCACHE)}"
export GOCACHE="${GOCACHE:-$(go env GOCACHE)}"
ip netns exec "${CLI_NS}" env \
  HOME="${HOME}" PATH="${PATH}" GOPATH="${GOPATH}" GOMODCACHE="${GOMODCACHE}" GOCACHE="${GOCACHE}" \
  OPENVPN_DESKTOP_INTEGRATION=1 \
  OPENVPN_DESKTOP_HELPER="${ROOT}/bin/openvpn-desktop-helper" \
  OPENVPN_DESKTOP_LIVE_PROFILE="${TMP}/pki/client.ovpn" \
  go test -tags integration ./internal/app -run TestLiveTunnel -v -count=1 -timeout 180s

echo "==> live tunnel verification passed"
