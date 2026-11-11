package com.smhasnan.openvpn.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the import-time trust/cipher hardening that [ConfigImporter]
 * delegates to the pure policies. The importer itself needs Android and is
 * covered by instrumentation tests; these pin the decision inputs it uses.
 */
class ImportWarningTest {
    @Test
    fun successWithoutWarningsHasANullWarning() {
        val success = ImportOutcome.Success("profile.ovpn", "id", alreadyImported = false)
        assertNull(success.warning)
        assertFalse(success.alreadyImported)
    }

    @Test
    fun successCarriesWarnings() {
        val success = ImportOutcome.Success(
            "profile.ovpn",
            "id",
            alreadyImported = false,
            warning = "This profile allows non-AEAD data ciphers (BF-CBC).",
        )
        assertEquals("This profile allows non-AEAD data ciphers (BF-CBC).", success.warning)
    }

    @Test
    fun aProfileWithoutTrustMaterialIsRejectedAtImport() {
        val config = "client\nremote example.com 1194\nauth-user-pass\n"
        val decision = ProfileTrustPolicy.validateForImport(config)
        assertTrue(decision is ImportDecision.Rejected)
        // The importer returns exactly this user-facing message as a failure.
        val message = (decision as ImportDecision.Rejected).message
        assertTrue(message.isNotBlank())
        assertTrue(message.contains("CA", ignoreCase = true))
        assertTrue(message.contains("fingerprint", ignoreCase = true))
    }

    @Test
    fun aTrustedProfilePassesImportValidation() {
        val config = """
            client
            remote example.com 1194
            <ca>
            -----BEGIN CERTIFICATE-----
            MIIB
            -----END CERTIFICATE-----
            </ca>
            auth-user-pass
        """.trimIndent()
        assertEquals(ImportDecision.Allowed, ProfileTrustPolicy.validateForImport(config))
    }

    @Test
    fun legacyCipherProfilesProduceAWarningInput() {
        val legacy = ProfileSecurityPolicy.legacyCipherFindings(
            "client\ncipher BF-CBC\ndata-ciphers AES-256-GCM:AES-256-CBC\n",
        )
        assertTrue(legacy.isNotEmpty())
        val names = legacy.map { it.value.uppercase() }.distinct()
        assertTrue(names.contains("BF-CBC"))
        assertTrue(names.contains("AES-256-CBC"))
    }

    @Test
    fun aeadProfilesProduceNoCipherWarningInput() {
        assertTrue(
            ProfileSecurityPolicy.legacyCipherFindings("client\ncipher AES-256-GCM\n").isEmpty(),
        )
    }

    @Test
    fun aSameNamedProfileWithADifferentCaIsDetectedByFingerprint() {
        val original = caProfile("MIIB")
        val replacement = caProfile("MIIC")
        val originalFingerprint = ProfileTrustPolicy.caFingerprint(original)
        val replacementFingerprint = ProfileTrustPolicy.caFingerprint(replacement)
        assertTrue(originalFingerprint != null)
        assertTrue(replacementFingerprint != null)
        assertNotEquals(originalFingerprint, replacementFingerprint)
    }

    @Test
    fun aSameNamedProfileWithTheSameCaIsNotFlagged() {
        val original = caProfile("MIIB")
        // Re-encoded with different PEM line wrapping: still the same CA.
        val reimported = caProfile("MI\nIB")
        assertEquals(
            ProfileTrustPolicy.caFingerprint(original),
            ProfileTrustPolicy.caFingerprint(reimported),
        )
    }

    private fun caProfile(base64: String): String = buildString {
        appendLine("client")
        appendLine("remote example.com 1194")
        appendLine("<ca>")
        appendLine("-----BEGIN CERTIFICATE-----")
        base64.lineSequence().forEach { appendLine(it) }
        appendLine("-----END CERTIFICATE-----")
        appendLine("</ca>")
        appendLine("auth-user-pass")
    }
}
