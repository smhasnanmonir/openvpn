package com.smhasnan.openvpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smhasnan.openvpn.config.ProfileSummary
import com.smhasnan.openvpn.ui.components.BrandMark
import com.smhasnan.openvpn.ui.components.DividerLine
import com.smhasnan.openvpn.ui.components.EmptyState
import com.smhasnan.openvpn.ui.components.InfoRow
import com.smhasnan.openvpn.ui.components.MessageBanner
import com.smhasnan.openvpn.ui.components.ProfileAvatar
import com.smhasnan.openvpn.ui.components.ScreenHeader
import com.smhasnan.openvpn.ui.components.SectionCard
import com.smhasnan.openvpn.ui.components.StatTile
import com.smhasnan.openvpn.ui.components.StatusBadge
import com.smhasnan.openvpn.ui.components.statusColors
import com.smhasnan.openvpn.util.ByteFormat
import com.smhasnan.openvpn.util.DurationFormat
import com.smhasnan.openvpn.vpn.PublicIpState
import com.smhasnan.openvpn.vpn.VpnSnapshot
import com.smhasnan.openvpn.vpn.VpnState
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    snapshot: VpnSnapshot,
    profile: ProfileSummary?,
    ipState: PublicIpState,
    importing: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onRefreshIp: () -> Unit,
    onImport: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenLogs: () -> Unit,
    onRespondChallenge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var elapsedMillis by remember(snapshot.connectedAtMillis, snapshot.state) {
        mutableLongStateOf(0L)
    }
    LaunchedEffect(snapshot.connectedAtMillis, snapshot.state) {
        val started = snapshot.connectedAtMillis
        if (snapshot.state == VpnState.CONNECTED && started != null) {
            while (true) {
                elapsedMillis = System.currentTimeMillis() - started
                delay(1_000L)
            }
        } else {
            elapsedMillis = 0L
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ScreenHeader(
                title = "OpenVPN Client",
                subtitle = "Private by design",
            )
        }

        item {
            if (profile == null) {
                SectionCard {
                    EmptyState(
                        icon = { BrandMark(size = 42.dp) },
                        title = "Your private tunnel starts here",
                        message = "Import a .ovpn profile to connect through your own OpenVPN server.",
                        actionLabel = if (importing) "Importing…" else "Import profile",
                        onAction = if (importing) null else onImport,
                    )
                    TextButton(
                        onClick = onOpenProfiles,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("View profiles")
                    }
                }
            } else {
                ConnectHero(
                    snapshot = snapshot,
                    profile = profile,
                    elapsedMillis = elapsedMillis,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    onRespondChallenge = onRespondChallenge,
                )
            }
        }

        if (snapshot.state == VpnState.ERROR || snapshot.state == VpnState.AUTH_FAILED) {
            item {
                MessageBanner(
                    message = snapshot.error ?: "The VPN connection stopped.",
                    isError = true,
                    actionLabel = "Logs",
                    onAction = onOpenLogs,
                )
            }
        }
        if (snapshot.state == VpnState.NEEDS_CHALLENGE) {
            item {
                MessageBanner(
                    message = snapshot.challenge ?: "The server needs an additional response.",
                    isError = false,
                    actionLabel = "Respond",
                    onAction = onRespondChallenge,
                )
            }
        }

        if (profile != null && snapshot.state == VpnState.CONNECTED) {
            item {
                ConnectionStatsCard(snapshot = snapshot, elapsedMillis = elapsedMillis)
            }
            item {
                ConnectionDetailsCard(snapshot = snapshot, onReconnect = onReconnect)
            }
        }

        item {
            PublicIpCard(
                state = ipState,
                connected = snapshot.state == VpnState.CONNECTED,
                onRefresh = onRefreshIp,
            )
        }

        if (profile == null) {
            item {
                SectionCard {
                    Text(
                        "How it works",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Profiles are parsed by the official OpenVPN 3 Core engine, encrypted with Android Keystore, and never logged. The app uses Android VpnService for system-wide routing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectHero(
    snapshot: VpnSnapshot,
    profile: ProfileSummary,
    elapsedMillis: Long,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRespondChallenge: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val (statusColor, statusContainer) = statusColors(snapshot.state)
    val heroBrush = Brush.verticalGradient(
        listOf(
            colors.primaryContainer.copy(alpha = if (colors.background.luminanceCompat() < 0.5f) 0.62f else 1f),
            colors.surface,
        ),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(heroBrush)
            .padding(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar(emoji = profile.emoji, size = 52.dp, selected = true)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = listOfNotNull(
                            profile.metadata.displayServer,
                            profile.metadata.protocolLabel,
                        ).joinToString(" · ").ifBlank { "OpenVPN profile" },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusBadge(snapshot.state)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (snapshot.state == VpnState.CONNECTED) {
                    Text(
                        text = DurationFormat.format(elapsedMillis),
                        style = MaterialTheme.typography.displaySmall,
                        color = colors.onSurface,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = "Connected",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                } else {
                    Text(
                        text = when (snapshot.state) {
                            VpnState.PREPARING -> "Preparing the tunnel"
                            VpnState.CONNECTING -> "Establishing a secure session"
                            VpnState.RECONNECTING -> "Restoring the secure session"
                            VpnState.DISCONNECTING -> "Closing the tunnel"
                            VpnState.NEEDS_CHALLENGE -> "The server needs a response"
                            VpnState.AUTH_FAILED -> "Check your sign-in details"
                            VpnState.ERROR -> "The last connection attempt stopped"
                            VpnState.DISCONNECTED -> "Your traffic is not protected"
                            VpnState.CONNECTED -> "Connected"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = when (snapshot.state) {
                            VpnState.DISCONNECTED -> "Tap connect to open a private route to ${profile.metadata.serverHost ?: "your server"}."
                            VpnState.ERROR, VpnState.AUTH_FAILED -> "Open diagnostics if the problem persists."
                            else -> "Please wait…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                }

                val busy = snapshot.state == VpnState.PREPARING ||
                    snapshot.state == VpnState.CONNECTING ||
                    snapshot.state == VpnState.RECONNECTING ||
                    snapshot.state == VpnState.DISCONNECTING
                val connected = snapshot.state == VpnState.CONNECTED
                val label = when (snapshot.state) {
                    VpnState.CONNECTED, VpnState.RECONNECTING -> "Disconnect"
                    VpnState.PREPARING, VpnState.CONNECTING -> "Cancel"
                    VpnState.DISCONNECTING -> "Disconnecting…"
                    VpnState.NEEDS_CHALLENGE -> "Respond to server"
                    VpnState.ERROR, VpnState.AUTH_FAILED -> "Try again"
                    VpnState.DISCONNECTED -> "Connect"
                }
                Button(
                    onClick = {
                        when (snapshot.state) {
                            VpnState.CONNECTED, VpnState.RECONNECTING,
                            VpnState.PREPARING, VpnState.CONNECTING -> onDisconnect()
                            VpnState.NEEDS_CHALLENGE -> onRespondChallenge()
                            VpnState.DISCONNECTING -> Unit
                            else -> onConnect()
                        }
                    },
                    enabled = snapshot.state != VpnState.DISCONNECTING,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = if (connected) {
                        ButtonDefaults.buttonColors(
                            containerColor = colors.surface,
                            contentColor = colors.error,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    border = if (connected) BorderStroke(1.dp, colors.error.copy(alpha = 0.45f)) else null,
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = if (connected) colors.error else colors.onPrimary,
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }

                if (snapshot.state == VpnState.CONNECTED && snapshot.stats.hasData) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "${ByteFormat.format(snapshot.stats.bytesIn)} down · ${ByteFormat.format(snapshot.stats.bytesOut)} up",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatsCard(snapshot: VpnSnapshot, elapsedMillis: Long) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Session", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                DurationFormat.format(elapsedMillis),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "Download",
                value = if (snapshot.stats.hasData) ByteFormat.format(snapshot.stats.bytesIn) else "—",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Upload",
                value = if (snapshot.stats.hasData) ByteFormat.format(snapshot.stats.bytesOut) else "—",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "MTU",
                value = snapshot.details.mtu?.takeIf { it.isNotBlank() } ?: "—",
                modifier = Modifier.weight(0.7f),
            )
        }
        if (!snapshot.stats.hasData) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Traffic counters appear after the first packets traverse the tunnel.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConnectionDetailsCard(snapshot: VpnSnapshot, onReconnect: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Connection", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onReconnect) { Text("Reconnect") }
        }
        DividerLine()
        Spacer(Modifier.height(6.dp))
        InfoRow("Server", snapshot.details.serverLabel ?: "—")
        if (snapshot.details.serverIp != null) InfoRow("Server IP", snapshot.details.serverIp)
        InfoRow("Protocol", snapshot.details.protocol?.uppercase() ?: "—")
        if (!snapshot.details.vpnIp4.isNullOrBlank()) InfoRow("VPN IPv4", snapshot.details.vpnIp4)
        if (!snapshot.details.vpnIp6.isNullOrBlank()) InfoRow("VPN IPv6", snapshot.details.vpnIp6)
    }
}

@Composable
private fun PublicIpCard(state: PublicIpState, connected: Boolean, onRefresh: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Public IP", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (connected) "Checked through the active VPN network" else "The internet address apps currently use",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRefresh, enabled = !state.loading) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh public IP")
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            state.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Checking…", style = MaterialTheme.typography.bodyMedium)
            }
            state.error != null -> {
                Text(
                    state.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRefresh) { Text("Try again") }
            }
            state.ip != null -> {
                Text(
                    text = state.ip,
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "This is the public egress address reported by the IP service. It is not a complete leak test.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                Text(
                    "Check the address the internet sees for this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onRefresh) { Text("Check IP address") }
            }
        }
    }
}

private fun Color.luminanceCompat(): Float {
    fun channel(value: Float): Float =
        if (value <= 0.03928f) value / 12.92f else ((value + 0.055f) / 1.055f).let { it * it * it }
    return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
}
