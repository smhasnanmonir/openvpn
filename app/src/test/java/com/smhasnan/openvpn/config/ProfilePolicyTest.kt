package com.smhasnan.openvpn.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileNameValidatorTest {
    @Test
    fun trimsAndAccepts() {
        assertEquals("Office VPN", ProfileNameValidator.validate("  Office VPN  ").getOrNull())
    }

    @Test
    fun rejectsBlank() {
        assertTrue(ProfileNameValidator.validate("   ").isFailure)
    }

    @Test
    fun rejectsTooLong() {
        assertTrue(ProfileNameValidator.validate("a".repeat(61)).isFailure)
    }

    @Test
    fun rejectsControlCharacters() {
        assertTrue(ProfileNameValidator.validate("line\nbreak").isFailure)
    }
}

class ImportFilePolicyTest {
    @Test
    fun identifiesProfilesCaseInsensitively() {
        assertTrue(ImportFilePolicy.isProfileFile("work.OVPN"))
        assertFalse(ImportFilePolicy.isProfileFile("ca.crt"))
    }

    @Test
    fun acceptsNormalCompanionNames() {
        assertEquals("ca cert.pem", ImportFilePolicy.safeCompanionName("ca cert.pem"))
        assertEquals("client.key", ImportFilePolicy.safeCompanionName("client.key"))
    }

    @Test
    fun rejectsTraversalAndControlCharacters() {
        assertNull(ImportFilePolicy.safeCompanionName(".."))
        assertNull(ImportFilePolicy.safeCompanionName("../secret"))
        assertNull(ImportFilePolicy.safeCompanionName("bad\u0000name"))
        assertNull(ImportFilePolicy.safeCompanionName("nested/evil.pem"))
    }

    @Test
    fun derivesDisplayName() {
        assertEquals("Tokyo office", ImportFilePolicy.suggestedDisplayName("Tokyo office.ovpn"))
        assertEquals("profile", ImportFilePolicy.suggestedDisplayName("profile.ovpn"))
    }
}

class ProfileImportPolicyTest {
    @Test
    fun detectsBareAuthUserPassDirective() {
        assertTrue(
            ProfileImportPolicy.declaresUserPasswordAuth(
                "client\nauth-user-pass\nremote example.com 1194\n",
            ),
        )
    }

    @Test
    fun detectsInlineAuthUserPassBlock() {
        assertTrue(
            ProfileImportPolicy.declaresUserPasswordAuth(
                "client\n<auth-user-pass>\nusername\npassword\n</auth-user-pass>\n",
            ),
        )
    }

    @Test
    fun detectsSetenvOptAuthUserPass() {
        assertTrue(ProfileImportPolicy.declaresUserPasswordAuth("client\nsetenv opt auth-user-pass\n"))
    }

    @Test
    fun ignoresCommentedAuthUserPass() {
        assertFalse(ProfileImportPolicy.declaresUserPasswordAuth("client\n#auth-user-pass\n"))
        assertFalse(ProfileImportPolicy.declaresUserPasswordAuth("client\n# auth-user-pass\n"))
    }

    @Test
    fun detectsExternalPkiMarkerForms() {
        assertTrue(ProfileImportPolicy.declaresExternalPki("client\nsetenv opt EXTERNAL_PKI 1\n"))
        assertTrue(ProfileImportPolicy.declaresExternalPki("client\nEXTERNAL_PKI 1\n"))
        assertTrue(ProfileImportPolicy.declaresExternalPki("client\nexternal_pki\n"))
    }

    @Test
    fun ignoresCommentedExternalPkiMarker() {
        assertFalse(ProfileImportPolicy.declaresExternalPki("client\n# EXTERNAL_PKI 1\n"))
        assertFalse(ProfileImportPolicy.declaresExternalPki("client\n#setenv opt EXTERNAL_PKI 1\n"))
    }

    @Test
    fun acceptsNcvpnUsernamePasswordProfile() {
        assertFalse(ProfileImportPolicy.declaresExternalPki(NCVPN_PROFILE))
        assertTrue(ProfileImportPolicy.declaresUserPasswordAuth(NCVPN_PROFILE))
    }

    @Test
    fun appendsClientCertDirectiveAndPreservesBody() {
        val adapted = ProfileImportPolicy.withoutClientCertificate(NCVPN_PROFILE)

        assertEquals(
            "$NCVPN_PROFILE\n${ProfileImportPolicy.CLIENT_CERT_DISABLED_DIRECTIVE}\n",
            adapted,
        )
        assertEquals(1, adapted.directiveLineCount(ProfileImportPolicy.CLIENT_CERT_DISABLED_DIRECTIVE))
    }

    @Test
    fun withoutClientCertificateIsIdempotent() {
        val once = ProfileImportPolicy.withoutClientCertificate(NCVPN_PROFILE)
        val twice = ProfileImportPolicy.withoutClientCertificate(once)

        assertEquals(once, twice)
        assertEquals(1, twice.directiveLineCount(ProfileImportPolicy.CLIENT_CERT_DISABLED_DIRECTIVE))
    }

    @Test
    fun leavesExistingClientCertDirectiveUntouched() {
        val config = "client\nsetenv CLIENT_CERT 0\nauth-user-pass\n"

        assertEquals(config, ProfileImportPolicy.withoutClientCertificate(config))
    }

    private fun String.directiveLineCount(directive: String): Int =
        lineSequence().count { it.trim().equals(directive, ignoreCase = true) }

    private companion object {
        /** Shaped like the NCVPN profiles: inline CA, UDP remotes, no client cert/key. */
        val NCVPN_PROFILE = """
            client
            dev tun
            proto udp
            remote dxb-c10.vpn.wlvpn.com 1194
            remote dxb-c11.vpn.wlvpn.com 1194
            remote dxb-c12.vpn.wlvpn.com 1194
            remote dxb-c20.vpn.wlvpn.com 1194
            remote-random
            resolv-retry infinite
            nobind
            persist-key
            persist-tun
            <ca>
            -----BEGIN CERTIFICATE-----
            MIIESjCCAzKgAwIBAgIJAKSqvk2CSdJGMA0GCSqGSIb3DQEBDQUAMHUxCzAJBgNV
            BAYTAlVTMQwwCgYDVQQIEwNWUE4xDDAKBgNVBAcTA1ZQTjEMMAoGA1UEChMDVlBO
            -----END CERTIFICATE-----
            </ca>
            verify-x509-name dxb-c name-prefix
            remote-cert-tls server
            auth-user-pass
            verb 3
            auth SHA256
            data-ciphers AES-256-GCM:AES-256-CBC
        """.trimIndent()
    }
}

class ProfileTlsCipherPolicyTest {
    @Test
    fun removesLegacyOnlyTlsCipherPin() {
        val config = """
            client
            remote sin-c07.vpn.wlvpn.com 1194
            tls-cipher TLS-DHE-RSA-WITH-AES-256-CBC-SHA:TLS-DHE-DSS-WITH-AES-256-CBC-SHA:TLS-RSA-WITH-AES-256-CBC-SHA
            auth-user-pass
        """.trimIndent()

        val result = ProfileImportPolicy.withNegotiableTlsCiphers(config)

        assertFalse(result.contains("tls-cipher"))
        assertTrue(result.contains("remote sin-c07.vpn.wlvpn.com 1194"))
        assertTrue(result.contains("auth-user-pass"))
    }

    @Test
    fun keepsAPinThatStillOffersAnEcdheSuite() {
        val config = "client\ntls-cipher TLS-DHE-RSA-WITH-AES-256-CBC-SHA:TLS-ECDHE-RSA-WITH-AES-256-GCM-SHA384\n"
        assertEquals(config, ProfileImportPolicy.withNegotiableTlsCiphers(config))
    }

    @Test
    fun ignoresCommentedTlsCipherLines() {
        val config = "client\n# tls-cipher TLS-DHE-RSA-WITH-AES-256-CBC-SHA\n"
        assertEquals(config, ProfileImportPolicy.withNegotiableTlsCiphers(config))
    }

    @Test
    fun leavesProfilesWithoutATlsCipherPinUntouched() {
        val config = "client\nremote example.com 1194\nauth-user-pass\n"
        assertEquals(config, ProfileImportPolicy.withNegotiableTlsCiphers(config))
    }

    @Test
    fun removingThePinIsIdempotent() {
        val config = "client\ntls-cipher TLS-RSA-WITH-AES-256-CBC-SHA\nremote example.com 1194\n"
        val once = ProfileImportPolicy.withNegotiableTlsCiphers(config)
        assertEquals(once, ProfileImportPolicy.withNegotiableTlsCiphers(once))
    }

    @Test
    fun removesOnlyLegacyPinsAndKeepsModernDirectives() {
        val config = """
            client
            tls-cipher TLS-RSA-WITH-AES-256-CBC-SHA
            tls-cipher TLS-ECDHE-RSA-WITH-AES-256-GCM-SHA384
            tls-ciphersuites TLS_AES_256_GCM_SHA384
        """.trimIndent()

        val result = ProfileImportPolicy.withNegotiableTlsCiphers(config)

        assertTrue(result.contains("TLS-ECDHE-RSA-WITH-AES-256-GCM-SHA384"))
        assertTrue(result.contains("tls-ciphersuites"))
        assertEquals(1, result.lines().count { it.trim().startsWith("tls-cipher ") })
    }
}

class ProfileServerCertificatePolicyTest {
    @Test
    fun addsRemoteCertTlsServerForCaBasedProfiles() {
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

        val result = ProfileImportPolicy.withServerCertificateUsage(config)

        assertTrue(result.contains("remote-cert-tls server"))
        assertEquals(1, result.lines().count { it.trim() == "remote-cert-tls server" })
    }

    @Test
    fun addingServerCertificateUsageIsIdempotent() {
        val config = "client\n<ca>\nMIIB\n</ca>\n"
        val once = ProfileImportPolicy.withServerCertificateUsage(config)
        assertEquals(once, ProfileImportPolicy.withServerCertificateUsage(once))
    }

    @Test
    fun keepsAnExistingRemoteCertTlsDirectiveUntouched() {
        val config = "client\n<ca>\nMIIB\n</ca>\nremote-cert-tls server\n"
        assertEquals(config, ProfileImportPolicy.withServerCertificateUsage(config))
    }

    @Test
    fun keepsExplicitRemoteCertEkuPolicyUntouched() {
        val config = "client\n<ca>\nMIIB\n</ca>\nremote-cert-eku \"TLS Web Server Authentication\"\n"
        assertEquals(config, ProfileImportPolicy.withServerCertificateUsage(config))
    }

    @Test
    fun keepsPeerFingerprintProfilesUntouched() {
        val config = "client\npeer-fingerprint AA:BB:CC\nremote example.com 1194\n"
        assertEquals(config, ProfileImportPolicy.withServerCertificateUsage(config))
    }

    @Test
    fun doesNotInventServerUsageWithoutCaOrPin() {
        val config = "client\nremote example.com 1194\nauth-user-pass\n"
        assertEquals(config, ProfileImportPolicy.withServerCertificateUsage(config))
    }

    @Test
    fun ignoresCommentedRemoteCertTlsLines() {
        val config = "client\n<ca>\nMIIB\n</ca>\n# remote-cert-tls server\n"
        val result = ProfileImportPolicy.withServerCertificateUsage(config)
        assertTrue(result.lines().any { it.trim() == "remote-cert-tls server" })
    }

    @Test
    fun keepsInlinePeerFingerprintBlocksUntouched() {
        val config = "client\n<ca>\nMIIB\n</ca>\n<peer-fingerprint>\n" +
            "AA:BB:CC\nDD:EE:FF\n</peer-fingerprint>\n"
        assertEquals(config, ProfileImportPolicy.withServerCertificateUsage(config))
    }
}
