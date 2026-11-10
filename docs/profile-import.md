# Profile import

## User flow

1. The user taps **Import** on Home or Profiles.
2. Android opens `ActivityResultContracts.OpenMultipleDocuments()` with `*/*`
   MIME because `.ovpn` files are commonly reported as
   `application/octet-stream` by providers.
3. The importer identifies files ending in `.ovpn` (case-insensitive).
4. Non-`.ovpn` files selected in the same picker are treated as possible
   companion files (`ca`, `cert`, `key`, `tls-auth`, etc.).
5. Each `.ovpn` is processed independently. A copy of the selected `.ovpn` and
   all selected companions is written to a random private cache directory.
6. `OpenVPNClientHelper::merge_config(path, follow_references=true)` resolves
   references and produces a unified profile. If a referenced file is missing,
   the import fails with a clear message and the partial profile is discarded.
7. The unified profile is run through `eval_config` for validation and
   non-secret metadata (remote host/port/proto, auth requirements, challenge,
   key-password requirement).
8. If Core reports `externalPki` for a certificate-less username/password
   profile, the importer adapts it (see below), validates the adapted profile
   again, and stores the adapted content.
9. If the profile pins only legacy TLS 1.2 cipher suites in a `tls-cipher`
   directive, that pin is dropped so the TLS library can negotiate modern
   defaults (see below), and the profile is validated again.
10. The result is encrypted and saved. The temp directory is recursively deleted.

Nothing is copied into shared storage and the raw profile is never logged.

## Why a merge is used

The app does not rewrite directives with a homemade parser. OpenVPN Core is the
authority for:

- inline `<ca>`, `<cert>`, `<key>`, `<tls-auth>`, `<tls-crypt>` blocks;
- external file references;
- quoted paths and directive syntax;
- rejecting malformed/unsupported constructs.

The engine's merged output is stored as the profile content, so later connects
do not depend on the source document URI or long-lived SAF permissions.

## External references and user consent

SAF grants access only to the URIs the user selected. The importer never scans
the filesystem for referenced names. If the user selects only the `.ovpn` and it
references an external file, the engine reports a reference failure and the
profile is rejected. Clear guidance tells the user to select the companion files
together with the `.ovpn` file.

If a companion file cannot be opened, has an unsafe name, or exceeds the size
limit, the import fails. There is no silent omission of security-related files.

## Username/password-only profiles (no client certificate)

OpenVPN Core reports `externalPki=true` whenever a profile has no client
`cert`/`key` option, even for a legitimate profile that authenticates with a
username and password (`auth-user-pass`) and needs no client certificate at all.

When Core reports `externalPki` and the merged profile:

- does **not** declare an `EXTERNAL_PKI` marker (`EXTERNAL_PKI` or
  `setenv opt EXTERNAL_PKI`), and
- declares username/password authentication (`auth-user-pass`,
  `<auth-user-pass>` or `setenv opt auth-user-pass`),

the importer appends OpenVPN Core's supported directive

```
setenv CLIENT_CERT 0
```

to the stored merged profile, re-runs `eval_config`, and stores the adapted
content. Core re-parses the stored profile when connecting, so no native change
is required. The metadata records `clientCertNotRequired=true` and the profile
list shows "Sign-in · no client certificate".

The SHA-256 content hash used for duplicate detection is computed over the
adapted merged profile, so re-importing the same `.ovpn` is still detected as a
duplicate. The directive is only added when it is not already present.

Genuinely external-PKI profiles (an explicit `EXTERNAL_PKI` marker, as used by
profiles whose certificate and key are supplied at runtime by another app or a
hardware token) remain rejected, as do profiles with neither a client
certificate/key nor username/password sign-in material.

## Legacy TLS cipher pins

Some provider exports pin a TLS 1.2 cipher list, for example:

```
tls-cipher TLS-DHE-RSA-WITH-AES-256-CBC-SHA:TLS-DHE-DSS-WITH-AES-256-CBC-SHA:TLS-RSA-WITH-AES-256-CBC-SHA
```

None of those suites is an ECDHE suite. When a provider now serves TLS 1.3 only,
such a pin prevents the handshake from completing, so the connection stalls
until the timeout without any authentication error. When every suite in a real
(not commented-out) `tls-cipher` directive is a legacy non-ECDHE suite, the
importer removes that directive, validates the profile again, and logs
"removed a legacy-only tls-cipher pin …" to diagnostics. Pins that still contain
an ECDHE suite are left untouched. The same normalization is applied when
connecting to profiles imported by earlier app versions (stored with the pin),
so updating the app fixes existing profiles without re-importing them. This only
drops an obsolete TLS 1.2 cipher selection; TLS 1.3 cipher suites are negotiated
by the TLS library regardless, and certificate verification is never weakened.

## Validation and rejection

Examples of rejected input:

- A non-`.ovpn` file selected as the only document.
- An empty, malformed, or oversized profile.
- A missing external certificate/key.
- A profile rejected by `eval_config`.
- A profile that explicitly requests external PKI (`EXTERNAL_PKI` or
  `setenv opt EXTERNAL_PKI`).
- A certificate-less profile with no username/password sign-in.
- A certificate-less username/password profile that Core still reports as
  external PKI after the `setenv CLIENT_CERT 0` adaptation.
- Duplicate repeated file names in one selection.
- Companion names containing path separators, control characters, `.` or `..`.

Duplicate content (SHA-256 of the merged profile) is detected. The existing
profile is selected instead of creating a second copy.

## Storage and naming

- Stored filename: random UUID + `.profile` (no profile/server name in the
  filesystem path).
- Display name: derived from the selected filename, sanitized through
  `ProfileNameValidator`, editable later.
- Rename changes only the display name; VPN directives are not touched.
- Emoji avatar is stored with the encrypted metadata.
- Delete removes both the profile blob and the credential blob.
- Profile files and credentials are encrypted with AES-256-GCM using
  Android Keystore keys and written atomically (temp file, fsync, rename).

## Limits

- Maximum 4 MiB per selected file.
- `.ovpn` extension required.
- Full external reference resolution only when the referenced files are among
  the selected documents.
- Android Storage Access Framework provider quirks are handled with user-facing
  errors rather than assumptions about file paths.
