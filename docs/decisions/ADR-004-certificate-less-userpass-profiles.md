# ADR-004: Support certificate-less username/password profiles

## Status

Accepted.

## Context

A user reported that importing a commercial provider profile
(`NCVPN-SG-Singapore-UDP.ovpn`) failed with:

> External PKI profiles are not supported. Import a profile that contains its
> certificate and key.

The profile is a valid OpenVPN 2-style profile: inline `<ca>`, a list of
`remote ... 1194` entries, `remote-random`, `verify-x509-name sin- name-prefix`,
`remote-cert-tls server`, and `auth-user-pass`. It has **no client `cert` and no
`key`** because the provider authenticates users with a username/password; no
client certificate is sent. Provider exports in this shape are common.

The root cause is in OpenVPN 3 Core 3.9 (pinned revision `10c6a56`), not in the
profile. `ParseClientConfig` (`openvpn/client/cliopthelper.hpp`) computes:

```cpp
externalPki_ = (clientCertEnabled_ && is_external_pki(options));

static bool is_external_pki(const OptionList &options)
{
    const Option *epki = options.get_ptr("EXTERNAL_PKI");
    if (epki)
        return string::is_true(epki->get_optional(1, 16));
    else
    {
        const Option *cert = options.get_ptr("cert");
        const Option *key = options.get_ptr("key");
        return !cert || !key;
    }
}
```

So "no client certificate or key" is reported as *external PKI*, the same
`EvalConfig.externalPki` value used for profiles whose certificate/key is
supplied at runtime by a provider or hardware token. The app rejected every
`externalPki` profile, which is why the import failed. The profile itself parses
and evaluates without error: `eval.error` was false and only the `externalPki`
flag triggered the rejection.

Core already has a supported way to run such a profile: the OpenVPN 2
`setenv CLIENT_CERT 0` directive sets `clientCertEnabled_ = false`, which makes
`externalPki_` false and makes `ClientOptions` call
`set_local_cert_enabled(false)` (`openvpn/client/cliopt.hpp`). The connection then
performs the TLS handshake without a client certificate and never invokes
`external_pki_cert_request`, which the app's adapter answers with an error.

## Decision

1. Replace the unconditional `externalPki` rejection with a policy
   (`ProfileImportPolicy`) that inspects the merged profile text, because
   `EvalConfig` does not expose which of the two causes produced the flag:
   - explicit `EXTERNAL_PKI` / `setenv opt EXTERNAL_PKI` marker → reject (the app
     has no alias/key provider);
   - certificate-less profile with username/password authentication
     (`auth-user-pass`, `<auth-user-pass>`, `setenv opt auth-user-pass`) → append
     Core's own `setenv CLIENT_CERT 0` directive to the merged profile, re-run
     `eval_config`, and store the adapted content only if Core now reports
     `externalPki == false`;
   - certificate-less profile with no sign-in material → reject (fail closed
     rather than connecting with no authentication).
2. Record `clientCertNotRequired` in the stored profile metadata and show
   "Sign-in · no client certificate" in the profile list.
3. Compute the duplicate-detection SHA-256 over the adapted merged content, and
   never append the directive twice.
4. Do it entirely in Kotlin. Core re-parses the stored profile content when the
   service connects, so no C++ adapter, JNI signature, or `ClientAPI::Config`
   change is needed.

## Consequences

- Provider exports that sign in with a username/password now import and connect
  (all 151 profiles in the reported provider set take this path).
- The stored profile differs from the user's file by exactly one Core-defined
  directive; the original file on disk is untouched, and the adaptation is
  documented in `docs/profile-import.md`.
- Server certificate verification is unchanged: CA, `remote-cert-tls server`,
  and `verify-x509-name` remain in force. Only the client certificate is
  skipped, which the profile never had.
- Profiles needing a real external PKI provider are still rejected with a
  clearer message, and profiles with neither a certificate nor credentials fail
  closed.
- Limitation: a profile that supplies client-cert material through options other
  than `cert`/`key` (for example a `pkcs12` bundle) while also declaring
  `auth-user-pass` would be adapted with the client certificate disabled. Core
  3.9 does not parse `pkcs12` as client-cert material, so this is not
  distinguishable through the pinned API; such profiles are out of scope until a
  Core API exposes certificate presence directly.
