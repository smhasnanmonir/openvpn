package com.smhasnan.openvpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smhasnan.openvpn.ui.components.EmptyState
import com.smhasnan.openvpn.ui.components.FilterPillRow
import com.smhasnan.openvpn.ui.components.ProfileAvatar
import com.smhasnan.openvpn.ui.components.ScreenHeader
import com.smhasnan.openvpn.ui.components.SectionCard
import com.smhasnan.openvpn.ui.theme.Dimens
import com.smhasnan.openvpn.ui.theme.StatusAmber
import com.smhasnan.openvpn.ui.theme.StatusAmberLight
import com.smhasnan.openvpn.ui.theme.StatusRed
import com.smhasnan.openvpn.ui.theme.StatusRedLight
import com.smhasnan.openvpn.vpn.LogEntry
import com.smhasnan.openvpn.vpn.LogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.Flow

@Composable
fun LogsScreen(
    logs: Flow<List<LogEntry>>,
    onClear: () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val entries by logs.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf<LogLevel?>(null) }
    val filtered = remember(entries, filter) {
        if (filter == null) entries else entries.filter { it.level == filter }
    }
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    LaunchedEffect(filtered.size) {
        val visible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (filtered.isNotEmpty() && visible >= filtered.size - 3) {
            listState.animateScrollToItem(filtered.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = Dimens.ScreenPadding, end = Dimens.ScreenPadding, top = 14.dp),
    ) {
        ScreenHeader(
            eyebrow = "Engine",
            title = "Diagnostics",
            subtitle = "Sanitized engine and service events",
            action = {
                if (entries.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            copyLogs(context, filtered, timeFormat)
                            onMessage("Sanitized logs copied")
                        }) {
                            Text("Copy")
                        }
                        IconButton(onClick = onClear) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Clear diagnostics",
                            )
                        }
                    }
                }
            },
        )
        Spacer(Modifier.height(14.dp))
        FilterPillRow(
            options = listOf<LogLevel?>(null, LogLevel.WARN, LogLevel.ERROR),
            selected = filter,
            label = { level ->
                when (level) {
                    LogLevel.WARN -> "Warnings"
                    LogLevel.ERROR -> "Errors"
                    else -> "All"
                }
            },
            onSelect = { filter = it },
        )
        Spacer(Modifier.height(14.dp))
        if (entries.isEmpty()) {
            SectionCard(modifier = Modifier.fillMaxWidth()) {
                EmptyState(
                    icon = { ProfileAvatar(emoji = "📋", size = 52.dp) },
                    title = "No events yet",
                    message = "Connection and engine events appear here. Passwords, keys, and profile contents are never logged.",
                    iconBackground = false,
                )
            }
        } else if (filtered.isEmpty()) {
            SectionCard(modifier = Modifier.fillMaxWidth()) {
                EmptyState(
                    icon = { ProfileAvatar(emoji = "✅", size = 52.dp) },
                    title = "Nothing in this filter",
                    message = "There are no ${filter?.name?.lowercase() ?: ""} events yet.",
                    actionLabel = "Show all",
                    onAction = { filter = null },
                    iconBackground = false,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(filtered) { entry ->
                    LogRow(entry, timeFormat)
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry, timeFormat: SimpleDateFormat) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminanceCompat() < 0.5f
    val accent = when (entry.level) {
        LogLevel.INFO -> scheme.onSurfaceVariant
        LogLevel.WARN -> if (dark) StatusAmberLight else StatusAmber
        LogLevel.ERROR -> if (dark) StatusRedLight else StatusRed
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surface)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accent, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = timeFormat.format(Date(entry.timestampMillis)),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = entry.level.name.take(4),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = accent,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun copyLogs(context: Context, logs: List<LogEntry>, format: SimpleDateFormat) {
    val text = logs.joinToString("\n") { entry ->
        "${format.format(Date(entry.timestampMillis))} ${entry.level.name} ${entry.message}"
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Sanitized VPN diagnostics", text))
}

private fun androidx.compose.ui.graphics.Color.luminanceCompat(): Float {
    fun channel(value: Float): Float =
        if (value <= 0.03928f) value / 12.92f else ((value + 0.055f) / 1.055f).let { it * it * it }
    return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
}
