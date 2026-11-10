package com.smhasnan.openvpn.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Shared layout constants. Screens use these instead of ad-hoc padding values so
 * the vertical rhythm and corner radii stay consistent.
 */
object Dimens {
    /** Horizontal screen gutter. */
    val ScreenPadding = 20.dp

    /** Padding inside a [com.smhasnan.openvpn.ui.components.SectionCard]. */
    val CardPadding = 18.dp

    /** Gap between major sections on a scrolling screen. */
    val SectionGap = 22.dp

    /** Gap between cards inside one section. */
    val CardGap = 10.dp

    /** Corner radius for cards, sheets and hero surfaces. */
    val CardRadius = 20.dp

    /** Standard height for primary actions. */
    val PrimaryActionHeight = 54.dp
}
