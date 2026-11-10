package com.smhasnan.openvpn.config

/**
 * Pure policy for the OpenVPN Core metadata that drives import decisions.
 *
 * OpenVPN 3 Core reports `externalPki=true` whenever a profile has no client
 * `cert`/`key` option. That also covers legitimate username/password-only
 * profiles, which Core can run without a client certificate once it is told
 * that no certificate is needed. This policy inspects the merged profile text
 * so the importer can tell those apart from profiles that really depend on an
 * external PKI provider.
 */
object ProfileImportPolicy {
    /**
     * Core directive that disables the client-certificate requirement for a
     * certificate-less, username/password profile.
     */
    const val CLIENT_CERT_DISABLED_DIRECTIVE = "setenv CLIENT_CERT 0"

    /**
     * A real (not commented-out) `EXTERNAL_PKI ...` or
     * `setenv opt EXTERNAL_PKI ...` directive line.
     */
    private val EXTERNAL_PKI_DIRECTIVE = Regex(
        pattern = "^(?![ \\t]*[#;])[ \\t]*(?:setenv[ \\t]+opt[ \\t]+)?EXTERNAL_PKI(?:[ \\t]+[^\\r\\n]*)?[ \\t\\r]*$",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )

    /**
     * A real `auth-user-pass` directive line, its `<auth-user-pass>` block
     * opener, or the `setenv opt auth-user-pass` form.
     */
    private val USER_PASSWORD_AUTH_DIRECTIVE = Regex(
        pattern = "^(?![ \\t]*[#;])[ \\t]*(?:setenv[ \\t]+opt[ \\t]+)?(?:auth-user-pass|<auth-user-pass>)(?:[ \\t]+[^\\r\\n]*)?[ \\t\\r]*$",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )

    /** A real `setenv CLIENT_CERT 0` directive line, comments excluded. */
    private val CLIENT_CERT_DISABLED_DIRECTIVE_LINE = Regex(
        pattern = "^(?![ \\t]*[#;])[ \\t]*setenv[ \\t]+CLIENT_CERT[ \\t]+0(?:[ \\t]+[#;][^\\r\\n]*)?[ \\t\\r]*$",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )

    /** True when the profile explicitly asks for an external PKI provider. */
    fun declaresExternalPki(config: String): Boolean = EXTERNAL_PKI_DIRECTIVE.containsMatchIn(config)

    /** True when the profile declares username/password authentication. */
    fun declaresUserPasswordAuth(config: String): Boolean =
        USER_PASSWORD_AUTH_DIRECTIVE.containsMatchIn(config)

    /**
     * Returns [config] with Core's [CLIENT_CERT_DISABLED_DIRECTIVE] appended, or
     * unchanged when that directive is already present. The original directive
     * text is preserved; only trailing newlines are normalized before the
     * directive is appended.
     */
    fun withoutClientCertificate(config: String): String {
        if (CLIENT_CERT_DISABLED_DIRECTIVE_LINE.containsMatchIn(config)) return config
        val body = config.trimEnd('\n', '\r')
        return "$body\n$CLIENT_CERT_DISABLED_DIRECTIVE\n"
    }

    /**
     * A real (not commented-out) `tls-cipher ...` directive line.
     */
    private val TLS_CIPHER_DIRECTIVE = Regex(
        pattern = "^(?![ \\t]*[#;])[ \\t]*tls-cipher[ \\t]+([^\\r\\n]+)[ \\t\\r]*$",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )

    /**
     * Removes `tls-cipher` pins that list only legacy TLS 1.2 suites (RSA/DHE,
     * without any ECDHE suite).
     *
     * Many provider exports pin e.g.
     * `TLS-DHE-RSA-WITH-AES-256-CBC-SHA:TLS-DHE-DSS-...:TLS-RSA-...`, which
     * excludes every ECDHE suite. When such a server now answers with TLS 1.3
     * only, the pinned list makes the handshake stall until the connection
     * timeout, with no authentication error. Dropping the pin lets the TLS
     * library negotiate its own safe defaults (TLS 1.3 suites are not selected
     * by `tls-cipher` anyway). Pins that still contain an ECDHE suite are kept
     * untouched, and commented-out lines are never modified.
     */
    fun withNegotiableTlsCiphers(config: String): String {
        if (!TLS_CIPHER_DIRECTIVE.containsMatchIn(config)) return config
        val lines = config.split('\n')
        val result = lines.filterNot { line ->
            val match = TLS_CIPHER_DIRECTIVE.matchEntire(line) ?: return@filterNot false
            val suites = match.groupValues[1]
                .split(':', ' ', '\t')
                .filter { it.isNotBlank() }
            suites.isNotEmpty() && suites.none { it.contains("ECDHE", ignoreCase = true) }
        }
        return if (result.size == lines.size) config else result.joinToString("\n")
    }
}
