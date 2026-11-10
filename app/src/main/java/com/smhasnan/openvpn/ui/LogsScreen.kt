package com.smhasnan.openvpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smhasnan.openvpn.ui.components.EmptyState
import com.smhasnan.openvpn.ui.components.ProfileAvatar
import com.smhasnan.openvpn.ui.components.ScreenHeader
import com.smhasnan.openvpn.ui.components.SectionCard
import com.smhasnan.openvpn.ui.theme.StatusAmber
import com.smhasnan.openvpn.ui.theme.StatusAmberLight
import com.smhasnan.openvpn.ui.theme.StatusRed
import com.smhasnan.openvpn.ui.theme.StatusRedLight
import com.smhasnan.openvpn.vpn.LogEntry
import com.smhasnan.openvpn.vpn.LogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogsScreen(
    logs: List<LogEntry>,
    onClear: () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf<LogLevel?>(null) }
    val filtered = remember(logs, filter) {
        if (filter == null) logs else logs.filter { it.level == filter }
    }
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) listState.animateScrollToItem(filtered.lastIndex)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp),
    ) {
        ScreenHeader(
            title = "Diagnostics",
            subtitle = "Sanitized events from OpenVPN Core and the VPN service",
            action = {
                Row {
                    if (logs.isNotEmpty()) {
                        TextButton(onClick = {
                            copyLogs(context, filtered, timeFormat)
                            onMessage("Sanitized logs copied")
                        }) { Text("Copy") }
                        TextButton(onClick = onClear) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Clear")
                        }
                    }
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = filter == null,
                onClick = { filter = null },
                label = { Text("All ${logs.size}") },
            )
            FilterChip(
                selected = filter == LogLevel.WARN,
                onClick = { filter = if (filter == LogLevel.WARN) null else LogLevel.WARN },
                label = { Text("Warnings") },
            )
            FilterChip(
                selected = filter == LogLevel.ERROR,
                onClick = { filter = if (filter == LogLevel.ERROR) null else LogLevel.ERROR },
                label = { Text("Errors") },
            )
        }
        Spacer(Modifier.height(12.dp))
        if (logs.isEmpty()) {
            SectionCard(modifier = Modifier.fillMaxWidth()) {
                EmptyState(
                    icon = { ProfileAvatar(emoji = "📋", size = 42.dp) },
                    title = "No events yet",
                    message = "Profile validation, connection events, and sanitized engine logs will appear here. Passwords, keys, and profile contents are never written.",
                )
            }
        } else if (filtered.isEmpty()) {
            SectionCard(modifier = Modifier.fillMaxWidth()) {
                EmptyState(
                    icon = { ProfileAvatar(emoji = "✅", size = 42.dp) },
                    title = "Nothing in this filter",
                    message = "There are no ${filter?.name?.lowercase() ?: ""} events yet.",
                    actionLabel = "Show all",
                    onAction = { filter = null },
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
    val dark = MaterialTheme.colorScheme.background.luminanceCompat() < 0.5f
    val color = when (entry.level) {
        LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
        LogLevel.WARN -> if (dark) StatusAmberLight else StatusAmber
        LogLevel.ERROR -> if (dark) StatusRedLight else StatusRed
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface,
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = timeFormat.format(Date(entry.timestampMillis)),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = entry.level.name.take(4),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = color,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
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
