package com.smhasnan.openvpn.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smhasnan.openvpn.config.EmojiCatalog
import com.smhasnan.openvpn.ui.theme.Dimens
import com.smhasnan.openvpn.ui.theme.StatusAmberInk
import com.smhasnan.openvpn.ui.theme.StatusAmberLight
import com.smhasnan.openvpn.ui.theme.StatusBlueInk
import com.smhasnan.openvpn.ui.theme.StatusBlueLight
import com.smhasnan.openvpn.ui.theme.StatusGreenInk
import com.smhasnan.openvpn.ui.theme.StatusGreenLight
import com.smhasnan.openvpn.ui.theme.StatusRedInk
import com.smhasnan.openvpn.ui.theme.StatusRedLight
import com.smhasnan.openvpn.vpn.VpnState

// ---------------------------------------------------------------------------
// Brand
// ---------------------------------------------------------------------------

@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 26.dp) {
    val primary = MaterialTheme.colorScheme.primary
    val container = MaterialTheme.colorScheme.primaryContainer
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val shield = Path().apply {
            moveTo(w * 0.5f, h * 0.04f)
            lineTo(w * 0.9f, h * 0.22f)
            lineTo(w * 0.9f, h * 0.5f)
            cubicTo(w * 0.9f, h * 0.76f, w * 0.72f, h * 0.9f, w * 0.5f, h * 0.98f)
            cubicTo(w * 0.28f, h * 0.9f, w * 0.1f, h * 0.76f, w * 0.1f, h * 0.5f)
            lineTo(w * 0.1f, h * 0.22f)
            close()
        }
        drawPath(
            path = shield,
            brush = Brush.verticalGradient(listOf(primary, primary.copy(alpha = 0.7f))),
        )
        val check = Path().apply {
            moveTo(w * 0.31f, h * 0.5f)
            lineTo(w * 0.44f, h * 0.63f)
            lineTo(w * 0.7f, h * 0.36f)
        }
        drawPath(
            path = check,
            color = container,
            style = Stroke(width = w * 0.085f, cap = StrokeCap.Round),
        )
    }
}

@Composable
fun ProfileAvatar(
    emoji: String,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    selected: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val borderColor = if (selected) scheme.primary else scheme.outlineVariant.copy(alpha = 0.7f)
    // A custom icon may use two emoji; render those slightly smaller and on one
    // line so they stay inside the circle instead of wrapping or clipping.
    val clusters = EmojiCatalog.clusterCount(emoji).coerceIn(1, 2)
    val emojiScale = if (clusters > 1) 0.30f else 0.42f
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(scheme.surfaceContainerHigh, scheme.surfaceContainer),
                    start = Offset.Zero,
                    end = Offset(160f, 160f),
                ),
            )
            .border(if (selected) 2.dp else 1.dp, borderColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = emoji,
            fontSize = (size.value * emojiScale).sp,
            lineHeight = (size.value * (emojiScale + 0.06f)).sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

// ---------------------------------------------------------------------------
// Headers and sections
// ---------------------------------------------------------------------------

/**
 * Screen-level header: optional uppercase eyebrow, a title with the brand mark,
 * an optional supporting line, and an optional trailing action.
 */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (eyebrow != null) {
            Text(
                text = eyebrow.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(size = 22.dp)
            Spacer(Modifier.width(9.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            if (action != null) {
                Spacer(Modifier.width(12.dp))
                action()
            }
        }
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Small uppercase label that introduces a group of cards. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

/**
 * Neutral content card. Layering comes from the tonal surface color; a border is
 * only added when a caller explicitly needs one.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(Dimens.CardPadding),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    border: BorderStroke? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.CardRadius),
        color = containerColor,
        border = border,
    ) {
        Column(modifier = Modifier.padding(contentPadding)) { content() }
    }
}

// ---------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------

/**
 * Key/value line used by diagnostics and connection details.
 *
 * The label keeps its intrinsic width and the value owns the remaining space
 * with right alignment and wrapping. On very narrow layouts the row collapses
 * into a stacked label/value pair so long values (the OpenVPN platform string,
 * the core revision, the app version) never break mid-token.
 */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    monospace: Boolean = false,
    maxValueLines: Int = 2,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val stacked = maxWidth < 280.dp
        if (stacked) {
            Column(modifier = Modifier.padding(vertical = 9.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.SansSerif,
                    color = valueColor,
                    maxLines = maxValueLines + 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Row(
                modifier = Modifier.padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 88.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.SansSerif,
                    color = valueColor,
                    textAlign = TextAlign.End,
                    maxLines = maxValueLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** A switch setting with a title and optional supporting text. */
@Composable
fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** A row that opens another destination, with an optional trailing label. */
@Composable
fun LinkRow(
    title: String,
    subtitle: String? = null,
    trailingLabel: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailingLabel != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = trailingLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun DividerLine(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    )
}

// ---------------------------------------------------------------------------
// Status and controls
// ---------------------------------------------------------------------------

@Composable
fun StatusBadge(state: VpnState, label: String? = null, modifier: Modifier = Modifier) {
    val (foreground, container) = statusColors(state)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(foreground, CircleShape),
            )
            Text(
                text = (label ?: statusLabel(state)).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = foreground,
            )
        }
    }
}

@Composable
fun statusColors(state: VpnState): Pair<Color, Color> {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when (state) {
        VpnState.CONNECTED -> (if (dark) StatusGreenLight else StatusGreenInk) to
            (if (dark) Color(0xFF0F3B2D) else Color(0xFFDCF3E9))
        VpnState.CONNECTING, VpnState.PREPARING, VpnState.RECONNECTING, VpnState.DISCONNECTING ->
            (if (dark) StatusAmberLight else StatusAmberInk) to
                (if (dark) Color(0xFF3A2A0C) else Color(0xFFFBEBC9))
        VpnState.AUTH_FAILED, VpnState.ERROR -> (if (dark) StatusRedLight else StatusRedInk) to
            (if (dark) Color(0xFF4A1210) else Color(0xFFFADAD6))
        VpnState.NEEDS_CHALLENGE -> (if (dark) StatusBlueLight else StatusBlueInk) to
            (if (dark) Color(0xFF122744) else Color(0xFFDCE8FF))
        VpnState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant to
            MaterialTheme.colorScheme.surfaceContainerHigh
    }
}

private fun statusLabel(state: VpnState): String = when (state) {
    VpnState.DISCONNECTED -> "Not connected"
    VpnState.PREPARING -> "Preparing"
    VpnState.CONNECTING -> "Connecting"
    VpnState.CONNECTED -> "Connected"
    VpnState.RECONNECTING -> "Reconnecting"
    VpnState.DISCONNECTING -> "Disconnecting"
    VpnState.NEEDS_CHALLENGE -> "Action required"
    VpnState.AUTH_FAILED -> "Authentication failed"
    VpnState.ERROR -> "Connection error"
}

/** Equal-width segmented control for small option sets such as the theme mode. */
@Composable
fun <T> SegmentedOptions(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainer)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Surface(
                onClick = { onSelect(option) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(11.dp),
                color = if (isSelected) scheme.primary else Color.Transparent,
            ) {
                Box(
                    modifier = Modifier.padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) scheme.onPrimary else scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Compact pill used for profile traits such as the authentication mode. */
@Composable
fun MetaChip(text: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (emphasized) scheme.primaryContainer else scheme.surfaceContainerHigh,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (emphasized) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun SelectedCheck(selected: Boolean, modifier: Modifier = Modifier) {
    if (selected) {
        Box(
            modifier = modifier
                .size(22.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Selected profile",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Statistics and feedback
// ---------------------------------------------------------------------------

@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun EmptyState(
    icon: @Composable () -> Unit,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    iconBackground: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 26.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (iconBackground) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(scheme.primaryContainer.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
        } else {
            icon()
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            Button(onClick = onAction, shape = RoundedCornerShape(14.dp)) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun MessageBanner(
    message: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (isError) scheme.errorContainer else scheme.secondaryContainer
    val content = if (isError) scheme.onErrorContainer else scheme.onSecondaryContainer
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isError) Icons.Filled.Warning else Icons.Filled.Info,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = content,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = content,
                    )
                }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Dismiss",
                        tint = content,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun <T> FilterPillRow(
    options: List<T>,
    selected: T?,
    label: (T?) -> String,
    onSelect: (T?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val scheme = MaterialTheme.colorScheme
            Surface(
                onClick = { onSelect(option) },
                shape = RoundedCornerShape(50),
                color = if (isSelected) scheme.primary else scheme.surfaceContainer,
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) scheme.onPrimary else scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                )
            }
        }
    }
}

private fun Color.luminance(): Float {
    fun channel(value: Float): Float =
        if (value <= 0.03928f) value / 12.92f else ((value + 0.055f) / 1.055f).let { it * it * it }
    return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
}
