package com.smhasnan.openvpn.config

import java.security.MessageDigest
import java.util.Base64

/**
 * Pure profile trust policy (#11).
 *
 * OpenVPN authenticates the server when the profile contains a trust anchor:
 * a CA it verifies the server certificate against, or a legacy `tls-remote`
 * subject pin. Profiles with neither (for example a bare `remote` +
 * `auth-user-pass` export) can be made to connect to an attacker-controlled
 * server, and the client would have no way to notice.
 *
 * `peer-fingerprint` is *not* counted as a trust anchor here: the pinned
 * OpenVPN 3 Core build uses the mbedTLS backend, which has no peer-fingerprint
 * implementation (only the OpenSSL backend does), so the pin would be silently
 * ignored. A fingerprint-only profile is rejected with an explicit message; a
 * profile that also has a CA is accepted, because the CA still authenticates the
 * server, but the ignored pin is reported as a warning at import time.
 *
 * Comments are ignored everywhere; inline `<ca>` blocks count as real material.
 */
data class TrustMaterial(
    val hasCa: Boolean = false,
    val hasPeerFingerprint: Boolean = false,
    val hasTlsRemote: Boolean = false,
) {
    /**
     * True when the profile gives this client something it can actually verify
     * the server with. `peer-fingerprint` is deliberately excluded (see class
     * documentation).
     */
    val hasServerTrust: Boolean
        get() = hasCa || hasTlsRemote
}

sealed interface ImportDecision {
    /** The profile carries a trust anchor and may be imported. */
    data object Allowed : ImportDecision

    /** The profile must not be imported; [message] is user-facing and actionable. */
    data class Rejected(val message: String) : ImportDecision
}

private const val NO_SERVER_TRUST_MESSAGE =
    "This profile has no CA certificate and no explicit server-name pin, so the app cannot " +
        "verify that it is really connecting to the intended VPN server. Ask your VPN provider " +
        "for a profile that includes a <ca> certificate, or add one to the .ovpn file, then " +
        "import it again."

private const val PEER_FINGERPRINT_NOT_ENFORCED_MESSAGE =
    "This profile relies on a peer-fingerprint pin, but the VPN engine in this build cannot " +
        "verify certificate fingerprints (that option is silently ignored by its TLS backend), " +
        "so the server identity could not be authenticated. Ask your VPN provider for a profile " +
        "that includes a <ca> certificate, or add one to the .ovpn file, then import it again."

object ProfileTrustPolicy {
    /** Real (not commented-out) trust directives present in [config]. */
    fun trustMaterial(config: String): TrustMaterial = scanTrust(config).material

    /**
     * Fails closed for profiles that contain no trust anchor this backend can
     * enforce. A profile without a CA or a legacy `tls-remote` directive must
     * not be imported, because server authentication would be impossible (or, in
     * the case of a peer-fingerprint-only profile, silently skipped).
     */
    fun validateForImport(config: String): ImportDecision {
        val material = trustMaterial(config)
        return when {
            material.hasServerTrust -> ImportDecision.Allowed
            material.hasPeerFingerprint -> ImportDecision.Rejected(PEER_FINGERPRINT_NOT_ENFORCED_MESSAGE)
            else -> ImportDecision.Rejected(NO_SERVER_TRUST_MESSAGE)
        }
    }

    /**
     * True when the profile contains a `peer-fingerprint` pin that this engine
     * build ignores. Callers should warn the user; the pin is not a trust anchor.
     */
    fun hasIgnoredPeerFingerprint(config: String): Boolean =
        trustMaterial(config).hasPeerFingerprint

    /**
     * Stable SHA-256 fingerprint of the CA material in [config], or `null` when
     * the profile has no CA or no inline material can be extracted (for example
     * a `ca file.crt` reference that Core did not merge into the profile).
     *
     * The fingerprint is order-independent for multi-certificate bundles,
     * ignores PEM line wrapping and comments, and hashes the decoded DER
     * certificate bytes rather than the text, so re-encoding the same CA does not
     * look like a change. Used to warn when a same-named profile is re-imported
     * with a different CA.
     */
    fun caFingerprint(config: String): String? {
        val scanned = scanTrust(config)
        if (!scanned.material.hasCa) return null
        val caText = scanned.caText?.takeIf { it.isNotBlank() } ?: return null
        val certificates = extractPemCertificates(caText)
        val material = if (certificates.isEmpty()) {
            normalizeCaText(caText).toByteArray(Charsets.UTF_8)
        } else {
            certificates
                .sortedBy { certificate -> certificate.toHex() }
                .fold(ByteArray(0)) { combined, certificate -> combined + certificate }
        }
        if (material.isEmpty()) return null
        return sha256Hex(material)
    }
}

private class ScanResult(
    val material: TrustMaterial,
    val caText: String?,
)

private enum class InlineBlock { NONE, CA, PEER_FINGERPRINT }

private fun scanTrust(config: String): ScanResult {
    var hasCa = false
    var hasPeerFingerprint = false
    var hasTlsRemote = false
    val caText = StringBuilder()
    var block = InlineBlock.NONE
    var caCompleted = false

    for (rawLine in config.lineSequence()) {
        val line = rawLine.trim()
        when (block) {
            InlineBlock.CA -> {
                if (line.equals("</ca>", ignoreCase = true)) {
                    block = InlineBlock.NONE
                    caCompleted = true
                } else {
                    caText.append(rawLine).append('\n')
                }
                continue
            }
            InlineBlock.PEER_FINGERPRINT -> {
                if (line.equals("</peer-fingerprint>", ignoreCase = true)) {
                    block = InlineBlock.NONE
                }
                continue
            }
            InlineBlock.NONE -> Unit
        }
        if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
        when {
            line.equals("<ca>", ignoreCase = true) -> {
                hasCa = true
                block = InlineBlock.CA
            }
            line.equals("<peer-fingerprint>", ignoreCase = true) -> {
                hasPeerFingerprint = true
                block = InlineBlock.PEER_FINGERPRINT
            }
            else -> {
                val tokens = line.split(WHITESPACE)
                if (tokens.isEmpty()) continue
                when (tokens[0].lowercase()) {
                    "ca" -> if (tokens.size >= 2) hasCa = true
                    "peer-fingerprint" -> if (tokens.size >= 2) hasPeerFingerprint = true
                    "tls-remote" -> if (tokens.size >= 2) hasTlsRemote = true
                }
            }
        }
    }

    val material = TrustMaterial(
        hasCa = hasCa,
        hasPeerFingerprint = hasPeerFingerprint,
        hasTlsRemote = hasTlsRemote,
    )
    val extractedCa = if (!hasCa) null else if (caCompleted || caText.isNotEmpty()) caText.toString() else ""
    return ScanResult(material, extractedCa)
}

private val WHITESPACE = Regex("[ \\t]+")

private val PEM_CERTIFICATE = Regex(
    "-----BEGIN CERTIFICATE-----([\\s\\S]*?)-----END CERTIFICATE-----",
)

private fun extractPemCertificates(caText: String): List<ByteArray> =
    PEM_CERTIFICATE.findAll(caText).mapNotNull { match ->
        runCatching { Base64.getMimeDecoder().decode(match.groupValues[1].trim()) }.getOrNull()
    }.filter { it.isNotEmpty() }.toList()

private fun normalizeCaText(caText: String): String =
    caText.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(";") }
        .joinToString("\n")

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

private fun sha256Hex(value: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(value).toHex()
