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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smhasnan.openvpn.config.ProfileSummary
import com.smhasnan.openvpn.ui.components.BrandMark
import com.smhasnan.openvpn.ui.components.DividerLine
import com.smhasnan.openvpn.ui.components.EmptyState
import com.smhasnan.openvpn.ui.components.KeyValueRow
import com.smhasnan.openvpn.ui.components.MessageBanner
import com.smhasnan.openvpn.ui.components.ScreenHeader
import com.smhasnan.openvpn.ui.components.SectionCard
import com.smhasnan.openvpn.ui.components.SectionLabel
import com.smhasnan.openvpn.ui.components.StatTile
import com.smhasnan.openvpn.ui.theme.Dimens
import com.smhasnan.openvpn.ui.theme.HeroAccent
import com.smhasnan.openvpn.ui.theme.HeroBottom
import com.smhasnan.openvpn.ui.theme.HeroDanger
import com.smhasnan.openvpn.ui.theme.HeroMuted
import com.smhasnan.openvpn.ui.theme.HeroText
import com.smhasnan.openvpn.ui.theme.HeroTop
import com.smhasnan.openvpn.ui.theme.StatusAmberLight
import com.smhasnan.openvpn.ui.theme.StatusBlueLight
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
        contentPadding = PaddingValues(
            start = Dimens.ScreenPadding,
            end = Dimens.ScreenPadding,
            top = 12.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.SectionGap),
    ) {
        item {
            ScreenHeader(
                eyebrow = "OpenVPN Client",
                title = "Home",
                subtitle = "Private by design",
            )
        }

        item {
            if (profile == null) {
                SectionCard {
                    EmptyState(
                        icon = { BrandMark(size = 34.dp) },
                        title = "No profile yet",
                        message = "Import a .ovpn profile to start a private tunnel to your own OpenVPN server.",
                        actionLabel = if (importing) "Importing…" else "Import profile",
                        onAction = if (importing) null else onImport,
                    )
                    TextButton(
                        onClick = onOpenProfiles,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Browse profiles")
                    }
                }
            } else {
                ConnectionHero(
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
                SectionLabel("This session")
                SessionCard(snapshot = snapshot, elapsedMillis = elapsedMillis)
            }
            item {
                SectionLabel("Connection details")
                ConnectionDetailsCard(snapshot = snapshot, onReconnect = onReconnect)
            }
        }

        item {
            SectionLabel("Network")
            PublicIpCard(
                state = ipState,
                connected = snapshot.state == VpnState.CONNECTED,
                onRefresh = onRefreshIp,
            )
        }

        if (profile == null) {
            item {
                SectionLabel("About the engine")
                SectionCard {
                    Text(
                        text = "Profiles are parsed by the official OpenVPN 3 Core engine, " +
                            "encrypted with Android Keystore, and never logged.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionHero(
    snapshot: VpnSnapshot,
    profile: ProfileSummary,
    elapsedMillis: Long,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRespondChallenge: () -> Unit,
) {
    val connected = snapshot.state == VpnState.CONNECTED
    val busy = snapshot.state == VpnState.PREPARING ||
        snapshot.state == VpnState.CONNECTING ||
        snapshot.state == VpnState.RECONNECTING ||
        snapshot.state == VpnState.DISCONNECTING
    val failed = snapshot.state == VpnState.ERROR || snapshot.state == VpnState.AUTH_FAILED

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(HeroTop, HeroBottom),
                ),
            )
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(HeroAccent.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = profile.emoji,
                        fontSize = 20.sp,
                        lineHeight = 22.sp,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = HeroText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = listOfNotNull(
                            profile.metadata.displayServer,
                            profile.metadata.protocolLabel,
                        ).joinToString(" · ").ifBlank { "OpenVPN profile" },
                        style = MaterialTheme.typography.bodySmall,
                        color = HeroMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                HeroStatusPill(state = snapshot.state)
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (connected) {
                    Text(
                        text = DurationFormat.format(elapsedMillis),
                        style = MaterialTheme.typography.displaySmall,
                        color = HeroText,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Encrypted session · tap disconnect to close",
                        style = MaterialTheme.typography.bodySmall,
                        color = HeroMuted,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(
                        text = heroHeadline(snapshot.state),
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (failed) HeroDanger else HeroText,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = heroSupporting(snapshot.state, profile),
                        style = MaterialTheme.typography.bodySmall,
                        color = HeroMuted,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(18.dp))

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
                        .height(Dimens.PrimaryActionHeight),
                    shape = RoundedCornerShape(16.dp),
                    colors = if (connected) {
                        ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.10f),
                            contentColor = HeroAccent,
                            disabledContainerColor = Color.White.copy(alpha = 0.06f),
                            disabledContentColor = HeroMuted,
                        )
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = HeroAccent,
                            contentColor = HeroTop,
                            disabledContainerColor = HeroAccent.copy(alpha = 0.4f),
                            disabledContentColor = HeroTop.copy(alpha = 0.6f),
                        )
                    },
                    border = if (connected) {
                        BorderStroke(1.dp, HeroAccent.copy(alpha = 0.45f))
                    } else {
                        null
                    },
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = if (connected) HeroAccent else HeroTop,
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        text = heroActionLabel(snapshot.state),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                if (connected && snapshot.stats.hasData) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        HeroStat(
                            label = "Down",
                            value = ByteFormat.format(snapshot.stats.bytesIn),
                            modifier = Modifier.weight(1f),
                        )
                        HeroStat(
                            label = "Up",
                            value = ByteFormat.format(snapshot.stats.bytesOut),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroStatusPill(state: VpnState) {
    val dotColor = when (state) {
        VpnState.CONNECTED -> HeroAccent
        VpnState.CONNECTING, VpnState.PREPARING, VpnState.RECONNECTING, VpnState.DISCONNECTING -> StatusAmberLight
        VpnState.AUTH_FAILED, VpnState.ERROR -> HeroDanger
        VpnState.NEEDS_CHALLENGE -> StatusBlueLight
        VpnState.DISCONNECTED -> HeroMuted
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.White.copy(alpha = 0.10f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(dotColor, CircleShape),
            )
            Text(
                text = heroStatusLabel(state).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = HeroText,
            )
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = HeroMuted,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = HeroText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun heroStatusLabel(state: VpnState): String = when (state) {
    VpnState.DISCONNECTED -> "Inactive"
    VpnState.PREPARING -> "Preparing"
    VpnState.CONNECTING -> "Connecting"
    VpnState.CONNECTED -> "Protected"
    VpnState.RECONNECTING -> "Reconnecting"
    VpnState.DISCONNECTING -> "Closing"
    VpnState.NEEDS_CHALLENGE -> "Action needed"
    VpnState.AUTH_FAILED -> "Sign-in failed"
    VpnState.ERROR -> "Error"
}

private fun heroHeadline(state: VpnState): String = when (state) {
    VpnState.PREPARING -> "Preparing the tunnel"
    VpnState.CONNECTING -> "Establishing a session"
    VpnState.RECONNECTING -> "Restoring the session"
    VpnState.DISCONNECTING -> "Closing the tunnel"
    VpnState.NEEDS_CHALLENGE -> "Verification required"
    VpnState.AUTH_FAILED -> "Sign-in was rejected"
    VpnState.ERROR -> "Connection stopped"
    VpnState.DISCONNECTED -> "You are not protected"
    VpnState.CONNECTED -> "Connected"
}

private fun heroSupporting(state: VpnState, profile: ProfileSummary): String = when (state) {
    VpnState.DISCONNECTED ->
        "Connect to route this device through ${profile.metadata.displayServer ?: "your server"}."
    VpnState.PREPARING -> "Preparing the device tunnel…"
    VpnState.CONNECTING -> "Negotiating encryption with the server…"
    VpnState.RECONNECTING -> "Re-establishing the secure session…"
    VpnState.DISCONNECTING -> "Closing the tunnel…"
    VpnState.ERROR, VpnState.AUTH_FAILED ->
        "Check diagnostics for the engine reason, then try again."
    VpnState.NEEDS_CHALLENGE -> "The server is asking for an extra response."
    VpnState.CONNECTED -> "Your traffic is routed through the tunnel."
}

private fun heroActionLabel(state: VpnState): String = when (state) {
    VpnState.CONNECTED, VpnState.RECONNECTING -> "Disconnect"
    VpnState.PREPARING, VpnState.CONNECTING -> "Cancel"
    VpnState.DISCONNECTING -> "Disconnecting…"
    VpnState.NEEDS_CHALLENGE -> "Respond to server"
    VpnState.ERROR, VpnState.AUTH_FAILED -> "Try again"
    VpnState.DISCONNECTED -> "Connect"
}

@Composable
private fun SessionCard(snapshot: VpnSnapshot, elapsedMillis: Long) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Session",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = DurationFormat.format(elapsedMillis),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                modifier = Modifier.weight(0.8f),
            )
        }
        if (!snapshot.stats.hasData) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Traffic counters appear after the first packets cross the tunnel.",
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
            Text(
                text = "Route",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onReconnect) { Text("Reconnect") }
        }
        DividerLine()
        Spacer(Modifier.height(4.dp))
        KeyValueRow("Server", snapshot.details.serverLabel ?: "—")
        if (snapshot.details.serverIp != null) KeyValueRow("Server IP", snapshot.details.serverIp)
        KeyValueRow("Protocol", snapshot.details.protocol?.uppercase() ?: "—")
        if (!snapshot.details.vpnIp4.isNullOrBlank()) KeyValueRow("VPN IPv4", snapshot.details.vpnIp4)
        if (!snapshot.details.vpnIp6.isNullOrBlank()) KeyValueRow("VPN IPv6", snapshot.details.vpnIp6)
    }
}

@Composable
private fun PublicIpCard(state: PublicIpState, connected: Boolean, onRefresh: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Public IP", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when {
                        connected -> "Checked through the active tunnel"
                        state.ip != null -> "Last known egress address"
                        else -> "What the internet currently sees"
                    },
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
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Checking…", style = MaterialTheme.typography.bodyMedium)
            }
            state.error != null -> {
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRefresh) { Text("Try again") }
            }
            state.ip != null -> {
                Text(
                    text = state.ip.orEmpty(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Egress address reported by the IP service. Not a full leak test.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                Text(
                    text = "Check the address the internet sees for this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onRefresh,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                    Text("Check IP address")
                }
            }
        }
    }
}
