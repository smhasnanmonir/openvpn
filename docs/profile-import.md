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
8. The result is encrypted and saved. The temp directory is recursively deleted.

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

## Validation and rejection

Examples of rejected input:

- A non-`.ovpn` file selected as the only document.
- An empty, malformed, or oversized profile.
- A missing external certificate/key.
- A profile rejected by `eval_config`.
- External PKI profiles (not supported by this build).
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
