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
