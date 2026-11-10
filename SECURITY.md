# Security policy

## Reporting a vulnerability

Please report security issues privately to the repository owner instead of
opening a public issue. Include:

- affected version/commit and build type;
- Android version/device;
- a minimal reproduction using **synthetic** profiles and credentials;
- the security impact.

Do **not** include real credentials, private keys, full profiles, or personal
data. If a proof of concept needs a profile, generate a throwaway CA/server and
redact paths.

## Scope

In scope:

- secret exposure (profiles, credentials, keys, logs, backups, clipboard);
- TLS/certificate-validation bypasses in this app's integration;
- TUN/socket-protection or routing flaws introduced by this app;
- JNI lifetime/memory-safety bugs;
- exported component/permission mistakes;
- unsafe profile import or path handling;
- app-lock bypasses.

Out of scope:

- vulnerabilities in upstream OpenVPN 3 Core/mbedTLS/AndroidX that are not
  reachable through this app (report upstream);
- rooted-device/OS-compromise scenarios;
- malicious VPN servers or profiles that intentionally weaken their own
  configuration;
- lack of an app-level guaranteed kill switch (documented limitation).

## Supported versions

Security fixes are applied to the latest tagged release. Development snapshots
are provided as-is.

## Disclosure

Please allow time for a fix and coordinated disclosure. This is a
community-maintained project without a bug bounty.
