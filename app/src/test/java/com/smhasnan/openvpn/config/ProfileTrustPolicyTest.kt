package com.smhasnan.openvpn.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTrustPolicyTest {
    @Test
    fun inlineCaBlockCountsAsTrust() {
        val material = ProfileTrustPolicy.trustMaterial(CA_PROFILE)
        assertTrue(material.hasCa)
        assertFalse(material.hasPeerFingerprint)
        assertFalse(material.hasTlsRemote)
        assertTrue(material.hasServerTrust)
    }

    @Test
    fun caFileDirectiveCountsAsTrust() {
        val material = ProfileTrustPolicy.trustMaterial("client\nca ca.crt\nremote example.com 1194\n")
        assertTrue(material.hasCa)
        assertTrue(material.hasServerTrust)
    }

    @Test
    fun inlinePeerFingerprintBlockCountsAsTrust() {
        val config = """
            client
            remote example.com 1194
            <peer-fingerprint>
            AA:BB:CC
            DD:EE:FF
            </peer-fingerprint>
        """.trimIndent()
        val material = ProfileTrustPolicy.trustMaterial(config)
        assertTrue(material.hasPeerFingerprint)
        assertFalse(material.hasCa)
        assertTrue(material.hasServerTrust)
    }

    @Test
    fun linePeerFingerprintCountsAsTrust() {
        val material = ProfileTrustPolicy.trustMaterial("client\npeer-fingerprint AA:BB:CC\nremote example.com 1194\n")
        assertTrue(material.hasPeerFingerprint)
        assertTrue(material.hasServerTrust)
    }

    @Test
    fun legacyTlsRemoteCountsAsExplicitTrust() {
        val material = ProfileTrustPolicy.trustMaterial("client\nremote example.com 1194\ntls-remote example.com\n")
        assertTrue(material.hasTlsRemote)
        assertTrue(material.hasServerTrust)
    }

    @Test
    fun commentsAreIgnored() {
        val config = """
            client
            # ca ca.crt
            ; peer-fingerprint AA:BB:CC
            # tls-remote example.com
            remote example.com 1194
        """.trimIndent()
        val material = ProfileTrustPolicy.trustMaterial(config)
        assertFalse(material.hasCa)
        assertFalse(material.hasPeerFingerprint)
        assertFalse(material.hasTlsRemote)
        assertFalse(material.hasServerTrust)
    }

    @Test
    fun commentedBlockOpenersAreIgnored() {
        val config = """
            client
            # <ca>
            # -----BEGIN CERTIFICATE-----
            # MIIB
            # -----END CERTIFICATE-----
            # </ca>
        """.trimIndent()
        assertFalse(ProfileTrustPolicy.trustMaterial(config).hasCa)
    }

    @Test
    fun blockContentIsNotParsedAsDirectives() {
        val caWithDirectiveTextInside = """
            client
            <ca>
            tls-remote example.com
            peer-fingerprint AA:BB
            </ca>
        """.trimIndent()
        val material = ProfileTrustPolicy.trustMaterial(caWithDirectiveTextInside)
        assertTrue(material.hasCa)
        assertFalse(material.hasTlsRemote)
        assertFalse(material.hasPeerFingerprint)
    }

    @Test
    fun remoteCertificateUsageAloneIsNotATrustAnchor() {
        val config = "client\nremote example.com 1194\nremote-cert-tls server\nverify-x509-name example.com\n"
        val material = ProfileTrustPolicy.trustMaterial(config)
        assertFalse(material.hasCa)
        assertFalse(material.hasPeerFingerprint)
        assertFalse(material.hasTlsRemote)
        assertFalse(material.hasServerTrust)
    }

    @Test
    fun validatesProfilesWithATrustAnchor() {
        assertEquals(ImportDecision.Allowed, ProfileTrustPolicy.validateForImport(CA_PROFILE))
        assertEquals(ImportDecision.Allowed, ProfileTrustPolicy.validateForImport("client\npeer-fingerprint AA:BB\n"))
        assertEquals(ImportDecision.Allowed, ProfileTrustPolicy.validateForImport("client\ntls-remote example.com\n"))
    }

    @Test
    fun rejectsProfilesWithoutATrustAnchor() {
        val decision = ProfileTrustPolicy.validateForImport("client\nremote example.com 1194\nauth-user-pass\n")
        assertTrue(decision is ImportDecision.Rejected)
        val message = (decision as ImportDecision.Rejected).message
        assertTrue(message.contains("CA", ignoreCase = true))
        assertTrue(message.contains("fingerprint", ignoreCase = true))
        assertTrue(message.contains("<ca>"))
        assertTrue(message.contains("<peer-fingerprint>"))
        assertTrue(message.contains("import", ignoreCase = true))
        assertFalse(message.contains("tls-remote"))
    }

    @Test
    fun rejectsAnEmptyProfile() {
        assertTrue(ProfileTrustPolicy.validateForImport("") is ImportDecision.Rejected)
        assertTrue(ProfileTrustPolicy.validateForImport("client\ndev tun\n") is ImportDecision.Rejected)
    }

    @Test
    fun caFingerprintIsNullWithoutCa() {
        assertNull(ProfileTrustPolicy.caFingerprint("client\nremote example.com 1194\n"))
        assertNull(ProfileTrustPolicy.caFingerprint("client\npeer-fingerprint AA:BB\n"))
        assertNull(ProfileTrustPolicy.caFingerprint("client\nca ca.crt\n"))
    }

    @Test
    fun caFingerprintIsStableLowercaseSha256Hex() {
        val first = ProfileTrustPolicy.caFingerprint(CA_PROFILE)
        val second = ProfileTrustPolicy.caFingerprint(CA_PROFILE)
        assertTrue(first != null)
        assertEquals(first, second)
        assertTrue(first!!.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun reWrappingTheSamePemDoesNotChangeTheFingerprint() {
        val oneLine = caProfileWith("MIIB")
        val wrapped = caProfileWith("MI\nIB")
        assertEquals(ProfileTrustPolicy.caFingerprint(oneLine), ProfileTrustPolicy.caFingerprint(wrapped))
    }

    @Test
    fun crlfAndExtraWhitespaceDoNotChangeTheFingerprint() {
        val lf = caProfileWith("MIIB")
        val crlf = lf.replace("\n", "\r\n")
        val padded = caProfileWith("  MIIB  ")
        assertEquals(ProfileTrustPolicy.caFingerprint(lf), ProfileTrustPolicy.caFingerprint(crlf))
        assertEquals(ProfileTrustPolicy.caFingerprint(lf), ProfileTrustPolicy.caFingerprint(padded))
    }

    @Test
    fun commentsAroundCaDoNotChangeTheFingerprint() {
        val plain = caProfileWith("MIIB")
        val commented = "client\n# a comment\n<ca>\n# another comment\n" +
            "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n</ca>\n"
        assertEquals(ProfileTrustPolicy.caFingerprint(plain), ProfileTrustPolicy.caFingerprint(commented))
    }

    @Test
    fun differentCaCertificatesHaveDifferentFingerprints() {
        val first = ProfileTrustPolicy.caFingerprint(caProfileWith("MIIB"))
        val second = ProfileTrustPolicy.caFingerprint(caProfileWith("MIIC"))
        assertTrue(first != null)
        assertTrue(second != null)
        assertNotEquals(first, second)
    }

    @Test
    fun certificateBundleOrderDoesNotChangeTheFingerprint() {
        val forward = bundleProfile("MIIB", "MIIC")
        val reversed = bundleProfile("MIIC", "MIIB")
        assertEquals(ProfileTrustPolicy.caFingerprint(forward), ProfileTrustPolicy.caFingerprint(reversed))
    }

    @Test
    fun addingACertificateToABundleChangesTheFingerprint() {
        assertNotEquals(ProfileTrustPolicy.caFingerprint(bundleProfile("MIIB")), ProfileTrustPolicy.caFingerprint(bundleProfile("MIIB", "MIIC")))
    }

    private companion object {
        val CA_PROFILE = caProfileWith("MIIB")

        fun caProfileWith(base64: String): String = """
            client
            dev tun
            proto udp
            remote example.com 1194
            <ca>
            -----BEGIN CERTIFICATE-----
            $base64
            -----END CERTIFICATE-----
            </ca>
            auth-user-pass
        """.trimIndent()

        fun bundleProfile(vararg certificates: String): String = buildString {
            appendLine("client")
            appendLine("remote example.com 1194")
            appendLine("<ca>")
            for (certificate in certificates) {
                appendLine("-----BEGIN CERTIFICATE-----")
                appendLine(certificate)
                appendLine("-----END CERTIFICATE-----")
            }
            appendLine("</ca>")
            appendLine("auth-user-pass")
        }
    }
}
