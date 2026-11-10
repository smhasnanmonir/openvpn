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
}
