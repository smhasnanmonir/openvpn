package com.smhasnan.openvpn.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smhasnan.openvpn.ui.theme.StatusAmber
import com.smhasnan.openvpn.ui.theme.StatusAmberLight
import com.smhasnan.openvpn.ui.theme.StatusBlue
import com.smhasnan.openvpn.ui.theme.StatusBlueLight
import com.smhasnan.openvpn.ui.theme.StatusGreen
import com.smhasnan.openvpn.ui.theme.StatusGreenLight
import com.smhasnan.openvpn.ui.theme.StatusRed
import com.smhasnan.openvpn.ui.theme.StatusRedLight
import com.smhasnan.openvpn.vpn.VpnState

@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 34.dp) {
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
            brush = Brush.verticalGradient(listOf(primary, primary.copy(alpha = 0.72f))),
        )
        val check = Path().apply {
            moveTo(w * 0.31f, h * 0.5f)
            lineTo(w * 0.44f, h * 0.63f)
            lineTo(w * 0.7f, h * 0.36f)
        }
        drawPath(
            path = check,
            color = container,
            style = Stroke(width = w * 0.08f, cap = StrokeCap.Round),
        )
    }
}

@Composable
fun ProfileAvatar(
    emoji: String,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    selected: Boolean = false,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                ),
            )
            .border(if (selected) 2.dp else 1.dp, borderColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = emoji,
            fontSize = (size.value * 0.44f).sp,
            lineHeight = (size.value * 0.5f).sp,
        )
    }
}

@Composable
fun StatusBadge(state: VpnState, label: String? = null, modifier: Modifier = Modifier) {
    val (foreground, container) = statusColors(state)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(foreground, CircleShape),
            )
            Text(
                text = label ?: statusLabel(state),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = foreground,
            )
        }
    }
}

@Composable
fun statusColors(state: VpnState): Pair<Color, Color> {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when (state) {
        VpnState.CONNECTED -> (if (dark) StatusGreenLight else StatusGreen) to
            (if (dark) Color(0xFF103D2F) else Color(0xFFD9F4E9))
        VpnState.CONNECTING, VpnState.PREPARING, VpnState.RECONNECTING, VpnState.DISCONNECTING ->
            (if (dark) StatusAmberLight else StatusAmber) to
                (if (dark) Color(0xFF3D2A08) else Color(0xFFFFEBCB))
        VpnState.AUTH_FAILED, VpnState.ERROR -> (if (dark) StatusRedLight else StatusRed) to
            (if (dark) Color(0xFF3F0B08) else Color(0xFFFFDAD6))
        VpnState.NEEDS_CHALLENGE -> (if (dark) StatusBlueLight else StatusBlue) to
            (if (dark) Color(0xFF10284A) else Color(0xFFDCE8FF))
        VpnState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant to
            MaterialTheme.colorScheme.surfaceVariant
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

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(size = 26.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
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
        if (action != null) action()
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            content()
        }
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
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(22.dp))
            Button(onClick = onAction) {
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
    val colorScheme = MaterialTheme.colorScheme
    val container = if (isError) colorScheme.errorContainer else colorScheme.secondaryContainer
    val content = if (isError) colorScheme.onErrorContainer else colorScheme.onSecondaryContainer
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isError) Icons.Filled.Warning else Icons.Filled.Info,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = content,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = content,
                        contentColor = container,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.height(34.dp),
                ) {
                    Text(actionLabel, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = content, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
fun SelectedCheck(selected: Boolean, modifier: Modifier = Modifier) {
    if (selected) {
        Box(
            modifier = modifier
                .size(24.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Selected profile",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
fun SecondaryText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
fun DividerLine(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    )
}

private fun Color.luminance(): Float {
    fun channel(value: Float): Float =
        if (value <= 0.03928f) value / 12.92f else ((value + 0.055f) / 1.055f).let { it * it * it }
    return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
}
