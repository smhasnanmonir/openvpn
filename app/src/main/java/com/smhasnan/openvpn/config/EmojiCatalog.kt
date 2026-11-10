package com.smhasnan.openvpn.config

import java.text.BreakIterator
import java.util.Locale

/** Small, dependency-free emoji catalog used as a profile avatar. */
object EmojiCatalog {
    val choices = listOf(
        "🛡️", "🌐", "🔒", "🚀", "🌍", "🏠", "🏢", "⚡",
        "🌲", "🔥", "🧭", "🛰️", "🔐", "🦉", "🐧", "☁️",
        "📡", "🗺️", "🧱", "💼", "🎯", "🪐", "🍀", "🐳",
        "🦊", "🌙", "⭐", "🧩", "🔷", "🟢",
    )

    /** Enough UTF-16 units for a ZWJ sequence or two simple emoji. */
    private const val MAX_UTF16_LENGTH = 16
    private const val MAX_GRAPHEME_CLUSTERS = 2

    private const val ZERO_WIDTH_JOINER = 0x200D
    private const val REGIONAL_INDICATOR_FIRST = 0x1F1E6
    private const val REGIONAL_INDICATOR_LAST = 0x1F1FF
    private const val EMOJI_MODIFIER_FIRST = 0x1F3FB
    private const val EMOJI_MODIFIER_LAST = 0x1F3FF
    private const val TAG_FIRST = 0xE0020
    private const val TAG_LAST = 0xE007F

    /**
     * Returns [candidate] trimmed when it is a usable custom emoji, otherwise the catalog default.
     * Used on every persisted read/write path, so every previously stored catalog emoji must pass.
     */
    fun normalized(candidate: String?): String {
        val value = candidate?.trim().orEmpty()
        return if (isValidCustom(value)) value else choices.first()
    }

    fun defaultFor(seed: Int): String = choices[Math.floorMod(seed, choices.size)]

    /** True when [candidate] trims down to one or two emoji grapheme clusters. */
    fun isValidCustom(candidate: String?): Boolean {
        val value = candidate?.trim() ?: return false
        if (value.isEmpty() || value.length > MAX_UTF16_LENGTH) return false
        if (value.any { it.isISOControl() }) return false
        return clusterCount(value) in 1..MAX_GRAPHEME_CLUSTERS
    }

    /**
     * Counts extended grapheme clusters. The JVM [BreakIterator] uses legacy cluster rules that
     * split ZWJ sequences, skin-tone modifiers, flags and tag sequences into several clusters, so
     * those continuations are folded back into their base here. Android's ICU-backed
     * implementation already keeps them together, which makes the folding a no-op there.
     */
    private fun clusterCount(value: String): Int {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(value)
        iterator.first()

        var clusters = 0
        var previousEnd = -1
        var regionalIndicators = 0
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val start = if (previousEnd < 0) 0 else previousEnd
            val codePoint = Character.codePointAt(value, start)
            val joinsPrevious = previousEnd >= 0 &&
                continuesCluster(Character.codePointBefore(value, previousEnd), codePoint, regionalIndicators)
            if (joinsPrevious) {
                if (isRegionalIndicator(codePoint)) regionalIndicators++
            } else {
                clusters++
                regionalIndicators = if (isRegionalIndicator(codePoint)) 1 else 0
            }
            previousEnd = end
            end = iterator.next()
        }
        return clusters
    }

    private fun continuesCluster(previous: Int, codePoint: Int, regionalIndicators: Int): Boolean =
        previous == ZERO_WIDTH_JOINER ||
            codePoint == ZERO_WIDTH_JOINER ||
            codePoint in EMOJI_MODIFIER_FIRST..EMOJI_MODIFIER_LAST ||
            codePoint in TAG_FIRST..TAG_LAST ||
            pairsRegionalIndicator(previous, codePoint, regionalIndicators)

    /** Regional indicators only form a cluster in pairs, so a lone third one starts a new one. */
    private fun pairsRegionalIndicator(previous: Int, codePoint: Int, regionalIndicators: Int): Boolean =
        isRegionalIndicator(codePoint) &&
            isRegionalIndicator(previous) &&
            regionalIndicators % 2 == 1

    private fun isRegionalIndicator(codePoint: Int): Boolean =
        codePoint in REGIONAL_INDICATOR_FIRST..REGIONAL_INDICATOR_LAST
}
