package com.smhasnan.openvpn.config

import com.smhasnan.openvpn.settings.CompressionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSecurityPolicyTest {
    @Test
    fun aeadCipherListIsExact() {
        assertEquals(
            listOf("AES-256-GCM", "AES-192-GCM", "AES-128-GCM", "CHACHA20-POLY1305"),
            ProfileSecurityPolicy.AEAD_CIPHERS,
        )
    }

    @Test
    fun findsLegacyCipherDirective() {
        assertEquals(
            listOf(LegacyFinding("cipher", "BF-CBC")),
            ProfileSecurityPolicy.legacyCipherFindings("client\ncipher BF-CBC\n"),
        )
    }

    @Test
    fun findsNonAeadEntriesInDataCiphers() {
        assertEquals(
            listOf(LegacyFinding("data-ciphers", "AES-256-CBC")),
            ProfileSecurityPolicy.legacyCipherFindings("client\ndata-ciphers AES-256-GCM:AES-256-CBC\n"),
        )
    }

    @Test
    fun findsDataCiphersFallback() {
        assertEquals(
            listOf(LegacyFinding("data-ciphers-fallback", "AES-128-CBC")),
            ProfileSecurityPolicy.legacyCipherFindings("client\ndata-ciphers-fallback AES-128-CBC\n"),
        )
    }

    @Test
    fun aeadOnlyProfilesHaveNoFindings() {
        assertEquals(
            emptyList<LegacyFinding>(),
            ProfileSecurityPolicy.legacyCipherFindings(
                "client\ncipher AES-256-GCM\ndata-ciphers CHACHA20-POLY1305:AES-128-GCM\n",
            ),
        )
    }

    @Test
    fun ignoresCommentedCipherLines() {
        assertEquals(
            emptyList<LegacyFinding>(),
            ProfileSecurityPolicy.legacyCipherFindings("client\n# cipher BF-CBC\n; data-ciphers BF-CBC\n"),
        )
    }

    @Test
    fun ignoresTrailingComments() {
        assertEquals(
            listOf(LegacyFinding("data-ciphers", "AES-256-CBC")),
            ProfileSecurityPolicy.legacyCipherFindings("client\ndata-ciphers AES-256-GCM:AES-256-CBC # legacy fallback\n"),
        )
    }

    @Test
    fun directivesAreCaseInsensitiveButValuesArePreserved() {
        assertEquals(
            listOf(LegacyFinding("data-ciphers", "aes-256-cbc")),
            ProfileSecurityPolicy.legacyCipherFindings("client\nDATA-CIPHERS aes-256-cbc\n"),
        )
    }

    @Test
    fun returnsEveryFindingInFileOrder() {
        val findings = ProfileSecurityPolicy.legacyCipherFindings(
            "client\ncipher BF-CBC\ndata-ciphers AES-256-CBC:AES-128-CBC\ndata-ciphers-fallback DES-EDE3-CBC\n",
        )
        assertEquals(4, findings.size)
        assertEquals(listOf("BF-CBC", "AES-256-CBC", "AES-128-CBC", "DES-EDE3-CBC"), findings.map { it.value })
    }

    @Test
    fun emptyAndBlankConfigsHaveNoFindings() {
        assertEquals(emptyList<LegacyFinding>(), ProfileSecurityPolicy.legacyCipherFindings(""))
        assertEquals(emptyList<LegacyFinding>(), ProfileSecurityPolicy.legacyCipherFindings("client\ndev tun\n"))
    }

    @Test
    fun aeadPushPolicyQuotesTheMatchExpression() {
        val result = ProfileSecurityPolicy.withAeadOnlyPushPolicy("client\n")
        val lines = result.lines().filter { it.isNotBlank() }
        assertEquals("client", lines.first())
        assertEquals("pull-filter accept \"cipher AES-256-GCM\"", lines[1])
        assertEquals("pull-filter accept \"cipher AES-192-GCM\"", lines[2])
        assertEquals("pull-filter accept \"cipher AES-128-GCM\"", lines[3])
        assertEquals("pull-filter accept \"cipher CHACHA20-POLY1305\"", lines[4])
        assertEquals("pull-filter reject \"cipher\"", lines[5])
        // No cipher directive in the profile: add an AEAD local fallback so Core
        // cannot fall back to BF-CBC when the server pushes no cipher either.
        assertEquals("cipher AES-256-GCM", lines[6])
        assertEquals(7, lines.size)
    }

    @Test
    fun aeadPushPolicyDoesNotAddFallbackCipherWhenProfilePinsOne() {
        val pinned = ProfileSecurityPolicy.withAeadOnlyPushPolicy("client\ncipher AES-256-GCM\n")
        assertEquals(1, pinned.lines().count { it.trim() == "cipher AES-256-GCM" })
        val dataCiphers = ProfileSecurityPolicy.withAeadOnlyPushPolicy(
            "client\ndata-ciphers AES-256-GCM:AES-128-GCM\n",
        )
        assertEquals(0, dataCiphers.lines().count { it.trim() == "cipher AES-256-GCM" })
    }

    @Test
    fun aeadPushPolicyRemovesProfileCipherPullFilters() {
        val config = "client\n" +
            "pull-filter accept cipher\n" +
            "pull-filter reject \"cipher BF-CBC\"\n" +
            "pull-filter ignore cipher\n"
        val result = ProfileSecurityPolicy.withAeadOnlyPushPolicy(config)
        assertFalse(result.lines().any { it == "pull-filter accept cipher" })
        assertFalse(result.contains("BF-CBC"))
        assertFalse(result.contains("ignore cipher"))
        assertEquals(1, result.lines().count { it == "pull-filter reject \"cipher\"" })
    }

    @Test
    fun aeadPushPolicyIsIdempotent() {
        val once = ProfileSecurityPolicy.withAeadOnlyPushPolicy("client\nremote example.com 1194\n")
        val twice = ProfileSecurityPolicy.withAeadOnlyPushPolicy(once)
        assertEquals(once, twice)
        assertEquals(1, twice.lines().count { it == "pull-filter reject \"cipher\"" })
        assertEquals(
            1,
            twice.lines().count { it == "pull-filter accept \"cipher AES-256-GCM\"" },
        )
        assertEquals(1, twice.lines().count { it.trim() == "cipher AES-256-GCM" })
    }

    @Test
    fun aeadPushPolicyPreservesTrailingNewline() {
        assertTrue(ProfileSecurityPolicy.withAeadOnlyPushPolicy("client\n").endsWith("\n"))
        assertFalse(ProfileSecurityPolicy.withAeadOnlyPushPolicy("client").endsWith("\n"))
    }

    @Test
    fun aeadPushPolicyCanonicalizesAPartialExistingBlock() {
        val config = "client\npull-filter reject cipher\npull-filter accept cipher AES-128-GCM\n"
        val result = ProfileSecurityPolicy.withAeadOnlyPushPolicy(config)
        val policyLines = result.lines().filter { it.startsWith("pull-filter ") }
        assertEquals(5, policyLines.size)
        assertEquals("pull-filter reject \"cipher\"", policyLines.last())
        assertTrue(
            result.indexOf("pull-filter accept \"cipher AES-256-GCM\"") <
                result.indexOf("pull-filter reject \"cipher\""),
        )
    }

    @Test
    fun aeadPushPolicyOnEmptyConfigStartsWithTheAccepts() {
        val result = ProfileSecurityPolicy.withAeadOnlyPushPolicy("")
        assertTrue(result.startsWith("pull-filter accept \"cipher AES-256-GCM\""))
        assertTrue(result.endsWith("cipher AES-256-GCM"))
    }

    @Test
    fun strictCompressionRemovesLocalCompressionDirectives() {
        val result = ProfileSecurityPolicy.withStrictCompressionPolicy(
            "client\ncomp-lzo adaptive\ncompress lzo\nremote example.com 1194\n",
        )
        assertFalse(result.contains("comp-lzo adaptive"))
        assertFalse(result.contains("compress lzo"))
        assertTrue(result.contains("remote example.com 1194"))
        assertTrue(result.contains("pull-filter accept \"comp-lzo no\""))
    }

    @Test
    fun strictCompressionPolicyAcceptsOnlyTheDisabledStubAndRejectsTheRest() {
        val result = ProfileSecurityPolicy.withStrictCompressionPolicy("client\n")
        val policy = result.lines().filter { it.startsWith("pull-filter ") }
        assertEquals(
            listOf(
                "pull-filter accept \"comp-lzo no\"",
                "pull-filter reject \"comp-lzo\"",
                "pull-filter reject \"compress\"",
            ),
            policy,
        )
    }

    @Test
    fun strictCompressionPolicyIsIdempotentAndStripsForeignFilters() {
        val once = ProfileSecurityPolicy.withStrictCompressionPolicy(
            "client\npull-filter ignore compress\n",
        )
        val twice = ProfileSecurityPolicy.withStrictCompressionPolicy(once)
        assertEquals(once, twice)
        assertFalse(twice.contains("ignore compress"))
        assertEquals(1, twice.lines().count { it == "pull-filter reject \"compress\"" })
    }

    @Test
    fun validateAeadOnlyAllowsAeadProfiles() {
        assertNull(ProfileSecurityPolicy.validateAeadOnly("client\ndata-ciphers AES-256-GCM:AES-128-GCM\n"))
        assertNull(ProfileSecurityPolicy.validateAeadOnly("client\ncipher CHACHA20-POLY1305\n"))
    }

    @Test
    fun validateAeadOnlyNamesThePinnedCipherAndTheOptIn() {
        val message = ProfileSecurityPolicy.validateAeadOnly("client\ncipher BF-CBC\n")
        assertNotNull(message)
        assertTrue(message!!.contains("BF-CBC"))
        assertTrue(message.contains("cipher", ignoreCase = true))
        assertTrue(message.contains("compatibility", ignoreCase = true))
        assertTrue(message.contains("Settings", ignoreCase = true))
    }

    @Test
    fun validateAeadOnlyNamesTheFirstOffendingCipher() {
        val message = ProfileSecurityPolicy.validateAeadOnly("client\ndata-ciphers AES-256-CBC:AES-128-CBC\n")
        assertNotNull(message)
        assertTrue(message!!.contains("AES-256-CBC"))
    }

    @Test
    fun nativeCompressionModeMatchesThePolicy() {
        assertEquals("asym", ProfileSecurityPolicy.nativeCompressionMode(CompressionPolicy.COMPATIBILITY))
        assertEquals("no", ProfileSecurityPolicy.nativeCompressionMode(CompressionPolicy.STRICT))
    }

    @Test
    fun compressionDescriptionsAreDistinctAndExplicit() {
        val compatibility = ProfileSecurityPolicy.compressionDescription(CompressionPolicy.COMPATIBILITY)
        val strict = ProfileSecurityPolicy.compressionDescription(CompressionPolicy.STRICT)
        assertNotEquals(compatibility, strict)
        assertTrue(compatibility.contains("server", ignoreCase = true))
        assertTrue(compatibility.contains("compress", ignoreCase = true))
        assertTrue(compatibility.contains("LZO-ASYM", ignoreCase = true))
        assertTrue(strict.contains("refuse", ignoreCase = true))
        assertTrue(strict.contains("compress", ignoreCase = true))
    }
}
