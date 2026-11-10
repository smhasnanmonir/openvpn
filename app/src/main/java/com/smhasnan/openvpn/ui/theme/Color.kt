package com.smhasnan.openvpn.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Brand and hero palette
//
// The connection hero keeps the brand identity in both themes: a deep forest
// gradient with a mint accent. Everything else is neutral so the hero owns the
// attention.
// ---------------------------------------------------------------------------

val HeroTop = Color(0xFF0A322A)
val HeroBottom = Color(0xFF0F5A46)
val HeroAccent = Color(0xFF7BEBC8)
val HeroText = Color(0xFFF3FBF7)
val HeroMuted = Color(0xFFA6CFC1)
val HeroLine = Color(0xFF2A5A4C)
val HeroDanger = Color(0xFFFF8F84)

// ---------------------------------------------------------------------------
// Light neutrals
// ---------------------------------------------------------------------------

val Paper = Color(0xFFF4F6F4)
val Cloud = Color(0xFFFFFFFF)
val Ink = Color(0xFF10211C)
val InkMuted = Color(0xFF5A6C65)
val Line = Color(0xFFE2E9E4)
val LineStrong = Color(0xFFC9D6CF)
val Emerald = Color(0xFF0C5B47)
val EmeraldSoft = Color(0xFFC6EDE0)
val EmeraldInk = Color(0xFF063B2E)

// ---------------------------------------------------------------------------
// Dark neutrals
// ---------------------------------------------------------------------------

val Night = Color(0xFF060D0B)
val NightSurface = Color(0xFF0C1613)
val NightRaised = Color(0xFF111E1A)
val NightHigh = Color(0xFF182822)
val NightLine = Color(0xFF22352E)
val Mist = Color(0xFFE7F3EE)
val MistMuted = Color(0xFF9DB5AA)
val Mint = Color(0xFF7BE7C6)
val MintInk = Color(0xFF00251B)
val MintContainer = Color(0xFF0F4A3B)
val MintOnContainer = Color(0xFFD9F8EC)

// ---------------------------------------------------------------------------
// Status colors (kept stable; used by badges, log rows and banners)
// ---------------------------------------------------------------------------

val StatusGreen = Color(0xFF1B8F6B)
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
