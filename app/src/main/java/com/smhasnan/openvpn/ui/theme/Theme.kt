package com.smhasnan.openvpn.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.smhasnan.openvpn.settings.ThemeMode

/**
 * Light theme: warm garden background, white content cards, vivid spring-green
 * primary, warm amber and coral support tones. Surfaces carry the layering
 * (there are almost no visible borders), which keeps the screens calm instead
 * of a stack of outlined boxes.
 */
private val LightColors = lightColorScheme(
    primary = Emerald,
    onPrimary = Color.White,
    primaryContainer = EmeraldSoft,
    onPrimaryContainer = EmeraldInk,
    secondary = Color(0xFF7C5A12),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE9C2),
    onSecondaryContainer = Color(0xFF2E2000),
    tertiary = Color(0xFFBB4E3B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDAD2),
    onTertiaryContainer = Color(0xFF3A0B03),
    background = Paper,
    onBackground = Ink,
    surface = Cloud,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEEF2EC),
    onSurfaceVariant = InkMuted,
    surfaceTint = Emerald,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFBF8),
    surfaceContainer = Color(0xFFF2F5EE),
    surfaceContainerHigh = Color(0xFFEBF0E7),
    surfaceContainerHighest = Color(0xFFE4E9E0),
    outline = LineStrong,
    outlineVariant = Line,
    error = StatusRed,
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Ink,
    inverseOnSurface = Paper,
    inversePrimary = Mint,
    scrim = Color.Black,
)

/**
 * Dark theme: deep green-black background with raised panels and a fresh mint
 * accent; amber and coral support tones keep the dark surfaces friendly.
 * Kept low-contrast in the surfaces so the hero card and status colors remain
 * legible focal points.
 */
private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = MintInk,
    primaryContainer = MintContainer,
    onPrimaryContainer = MintOnContainer,
    secondary = Color(0xFFECC98F),
    onSecondary = Color(0xFF3E2D0A),
    secondaryContainer = Color(0xFF57421F),
    onSecondaryContainer = Color(0xFFFFE9C2),
    tertiary = Color(0xFFFFB4A6),
    onTertiary = Color(0xFF571608),
    tertiaryContainer = Color(0xFF67291B),
    onTertiaryContainer = Color(0xFFFFDAD2),
    background = Night,
    onBackground = Mist,
    surface = NightSurface,
    onSurface = Mist,
    surfaceVariant = NightRaised,
    onSurfaceVariant = MistMuted,
    surfaceTint = Mint,
    surfaceContainerLowest = Color(0xFF040A08),
    surfaceContainerLow = NightSurface,
    surfaceContainer = NightRaised,
    surfaceContainerHigh = NightHigh,
    surfaceContainerHighest = Color(0xFF1E312A),
    outline = Color(0xFF5B7268),
    outlineVariant = NightLine,
    error = StatusRedLight,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF6E1A14),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Mist,
    inverseOnSurface = Ink,
    inversePrimary = Emerald,
    scrim = Color.Black,
)

@Composable
fun OpenVpnTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
