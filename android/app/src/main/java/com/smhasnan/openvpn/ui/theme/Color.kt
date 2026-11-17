package com.smhasnan.openvpn.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Brand and hero palette
//
// "Playful garden": a vivid spring-green hero with a fresh mint accent, warm
// amber and coral support tones. The hero owns the attention; everything else
// stays calm and neutral.
// ---------------------------------------------------------------------------

val HeroTop = Color(0xFF0D4034)
val HeroBottom = Color(0xFF12996B)
val HeroAccent = Color(0xFF9BF0C7)
val HeroText = Color(0xFFF4FFF8)
val HeroMuted = Color(0xFFBCE3CF)
val HeroLine = Color(0xFF2E6B55)
val HeroDanger = Color(0xFFFF9F8A)

// ---------------------------------------------------------------------------
// Light neutrals
// ---------------------------------------------------------------------------

val Paper = Color(0xFFF7F9F4)
val Cloud = Color(0xFFFFFFFF)
val Ink = Color(0xFF11241E)
val InkMuted = Color(0xFF587268)
val Line = Color(0xFFE4ECE6)
val LineStrong = Color(0xFFC8D9CF)
val Emerald = Color(0xFF0E8F62)
val EmeraldSoft = Color(0xFFC9F2DE)
val EmeraldInk = Color(0xFF06402F)

// ---------------------------------------------------------------------------
// Dark neutrals
// ---------------------------------------------------------------------------

val Night = Color(0xFF071410)
val NightSurface = Color(0xFF0D1B16)
val NightRaised = Color(0xFF122119)
val NightHigh = Color(0xFF192B24)
val NightLine = Color(0xFF24382E)
val Mist = Color(0xFFEAF7F0)
val MistMuted = Color(0xFF9FBBAE)
val Mint = Color(0xFF7CF0BF)
val MintInk = Color(0xFF03291C)
val MintContainer = Color(0xFF10513C)
val MintOnContainer = Color(0xFFDDFBEC)

// ---------------------------------------------------------------------------
// Status colors (kept stable; used by badges, log rows and banners)
// ---------------------------------------------------------------------------

val StatusGreen = Color(0xFF189A6C)
val StatusGreenLight = Color(0xFF6FE3C0)
val StatusAmber = Color(0xFF9A5F00)
val StatusAmberLight = Color(0xFFFFC266)
val StatusRed = Color(0xFFB3261E)
val StatusRedLight = Color(0xFFFFB4AB)
val StatusBlue = Color(0xFF2E6FD8)
val StatusBlueLight = Color(0xFF9EC3FF)

// Darker foreground variants for small uppercase labels on tinted containers in
// the light theme (WCAG AA for small text, unlike the base status colors).
val StatusGreenInk = Color(0xFF0B5D45)
val StatusAmberInk = Color(0xFF7A4A00)
val StatusRedInk = Color(0xFF9B1C15)
val StatusBlueInk = Color(0xFF1E40AF)

// ---------------------------------------------------------------------------
// Legacy aliases kept so older references keep compiling.
// ---------------------------------------------------------------------------

val DeepInk = Night
val DeepForest = Emerald
val MintBright = HeroAccent
val Sand = Paper
val Slate = InkMuted
val Graphite = NightSurface
val Charcoal = NightRaised
