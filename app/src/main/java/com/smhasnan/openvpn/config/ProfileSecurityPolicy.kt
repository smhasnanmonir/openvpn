package com.smhasnan.openvpn.config

import com.smhasnan.openvpn.settings.CompressionPolicy

/** A non-AEAD cipher offered by a profile directive. */
data class LegacyFinding(val directive: String, val value: String)

/**
 * Pure profile security policy for the strict cipher/compression modes (#19).
 *
 * AEAD ciphers (AES-GCM, ChaCha20-Poly1305) authenticate the data channel, so
 * tampering is detected instead of producing plaintext that is fed to a
 * decompressor (the VORACLE class of attacks). Profiles that still pin or allow
 * CBC ciphers keep working only in the explicit compatibility mode.
 */
object ProfileSecurityPolicy {
    val AEAD_CIPHERS: List<String> = listOf(
        "AES-256-GCM",
        "AES-192-GCM",
        "AES-128-GCM",
        "CHACHA20-POLY1305",
    )

    /**
     * Every non-AEAD entry in a real `cipher`, `data-ciphers` or
     * `data-ciphers-fallback` directive. Comments (full-line and trailing) are
     * ignored; directives are matched case-insensitively.
     */
    fun legacyCipherFindings(config: String): List<LegacyFinding> {
        val findings = mutableListOf<LegacyFinding>()
        for (rawLine in config.lineSequence()) {
            val line = stripInlineComment(rawLine).trim()
            if (line.isEmpty()) continue
            val tokens = line.split(WHITESPACE, limit = 2)
            val directive = tokens[0].lowercase()
            if (directive !in CIPHER_DIRECTIVES) continue
            val value = tokens.getOrNull(1)?.trim().orEmpty()
            if (value.isEmpty()) continue
            for (entry in value.split(':')) {
                val cipher = entry.trim()
                if (cipher.isEmpty()) continue
                if (cipher.uppercase() !in AEAD_CIPHERS) findings.add(LegacyFinding(directive, cipher))
            }
        }
        return findings
    }

    /**
     * Appends the pull-filter policy that makes OpenVPN Core accept only AEAD
     * ciphers from the server. The filters are order-sensitive: an `accept` line
     * for each AEAD cipher must come before the final catch-all `reject`,
     * otherwise the reject would win for every cipher.
     *
     * Idempotent: an already-canonical policy block is left untouched, and a
     * partial block is rewritten in canonical order (so an upgrade cannot leave
     * `reject` ahead of the `accept` lines).
     */
    fun withAeadOnlyPushPolicy(config: String): String {
        val trailingNewline = trailingNewlineOf(config)
        val body = config.trimEnd('\n', '\r')
        val policySet = AEAD_PUSH_POLICY.map { it.lowercase() }.toSet()
        val bodyLines = body.lineSequence().toList()
        val retainedLines = bodyLines.filterNot { it.trim().lowercase() in policySet }
        val canonicalBody = retainedLines.joinToString("\n").trimEnd('\n', '\r')
        val canonicalLines = canonicalBody.lineSequence().filter { it.isNotEmpty() }.toList()
        val mergedBody = when {
            canonicalLines.isEmpty() -> AEAD_PUSH_POLICY.joinToString("\n")
            else -> canonicalLines.joinToString("\n") + "\n" + AEAD_PUSH_POLICY.joinToString("\n")
        }
        val merged = if (trailingNewline == null) mergedBody else mergedBody + trailingNewline
        // Preserve the input exactly when it already is the canonical form.
        return if (merged == config) config else merged
    }

    /**
     * Returns `null` when the profile may be used under the AEAD-only policy, or
     * an actionable user-facing message naming the first offending pinned cipher
     * and the explicit compatibility opt-in otherwise.
     */
    fun validateAeadOnly(config: String): String? {
        val finding = legacyCipherFindings(config).firstOrNull() ?: return null
        return "This profile pins or allows the non-AEAD data cipher ${finding.value} " +
            "(${finding.directive}). AEAD-only mode refuses non-AEAD ciphers. Use a profile " +
            "that offers AES-GCM or ChaCha20-Poly1305, or switch the cipher policy setting to " +
            "the compatibility (profile) option in Settings to connect with this profile."
    }

    /** OpenVPN Core's `compression` value for [policy]: `asym` or `no`. */
    fun nativeCompressionMode(policy: CompressionPolicy): String = when (policy) {
        CompressionPolicy.COMPATIBILITY -> "asym"
        CompressionPolicy.STRICT -> "no"
    }

    /** Human-readable explanation of [policy] for the settings/diagnostics UI. */
    fun compressionDescription(policy: CompressionPolicy): String = when (policy) {
        CompressionPolicy.COMPATIBILITY ->
            "Compatibility: allow the server to compress traffic it sends to this device " +
                "(LZO-ASYM, as some providers require). Compression is never applied in the " +
                "upload direction, and symmetric compression is still refused."
        CompressionPolicy.STRICT ->
            "Strict: refuse any server-pushed compression. A server that requires compression " +
                "fails the connection with an explicit error instead of silently compressing " +
                "traffic."
    }
}

private val CIPHER_DIRECTIVES = setOf("cipher", "data-ciphers", "data-ciphers-fallback")

private val AEAD_PUSH_POLICY: List<String> =
    ProfileSecurityPolicy.AEAD_CIPHERS.map { "pull-filter accept cipher $it" } +
        "pull-filter reject cipher"

private val WHITESPACE = Regex("[ \\t]+")

private fun trailingNewlineOf(config: String): String? = when {
    config.endsWith("\r\n") -> "\r\n"
    config.endsWith("\n") -> "\n"
    config.endsWith("\r") -> "\r"
    else -> null
}

/** Removes a trailing `#`/`;` comment; directive values cannot contain those characters. */
private fun stripInlineComment(line: String): String {
    for (index in line.indices) {
        val char = line[index]
        if ((char == '#' || char == ';') && (index == 0 || line[index - 1].isWhitespace())) {
            return line.substring(0, index)
        }
    }
    return line
}
