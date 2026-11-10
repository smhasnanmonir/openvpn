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
 * Light theme: warm paper background, white content cards, deep emerald accent.
 * Surfaces carry the layering (there are almost no visible borders), which is
 * what keeps the screens calm instead of a stack of outlined boxes.
 */
private val LightColors = lightColorScheme(
    primary = Emerald,
    onPrimary = Color.White,
    primaryContainer = EmeraldSoft,
    onPrimaryContainer = EmeraldInk,
    secondary = Color(0xFF48645B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE9E3),
    onSecondaryContainer = Color(0xFF10281F),
    tertiary = Color(0xFF7A5B2E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF4E4C6),
    onTertiaryContainer = Color(0xFF3A2A0F),
    background = Paper,
    onBackground = Ink,
    surface = Cloud,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDF2EE),
    onSurfaceVariant = InkMuted,
    surfaceTint = Emerald,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9FBF9),
    surfaceContainer = Color(0xFFF1F4F1),
    surfaceContainerHigh = Color(0xFFEAEFEB),
    surfaceContainerHighest = Color(0xFFE3E9E4),
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
 * Dark theme: near-black green background with slightly raised panels and a
 * mint accent. Kept low-contrast in the surfaces so the hero card and status
 * colors remain legible focal points.
 */
private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = MintInk,
    primaryContainer = MintContainer,
    onPrimaryContainer = MintOnContainer,
    secondary = Color(0xFFA9C8BC),
    onSecondary = Color(0xFF14332A),
    secondaryContainer = Color(0xFF1E3B32),
    onSecondaryContainer = Color(0xFFD5E8E0),
    tertiary = Color(0xFFE3C88C),
    onTertiary = Color(0xFF3B2C10),
    tertiaryContainer = Color(0xFF4A3A16),
    onTertiaryContainer = Color(0xFFF7E4BC),
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
