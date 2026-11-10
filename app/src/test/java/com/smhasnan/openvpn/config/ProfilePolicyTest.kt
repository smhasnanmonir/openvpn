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

class EmojiCatalogTest {
    @Test
    fun normalizesUnknownValues() {
        assertEquals(EmojiCatalog.choices.first(), EmojiCatalog.normalized(null))
        assertEquals(EmojiCatalog.choices.first(), EmojiCatalog.normalized(""))
    }

    @Test
    fun keepsKnownEmoji() {
        assertEquals("🛡️", EmojiCatalog.normalized("🛡️"))
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
