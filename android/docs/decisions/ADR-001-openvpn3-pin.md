# ADR-001: Pin OpenVPN 3 Core release/3.9

## Status

Accepted.

## Context

The app must not implement OpenVPN protocol, TLS, certificate validation, or
packet framing. The core is a C++ library without an official prebuilt Android
artifact. The latest `main` branch changes APIs and licenses over time.

## Decision

Pin the official repository at `release/3.9`, commit
`10c6a56cb17fd015a2d24953c3c42145bbcdea58`, as a git submodule. Build the core
from source into the app's native library with pinned asio 1.24.0 + OpenVPN
patches, mbedTLS 3.6.2, lz4 1.9.4, jsoncpp 1.9.6, and fmt 10.2.1.

## Consequences

- Reproducible, reviewable dependency set.
- License is AGPL-3.0-only at this revision (not the later MPL dual license).
- API differences from current upstream docs must be handled against the checked
  out 3.9 headers (`data_epoch.cpp` absent, DNS callback name differs).
- Upgrading requires re-reading headers, rebuilding, rerunning tests, and
  reviewing the license delta.
