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
     * ciphers from the server.
     *
     * OpenVPN Core parses `pull-filter <action> <match>` as exactly three
     * arguments and then re-parses <match> as an option line, so the match
     * expression must be quoted (`pull-filter accept "cipher AES-256-GCM"`).
     * The filters are order-sensitive: the `accept` lines must come before the
     * final catch-all `reject`, because first match wins.
     *
     * The transform also removes profile-supplied `pull-filter` lines that match
     * a pushed cipher (a profile could otherwise accept CBC before our reject),
     * and installs a local AEAD `cipher` fallback when the profile does not pin
     * any cipher at all, so Core cannot fall back to BF-CBC for such a profile.
     *
     * Idempotent: an already-canonical policy block is left untouched.
     */
    fun withAeadOnlyPushPolicy(config: String): String {
        val withoutCipherFilters = removePullFilters(config) { matchDirective -> matchDirective == "cipher" }
        // Remove our own fallback line (exact match) before deciding, so repeated
        // transforms keep a stable canonical order instead of treating the line we
        // appended as a profile pin.
        val withoutFallback = removeExactLine(withoutCipherFilters, AEAD_FALLBACK_CIPHER)
        return appendPolicyLines(withoutFallback, aeadPolicyLines(withoutFallback))
    }

    /**
     * Appends the push filters that make OpenVPN Core refuse server-pushed
     * compression in the opt-in Strict compression mode.
     *
     * Compression stubs still cause the engine to decompress unsolicited
     * compressed frames, so strict mode refuses every pushed compressor
     * (including `compress stub-v2`) except the explicit "comp-lzo no" stub that
     * only means "compression disabled". Profile-local `compress`/`comp-lzo`
     * directives are removed so a profile cannot re-enable a compression
     * context behind the setting.
     *
     * Idempotent.
     */
    fun withStrictCompressionPolicy(config: String): String {
        val withoutLocalDirectives = removeDirectives(config, COMPRESSION_DIRECTIVES)
        val withoutCompressionFilters = removePullFilters(withoutLocalDirectives) { matchDirective ->
            matchDirective == "compress" || matchDirective == "comp-lzo"
        }
        return appendPolicyLines(withoutCompressionFilters, STRICT_COMPRESSION_POLICY)
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
            "Strict: refuse every server-pushed compressor (including compression stubs). A " +
                "server that requires compression fails the connection with an explicit error " +
                "instead of silently compressing traffic."
    }
}

private val CIPHER_DIRECTIVES = setOf("cipher", "data-ciphers", "data-ciphers-fallback")
private val COMPRESSION_DIRECTIVES = setOf("compress", "comp-lzo")

/** A local AEAD fallback so a profile without any cipher pin cannot fall back to BF-CBC. */
private const val AEAD_FALLBACK_CIPHER = "cipher AES-256-GCM"

private val AEAD_PUSH_POLICY: List<String> =
    ProfileSecurityPolicy.AEAD_CIPHERS.map { "pull-filter accept \"cipher $it\"" } +
        "pull-filter reject \"cipher\""

private val STRICT_COMPRESSION_POLICY: List<String> = listOf(
    "pull-filter accept \"comp-lzo no\"",
    "pull-filter reject \"comp-lzo\"",
    "pull-filter reject \"compress\"",
)

private val WHITESPACE = Regex("[ \\t]+")

private fun aeadPolicyLines(config: String): List<String> =
    if (hasDirective(config, CIPHER_DIRECTIVES)) {
        AEAD_PUSH_POLICY
    } else {
        AEAD_PUSH_POLICY + AEAD_FALLBACK_CIPHER
    }

private fun hasDirective(config: String, directives: Set<String>): Boolean =
    config.lineSequence().any { lineDirective(it) in directives }

private fun removeDirectives(config: String, directives: Set<String>): String =
    rewriteBody(config) { lines -> lines.filterNot { lineDirective(it) in directives } }

/** Removes lines whose trimmed text equals [content] exactly. */
private fun removeExactLine(config: String, content: String): String =
    rewriteBody(config) { lines -> lines.filterNot { it.trim() == content } }

/**
 * Removes every `pull-filter` line whose match expression names a directive for
 * which [predicate] returns true. Handles quoted and unquoted match expressions.
 */
private fun removePullFilters(config: String, predicate: (String) -> Boolean): String =
    rewriteBody(config) { lines ->
        lines.filterNot { line ->
            val matchDirective = pullFilterMatchDirective(line) ?: return@filterNot false
            predicate(matchDirective)
        }
    }

private fun appendPolicyLines(config: String, policy: List<String>): String =
    rewriteBody(config) { lines ->
        // Drop only exact canonical lines; policy lines are re-appended in order.
        lines.filterNot { it.trim() in policy } + policy
    }

private fun rewriteBody(config: String, transform: (List<String>) -> List<String>): String {
    val trailingNewline = trailingNewlineOf(config)
    val lines = config.trimEnd('\n', '\r').lineSequence().toList()
    val body = transform(lines).filter { it.isNotEmpty() }.joinToString("\n")
    return if (trailingNewline == null) body else body + trailingNewline
}

private fun lineDirective(line: String): String? {
    val stripped = stripInlineComment(line).trim()
    if (stripped.isEmpty()) return null
    return stripped.split(WHITESPACE, limit = 2)[0].lowercase()
}

/**
 * For a `pull-filter <action> <match>` line, the lower-cased directive named by
 * <match>. Returns `null` for other lines. Quoted match expressions are
 * unquoted first (`"cipher AES-256-GCM"` -> `cipher`).
 */
private fun pullFilterMatchDirective(line: String): String? {
    val stripped = stripInlineComment(line).trim()
    if (stripped.isEmpty()) return null
    val parts = stripped.split(WHITESPACE, limit = 3)
    if (parts.size < 3 || parts[0].lowercase() != "pull-filter") return null
    var match = parts[2].trim()
    if (match.length >= 2 && match.startsWith('"') && match.endsWith('"')) {
        match = match.substring(1, match.length - 1).trim()
    }
    if (match.isEmpty()) return null
    return match.split(WHITESPACE, limit = 2)[0].lowercase()
}

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
