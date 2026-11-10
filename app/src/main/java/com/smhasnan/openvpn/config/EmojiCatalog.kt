package com.smhasnan.openvpn.config

/** Small, dependency-free emoji catalog used as a profile avatar. */
object EmojiCatalog {
    val choices = listOf(
        "🛡️", "🌐", "🔒", "🚀", "🌍", "🏠", "🏢", "⚡",
        "🌲", "🔥", "🧭", "🛰️", "🔐", "🦉", "🐧", "☁️",
        "📡", "🗺️", "🧱", "💼", "🎯", "🪐", "🍀", "🐳",
        "🦊", "🌙", "⭐", "🧩", "🔷", "🟢",
    )

    fun normalized(candidate: String?): String =
        if (candidate.isNullOrBlank() || candidate.length > 8) choices.first() else candidate

    fun defaultFor(seed: Int): String = choices[Math.floorMod(seed, choices.size)]
}
