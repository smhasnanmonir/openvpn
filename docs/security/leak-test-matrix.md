# VPN leak-test matrix

> **Status: test plan and harness description. Most rows have not been executed
> on physical devices.** This document defines how the app's leak behaviour is
> verified, exactly what "pass" means, and what is still unverified. Rows may be
> marked *automated* (covered by JVM/CI checks in this repository), *emulator*
> (previously/ feasibly exercised on an emulator), or *device* (requires a
> physical device and a controlled network). Do not claim a row passed unless it
> was run and recorded here or in `docs/testing.md`.

## 1. Why this exists

A VPN client must **fail securely**: when protection is lost, traffic must not
silently leave the tunnel. Passing a functional connect test is not enough. This
matrix covers IPv4/IPv6/DNS leaks, kill-switch behaviour, network transitions,
captive portals, process death/reboot, other VPNs, split tunneling, MTU/path
issues, and the Android 15+ 16 KB page-size platform requirement (issue #21).

## 2. Definitions and pass criteria

| Term | Meaning |
|---|---|
| **Leak** | Any packet that should be inside the tunnel (or blocked) observed leaving the device outside the tunnel to the internet. |
| **Protected window** | The UI/notification states Connected/Protected. A leak during this window is a failure. |
| **Unprotected window** | Explicitly documented states where traffic is not protected: no connection, reconnecting/paused, paused for portal, split-tunnel bypass apps. The UI must say so; traffic outside the tunnel is expected and is not counted as a leak *only if the state is honest*. |
| **Pass** | Expected behaviour is observed in the capture **and** the UI/notification state is honest. |
| **Fail** | Unexpected outside-tunnel traffic or a false "Protected" claim. Record the packet trace and UI state. |

**Method note:** capture at the network boundary, not only on the device. The
device may have a VPN TUN that hides its own egress; the authoritative check is
on the Wi-Fi AP/router or on the controlled server host watching the public
interface, plus a second monitoring host.

## 3. Test environment

Required for full execution:

- A physical Android device (ideally two: one Wi-Fi, one cellular/tablet) with
  an unlocked bootloader is **not** required; a normal device with USB
  debugging is.
- A controlled OpenVPN server (OpenVPN 2.6+/2.7, easy-rsa CA, `tls-crypt` or
  `tls-auth`, `data-ciphers AES-256-GCM:CHACHA20-POLY1305: AES-128-GCM`,
  `allow-compression no`, no redirects to external hosts) whose server-side
  capture is possible.
- A **controlled DNS resolver** whose query log is visible (or `tcpdump` on the
  server's DNS port), so pushed vs underlying DNS can be distinguished.
- A monitoring host/laptop on the same network running `tcpdump`/Wireshark on
  the AP uplink, **and** a second public-egress check (a host outside the VPN
  that records source IP, e.g. a small web server logging requests).
- A device with Android 15+ (or an emulator with 16 KB page-size support) for
  the 16 KB row.
- `adb` with USB debugging; for cellular tests, a SIM with data and the
  ability to toggle mobile data/Wi-Fi from the device.

Record for every run:

```
Date/time (Asia/Dhaka):
Device model / Android build / page size (getconf PAGE_SIZE):
App version + ABI + commit:
OpenVPN Core revision (10c6a56 for release/3.9):
Server software/version + profile (synthetic CA, test user):
DNS resolver + logging method:
Capture points (AP/router, server host):
```

## 4. adb and host commands

```bash
# Device/network state
adb shell getprop ro.build.version.release
adb shell getconf PAGE_SIZE
adb shell dumpsys connectivity | sed -n '/NetworkAgentInfo/,+12p'
adb shell ip -4 route show table all
adb shell ip -6 route show table all
adb shell ip rule
adb shell dumpsys activity services com.smhasnan.openvpn
adb shell dumpsys notification --noredact | grep -i openvpn

# Always-on / lockdown settings (read-only; best-effort, may be unreadable)
adb shell settings get secure always_on_vpn_app
adb shell settings get secure always_on_vpn_lockdown

# Lifecycle
adb shell am kill com.smhasnan.openvpn          # process death (with Always-on)
adb shell am force-stop com.smhasnan.openvpn   # force-stop (documented: no restart)
adb reboot

# UI state / logs while running
adb logcat -d | grep -iE 'openvpn|vpn|dns|tun' | tail -n 200
```

Host-side capture examples (adapt interfaces):

```bash
# All traffic of the test device on the AP uplink, excluding the VPN transport
sudo tcpdump -ni <uplink> -w device.pcap host <device-ip> or host <device-public-ip>

# DNS only
sudo tcpdump -ni <uplink> -vvv 'udp port 53 or tcp port 53'

# Server host: confirm the tunnel transport and egress
sudo tcpdump -ni <server-if> -w server.pcap 'udp port 1194 or tcp port 443'
```

Check the public egress from a monitor host (controlled outside server) during
each protected state; it must show the VPN server's egress IP, never the
device's ISP IP.

## 5. Matrix

Status legend: ✅ verified, 🟡 partially verified/needs repeat, ⬜ not run,
🤖 automated (JVM/CI subset), n/a.

### 5.1 Baseline and full tunnel

| ID | Scenario | Method | Expected result | Status |
|---|---|---|---|---|
| L-01 | No VPN connected (baseline) | Capture + egress check | Traffic goes directly; UI says not protected. Proves the capture can see leaks. | ⬜ |
| L-02 | Full-tunnel IPv4 connect | Connect with controlled profile; start a large download + `ping` public host; capture on AP uplink and server | Device sends no internet traffic outside the tunnel; egress IP = server egress; UI Connected/Protected. | ⬜ |
| L-03 | Connected then server-side tunnel drop | Stop the server process; capture device egress for 5 min | Client reports failure/reconnect honestly; no traffic silently leaves while UI still says Protected. Without lockdown, underlying traffic may flow only after the UI has left Protected. | ⬜ |
| L-04 | Disconnect via notification/tile | Disconnect, capture | No VPN transport, TUN removed, notification gone, egress returns direct; UI disconnected. | ⬜ |

### 5.2 DNS

| ID | Scenario | Method | Expected result | Status |
|---|---|---|---|---|
| L-05 | Pushed DNS used | Profile server pushes controlled DNS; resolve a unique test name; check DNS resolver log + capture | Queries arrive at the controlled resolver through the tunnel; no query for that name at the ISP/underlying resolver. | ⬜ |
| L-06 | No pushed DNS, "Require tunnel DNS" ON (default) | Server profile with no `push "dhcp-option DNS"`; connect | Connection is refused with an explicit error; no TUN; no DNS queries leave; UI explains refusal. **Fail-closed behaviour.** | 🤖 policy unit-tested; device ⬜ |
| L-07 | No pushed DNS + fallback DNS configured | Same profile, fallback = controlled IPv4 resolver | Connection succeeds; DNS queries go to the fallback resolver **through the tunnel**; diagnostics say fallback is used; capture shows tunnel transport only. | ⬜ |
| L-08 | No pushed DNS + setting OFF (escape hatch) | Same profile, setting off | App connects and logs the warning; DNS may use underlying resolver. UI/docs must describe this as a leak risk. Verify the warning is visible. | ⬜ |
| L-09 | IPv6-only pushed DNS | Profile pushes an IPv6 resolver; IPv6-capable device | Resolver is passed to Android and used; no IPv6 DNS leak; fail-closed if no usable resolver for a family. | ⬜ |
| L-10 | Search domains / split DNS | Profile with search domains and DNS | Android applies search domains; no plaintext fallback for unmatched names (documented limitations). | ⬜ |

### 5.3 IPv4/IPv6 family leaks

| ID | Scenario | Method | Expected result | Status |
|---|---|---|---|---|
| L-11 | IPv4-only profile, dual-stack device | Check `ip -6 route`; browse an IPv6-only test host; capture IPv6 traffic | Android blocks IPv6 family by default (no v6 address/route/DNS); no IPv6 leaves the device; UI/log notes family blocking. | ⬜ |
| L-12 | "Block untunneled address families" OFF | Toggle setting; capture IPv6 | IPv6 fallback is documented and visible; no false claim of blocking; warning logged. | ⬜ |
| L-13 | IPv6-enabled tunnel | Profile/server with IPv6; capture | IPv6 traffic and DNS go through the tunnel; egress v6 = server. | ⬜ |
| L-14 | Dual-stack with one family pushed only | Capture both families | Missing family is blocked (not leaked) while fail-closed DNS is on. | ⬜ |

### 5.4 Kill switch / Always-on / lockdown

| ID | Scenario | Method | Expected result | Status |
|---|---|---|---|---|
| L-15 | Always-on + lockdown, tunnel killed | Enable system Always-on + "Block connections without VPN"; kill server; try to reach internet; capture | All traffic blocked by Android lockdown; UI shows reconnecting/error, **never** a false "protected without lockdown" claim. | ⬜ |
| L-16 | Always-on without lockdown | Same without lockdown; kill server | Android restores underlying network when TUN closes; UI honest about not being protected; no "guaranteed kill switch" claim. | ⬜ |
| L-17 | Reconnecting/paused window | Force post-connect timeout (network black hole) until pause; capture | Traffic may use underlying network only while UI shows Reconnecting; bounded retries end with explicit error. This is the documented exposure (ADR-007). Fail only if UI claims Protected. | ⬜ |
| L-18 | Settings readiness card | Install, open Settings | Card reports best-effort Always-on/lockdown state (UNKNOWN when unreadable), links to system VPN settings, explains that the app cannot guarantee a kill switch without lockdown. | ⬜ |

### 5.5 Network transitions and lifecycle

| ID | Scenario | Method | Expected result | Status |
|---|---|---|---|---|
| L-19 | Wi-Fi ↔ cellular while connected | Toggle Wi-Fi off/on during traffic; capture both interfaces | One reconnect path; no long plaintext leak while UI claims Protected; transport socket protected; no stale fd. | ⬜ |
| L-20 | Airplane mode on → off | Toggle airplane mode; capture | Session ends or recovers per policy; no traffic while radio off; on recovery either reconnects or reports failure honestly. | ⬜ |
| L-21 | Process death, Always-on enabled | `adb shell am kill com.smhasnan.openvpn` (do **not** force-stop) | System restarts service; desired-state restore creates a **fresh** native session from stored profile/credentials; no stale pointer; protection resumes; UI converges. | ⬜ |
| L-22 | Process death, Always-on disabled | `adb shell am kill ...` | No restart; UI on next open shows disconnected; no stale notification/tile. | ⬜ |
| L-23 | Reboot with Always-on | Reboot device; unlock; capture | Pre-unlock: cannot restore (Keystore locked) — documented; after unlock: system starts the service and restores; no duplicate sessions/notifications. | ⬜ |
| L-24 | Force-stop | App info → Force stop; wait; capture | No background restart; state honest when reopened. | ⬜ |
| L-25 | Another VPN app takes over | Install/connect a second VPN; check app | This app's service is stopped/revoked cleanly; UI does not claim protection; no attempt to steal the VPN back. | ⬜ |
| L-26 | VPN permission revoked | Android settings → revoke VPN consent; try connect | Explicit error; no tunnel; state honest. | ⬜ |
| L-27 | Bounded retry exhaustion | Unreachable server after connect; wait through budget | After 6 attempts: explicit "traffic is no longer protected" error, no endless Reconnecting. | 🤖 policy unit-tested; device ⬜ |

### 5.6 Captive portal

| ID | Scenario | Method | Expected result | Status |
|---|---|---|---|---|
| L-28 | Captive portal detected | Join a portal network (or controlled portal); open app | Banner offers "Pause VPN to sign in"; no automatic pause/disable. | ⬜ |
| L-29 | Pause for sign-in | Tap pause; capture | Tunnel down; UI says traffic is NOT protected; portal sign-in succeeds; no false Protected state. | ⬜ |
| L-30 | Auto-resume after validation | Complete sign-in; wait | Network validates and the app reconnects the same profile with a fresh session; protection resumes; if it fails, honest error and manual retry. | ⬜ |
| L-31 | Manual sign-in URL | Use the offered `http://connectivitycheck.gstatic.com/generate_204` | Opens and completes the portal flow; documented as the fallback URL. | ⬜ |

### 5.7 Split tunneling

| ID | Scenario | Method | Expected result | Status |
|---|---|---|---|---|
| L-32 | Exclude-selected mode | Exclude a browser app; capture app traffic and VPN traffic | Excluded app traffic uses the underlying network (expected); warning "N apps bypass the tunnel" visible on Home/Settings; tunneled apps still use the VPN. | ⬜ |
| L-33 | Include-selected mode | Include only one test app; capture | Only the selected app uses the tunnel; all other traffic bypasses; warning visible; no claim that bypassing apps are protected. | ⬜ |
| L-34 | Include mode with zero apps | Clear selection; connect | Connection refused with a clear validation error; no empty tunnel established. | 🤖 policy unit-tested; device ⬜ |
| L-35 | Uninstalled selected app | Select an app, uninstall it, connect/reopen picker | Stale package filtered out safely; warning count reflects effective set; no crash. | 🤖 policy unit-tested; device ⬜ |
| L-36 | Change policy while connected | Change selection; observe | Change takes effect on next connection; UI offers reconnect; live tunnel unchanged. | ⬜ |

### 5.8 MTU / path issues and traffic integrity

| ID | Scenario | Method | Expected result | Status |
|---|---|---|---|---|
| L-37 | MTU Auto vs fixed 1400 on a 1400-byte path | Emulate smaller path MTU; large download; capture | Auto reproduces black hole/stall (documented), fixed 1400 succeeds; chosen MTU shown in connection details; no reconnect loop. | ⬜ |
| L-38 | Fixed MTU overrides server push | Server pushes 1500; choose 1400 | Connect config contains `pull-filter ignore tun-mtu` + `tun-mtu 1400`; TUN MTU is 1400. | 🤖 config transform unit-tested; device ⬜ |
| L-39 | EMSGSIZE diagnostics | Trigger oversized UDP; check logs | One warning per connection suggesting lower MTU; no auto-reconnect loop. | ⬜ |

### 5.9 Compression / cipher policy

| ID | Scenario | Method | Expected result | Status |
|---|---|---|---|---|
| L-40 | Compatibility compression (default) | Provider-like server pushes LZO-ASYM | Session stays connected; symmetric compression still refused; diagnostics state the policy. | ⬜ |
| L-41 | Strict compression | Enable Strict; server pushes compression | Connection fails with an explicit compression/server-push error (no silent degradation). | 🤖 patch logic build-time; device ⬜ |
| L-42 | AEAD-only with AEAD server | Enable AEAD-only; connect to AES-GCM/ChaCha20 server | Connects; data channel is AEAD; diagnostics state the policy. | ⬜ |
| L-43 | AEAD-only with CBC-only profile/server | Enable AEAD-only; profile pins BF-CBC/AES-CBC | Connection fails with a clear cipher-policy message; no non-AEAD cipher negotiated. | 🤖 policy unit-tested; device ⬜ |

### 5.10 Platform and release gates

| ID | Scenario | Method | Expected result | Status |
|---|---|---|---|---|
| L-44 | 16 KB page-size device/emulator | Install on Android 15+ 16 KB device; connect; large transfer | Installs/launches/connects; every packaged `.so` `LOAD` `p_align >= 0x4000`; no linker crash. | 🤖 CI alignment gate; device ⬜ |
| L-45 | Release APK | Install signed release (R8) from CI; run L-01/L-02/L-21 subset | Same behaviour as debug; JNI callbacks intact; no debug-only behaviour. | ⬜ |
| L-46 | Public-IP check honesty | Toggle VPN; run check | Result labels egress path; it is one signal, not a leak test; no false "no leak" claim. | ⬜ |
| L-47 | No analytics/tracking traffic | Capture while app idle/connected | No requests to analytics/crash/backend endpoints; only VPN transport + optional `api.ipify.org` check. | ⬜ |

## 6. Automated subset in this repository

| Check | Where | Covers |
|---|---|---|
| `DnsLeakPolicy` tests | JVM unit tests | IPv4/IPv6 normalisation, refusal when no usable DNS, fallback validation, both families (L-06/L-07/L-09 policy logic). |
| `ProfileTrustPolicy` tests | JVM unit tests | Strict trust acceptance/rejection, explicit policy detection (ADR-009). |
| `MtuPolicy` / `ProfileSecurityPolicy` tests | JVM unit tests | Fixed-MTU connect config, AEAD-only `pull-filter` transform, compression policy text (L-38, L-42/L-43 policy logic). |
| `SplitTunnelPolicy` tests | JVM unit tests | Zero-app validation, uninstalled-package filtering, warning counts (L-34/L-35). |
| `PauseRetryPolicy` tests | JVM unit tests | Bounded budget + bounded jitter (L-27 policy logic). |
| `CaptivePortalPolicy` tests | JVM unit tests | Capability interpretation, paused-state honesty (L-28/L-29 policy logic). |
| Import-policy tests | JVM unit tests | Trust/cipher rejection and warnings at import. |
| 16 KB alignment gate | CI (`scripts/check-16kb-alignment.sh`) | Packaged `.so` `LOAD` `p_align >= 0x4000`, `zipalign -P 16` (L-44 build-time). |
| SBOM + advisory scan | CI (`security.yml`, `generate-sbom.sh`) | Supply-chain advisories, not runtime leaks. |

**Automated checks cannot prove runtime leak behaviour.** They pin policy logic
and release gates; the rows above marked *device* require the environment in
section 3.

## 7. Open gaps / not verified

- None of the physical-device leak rows (L-01–L-47 device cases) were executed
  for this branch at the time of writing.
- No automated packet-capture harness in CI (no device farm with controllable
  network egress in this repository).
- Cellular-side capture needs a controlled APN or a rooted/monitored device;
  not available.
- Captive-portal rows need a real portal or a controlled redirect setup.
- 16 KB runtime row needs an Android 15+ 16 KB device/emulator; CI only proves
  ELF alignment.
- Revocation behaviour is server/PKI-side and is not tested by this client
  matrix (see ADR-009).
- MASVS/ASVS assessment and independent penetration test:
  `docs/security/masvs-gap-assessment.md`.

## 8. Recording results

When a row is executed, append to the report (not this table) with: row id,
date, device/build, app version/commit, capture files (pcap path + hash), UI
screenshots/state, expected vs observed, and pass/fail. Update the status
column and `docs/testing.md` honestly. A failed leak row blocks the release;
a row that was not run is not a pass.
