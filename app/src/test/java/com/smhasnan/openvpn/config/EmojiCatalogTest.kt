package com.smhasnan.openvpn.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiCatalogTest {
    @Test
    fun fallsBackToTheFirstCatalogChoiceForMissingValues() {
        val fallback = EmojiCatalog.choices.first()
        assertEquals(fallback, EmojiCatalog.normalized(null))
        assertEquals(fallback, EmojiCatalog.normalized(""))
        assertEquals(fallback, EmojiCatalog.normalized("   "))
        assertFalse(EmojiCatalog.isValidCustom(null))
        assertFalse(EmojiCatalog.isValidCustom("   "))
    }

    @Test
    fun keepsEveryCatalogEmoji() {
        EmojiCatalog.choices.forEach { emoji ->
            assertEquals(emoji, EmojiCatalog.normalized(emoji))
            assertTrue(emoji, EmojiCatalog.isValidCustom(emoji))
        }
    }

    @Test
    fun acceptsZwjFamilyEmoji() {
        assertTrue(EmojiCatalog.isValidCustom(FAMILY))
        assertEquals(FAMILY, EmojiCatalog.normalized(FAMILY))
    }

    @Test
    fun acceptsFlagEmoji() {
        assertTrue(EmojiCatalog.isValidCustom(FLAG_BANGLADESH))
        assertEquals(FLAG_BANGLADESH, EmojiCatalog.normalized(FLAG_BANGLADESH))
    }

    @Test
    fun acceptsTwoSimpleEmoji() {
        assertTrue(EmojiCatalog.isValidCustom("🚀🔥"))
        assertEquals("🚀🔥", EmojiCatalog.normalized("🚀🔥"))
    }

    @Test
    fun acceptsKeycapEmoji() {
        assertTrue(EmojiCatalog.isValidCustom(KEYCAP_ONE))
        assertEquals(KEYCAP_ONE, EmojiCatalog.normalized(KEYCAP_ONE))
        assertEquals(KEYCAP_HASH, EmojiCatalog.normalized(KEYCAP_HASH))
    }

    @Test
    fun keepsCustomEmojiInsteadOfFallingBackToTheCatalog() {
        assertEquals("🦄", EmojiCatalog.normalized("🦄"))
        assertEquals(FAMILY, EmojiCatalog.normalized(FAMILY))
        assertEquals(KEYCAP_ONE, EmojiCatalog.normalized(KEYCAP_ONE))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertTrue(EmojiCatalog.isValidCustom("\t🚀 "))
        assertEquals("🚀", EmojiCatalog.normalized("  🚀  "))
    }

    @Test
    fun rejectsBlankAndControlCharacters() {
        val fallback = EmojiCatalog.choices.first()
        assertFalse(EmojiCatalog.isValidCustom(""))
        assertFalse(EmojiCatalog.isValidCustom("🚀\n🔥"))
        assertFalse(EmojiCatalog.isValidCustom("🚀\u0000🔥"))
        assertEquals(fallback, EmojiCatalog.normalized("🚀\n🔥"))
        assertEquals(fallback, EmojiCatalog.normalized("🚀\u0000🔥"))
    }

    @Test
    fun rejectsMoreThanTwoClusters() {
        val fallback = EmojiCatalog.choices.first()
        assertFalse(EmojiCatalog.isValidCustom("🚀🔥🌙"))
        assertEquals(fallback, EmojiCatalog.normalized("🚀🔥🌙"))
    }

    @Test
    fun rejectsValuesLongerThanTheUtf16Cap() {
        val fallback = EmojiCatalog.choices.first()
        val tooLong = "🚀".repeat(9)
        assertFalse(EmojiCatalog.isValidCustom(tooLong))
        assertEquals(fallback, EmojiCatalog.normalized(tooLong))
        assertFalse(EmojiCatalog.isValidCustom("a".repeat(17)))
    }

    @Test
    fun rejectsPlainText() {
        val fallback = EmojiCatalog.choices.first()
        listOf("A", "AB", "hello", "12", "#").forEach { text ->
            assertFalse(text, EmojiCatalog.isValidCustom(text))
            assertEquals(text, fallback, EmojiCatalog.normalized(text))
        }
    }

    @Test
    fun rejectsTextMixedWithEmoji() {
        val fallback = EmojiCatalog.choices.first()
        listOf("A🛡️", "🛡️A", "hi🚀", "🚀!").forEach { value ->
            assertFalse(value, EmojiCatalog.isValidCustom(value))
            assertEquals(value, fallback, EmojiCatalog.normalized(value))
        }
    }

    @Test
    fun defaultForIsStableAndWraps() {
        val choices = EmojiCatalog.choices
        assertEquals(choices[0], EmojiCatalog.defaultFor(0))
        assertEquals(choices[3], EmojiCatalog.defaultFor(3))
        assertEquals(choices[3], EmojiCatalog.defaultFor(3 + choices.size))
        assertEquals(choices.last(), EmojiCatalog.defaultFor(-1))
        assertEquals(choices.last(), EmojiCatalog.defaultFor(choices.size - 1))
    }

    private companion object {
        const val FAMILY = "👨‍👩‍👧‍👦"
        const val FLAG_BANGLADESH = "🇧🇩"
        const val KEYCAP_ONE = "1️⃣"
        const val KEYCAP_HASH = "#️⃣"
    }
}
