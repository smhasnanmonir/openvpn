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

private val LightColors = lightColorScheme(
    primary = DeepForest,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2F5E9),
    onPrimaryContainer = Color(0xFF002B23),
    secondary = Color(0xFF3F6659),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E8DF),
    onSecondaryContainer = Color(0xFF102B22),
    tertiary = Color(0xFF5A6042),
    onTertiary = Color.White,
    background = Sand,
    onBackground = Color(0xFF15211D),
    surface = Cloud,
    onSurface = Color(0xFF15211D),
    surfaceVariant = Mist,
    onSurfaceVariant = Slate,
    outline = Color(0xFFA9BAB2),
    outlineVariant = Color(0xFFD5E1DB),
    error = StatusRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF0B4F41),
    onPrimaryContainer = Color(0xFFD2F5E9),
    secondary = Color(0xFFB5D6C9),
    onSecondary = Color(0xFF20382F),
    secondaryContainer = Color(0xFF2C4A3F),
    onSecondaryContainer = Color(0xFFD5E8DF),
    tertiary = Color(0xFFC4C9A5),
    onTertiary = Color(0xFF2D321C),
    background = DeepInk,
    onBackground = Color(0xFFE3EFEA),
    surface = Graphite,
    onSurface = Color(0xFFE3EFEA),
    surfaceVariant = Charcoal,
    onSurfaceVariant = Color(0xFFB8C9C1),
    outline = Color(0xFF6F857C),
    outlineVariant = Color(0xFF2D4239),
    error = StatusRedLight,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
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
