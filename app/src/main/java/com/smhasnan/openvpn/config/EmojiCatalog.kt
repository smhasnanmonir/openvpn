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
    private const val VARIATION_SELECTOR_16 = '\uFE0F'
    private const val KEYCAP_MARK = '\u20E3'
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
        val clusters = clusterRanges(value)
        return clusters.size in 1..MAX_GRAPHEME_CLUSTERS && clusters.all { isEmojiCluster(value, it) }
    }

    /** Number of folded extended grapheme clusters; the avatar renderer scales by this. */
    fun clusterCount(value: String): Int = clusterRanges(value).size

    /**
     * Splits [value] into extended grapheme clusters. The JVM [BreakIterator] uses legacy cluster
     * rules that split ZWJ sequences, skin-tone modifiers, flags and tag sequences into several
     * clusters, so those continuations are folded back into their base here. Android's ICU-backed
     * implementation already keeps them together, which makes the folding a no-op there.
     */
    private fun clusterRanges(value: String): List<IntRange> {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(value)
        iterator.first()

        val starts = mutableListOf<Int>()
        var previousEnd = -1
        var regionalIndicators = 0
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val start = if (previousEnd < 0) 0 else previousEnd
            val codePoint = Character.codePointAt(value, start)
            val joinsPrevious = previousEnd >= 0 &&
                continuesCluster(Character.codePointBefore(value, previousEnd), codePoint, regionalIndicators)
            if (previousEnd < 0 || !joinsPrevious) {
                starts.add(start)
                regionalIndicators = if (isRegionalIndicator(codePoint)) 1 else 0
            } else if (isRegionalIndicator(codePoint)) {
                regionalIndicators++
            }
            previousEnd = end
            end = iterator.next()
        }
        return starts.mapIndexed { index, start ->
            start until (starts.getOrNull(index + 1) ?: value.length)
        }
    }

    /**
     * Requires the cluster to start with an emoji code point or a keycap sequence; continuations
     * such as ZWJ, variation selectors, skin tones and tag characters are allowed after that base.
     */
    private fun isEmojiCluster(value: String, cluster: IntRange): Boolean {
        val base = Character.codePointAt(value, cluster.first)
        if (isEmojiBase(base)) return true
        if (!isKeycapBase(base)) return false
        var index = cluster.first + 1
        if (index <= cluster.last && value[index] == VARIATION_SELECTOR_16) index++
        return index == cluster.last && value[index] == KEYCAP_MARK
    }

    /** Regional indicators (U+1F1E6–U+1F1FF) are already inside the main pictograph range. */
    private fun isEmojiBase(codePoint: Int): Boolean =
        codePoint in 0x1F000..0x1FAFF ||
            codePoint in 0x2600..0x27BF ||
            codePoint in 0x2B00..0x2BFF ||
            codePoint in 0x2190..0x21FF ||
            codePoint in 0x2300..0x23FF

    private fun isKeycapBase(codePoint: Int): Boolean =
        codePoint == '#'.code || codePoint == '*'.code || codePoint in '0'.code..'9'.code

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
