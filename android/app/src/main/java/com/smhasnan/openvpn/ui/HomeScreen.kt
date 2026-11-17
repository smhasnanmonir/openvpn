package com.smhasnan.openvpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.smhasnan.openvpn.config.ProfileSummary
import com.smhasnan.openvpn.ui.components.BrandMark
import com.smhasnan.openvpn.ui.components.DividerLine
import com.smhasnan.openvpn.ui.components.EmptyState
import com.smhasnan.openvpn.ui.components.KeyValueRow
import com.smhasnan.openvpn.ui.components.MessageBanner
import com.smhasnan.openvpn.ui.components.MetaChip
import com.smhasnan.openvpn.ui.components.ProfileSwitcherSheet
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
import com.smhasnan.openvpn.vpn.PortalState
import com.smhasnan.openvpn.vpn.PublicIpState
import com.smhasnan.openvpn.vpn.VpnSnapshot
import com.smhasnan.openvpn.vpn.VpnState
import java.util.Date
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    snapshot: VpnSnapshot,
    profile: ProfileSummary?,
    profiles: List<ProfileSummary>,
    ipState: PublicIpState,
    importing: Boolean,
    portalState: PortalState,
    splitTunnelWarning: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onRefreshIp: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onImport: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenLogs: () -> Unit,
    onRespondChallenge: () -> Unit,
    onPauseForPortal: () -> Unit,
    onResumeAfterPortal: () -> Unit,
    onOpenPortalSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var elapsedMillis by remember(snapshot.connectedAtMillis, snapshot.state) {
        mutableLongStateOf(0L)
    }
    var switcherOpen by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, snapshot.connectedAtMillis, snapshot.state) {
        val started = snapshot.connectedAtMillis
        if (snapshot.state == VpnState.CONNECTED && started != null) {
            // Tick only while the window is actually resumed. In the background
            // the system notification chronometer already shows elapsed time, so
            // a UI timer there would be pure wake-up cost.
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    elapsedMillis = (System.currentTimeMillis() - started).coerceAtLeast(0L)
                    delay(1_000L)
                }
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
                    canSwitchProfile = profiles.size > 1,
                    onOpenProfileSwitcher = { switcherOpen = true },
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    onRespondChallenge = onRespondChallenge,
                    onResumeAfterPortal = onResumeAfterPortal,
                )
            }
        }

        // Honest protection state: a portal pause leaves the device unprotected,
        // so it is always stated first and in the strongest terms.
        if (snapshot.pausedForPortal) {
            item {
                PausedForPortalBanner(
                    onSignIn = onOpenPortalSignIn,
                    onResume = onResumeAfterPortal,
                )
            }
        }

        if (!snapshot.pausedForPortal && portalState == PortalState.SIGN_IN_REQUIRED &&
            snapshot.state.isActive
        ) {
            item {
                MessageBanner(
                    message = "This network needs sign-in. While the VPN is connected the " +
                        "sign-in page may be blocked by the tunnel.",
                    isError = false,
                    actionLabel = "Pause VPN",
                    onAction = onPauseForPortal,
                )
            }
        }

        if (splitTunnelWarning != null) {
            item {
                MessageBanner(message = splitTunnelWarning, isError = true)
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

    if (switcherOpen && profile != null) {
        ProfileSwitcherSheet(
            profiles = profiles,
            selectedId = profile.id,
            locked = snapshot.state.isActive,
            onSelect = {
                switcherOpen = false
                onSelectProfile(it)
            },
            onManageProfiles = {
                switcherOpen = false
                onOpenProfiles()
            },
            onDismiss = { switcherOpen = false },
        )
    }
}

@Composable
private fun ConnectionHero(
    snapshot: VpnSnapshot,
    profile: ProfileSummary,
    elapsedMillis: Long,
    canSwitchProfile: Boolean,
    onOpenProfileSwitcher: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRespondChallenge: () -> Unit,
    onResumeAfterPortal: () -> Unit,
) {
    val paused = snapshot.pausedForPortal
    val connected = snapshot.state == VpnState.CONNECTED && !paused
    val busy = snapshot.state == VpnState.PREPARING ||
        snapshot.state == VpnState.CONNECTING ||
        snapshot.state == VpnState.RECONNECTING ||
        snapshot.state == VpnState.DISCONNECTING
    val failed = snapshot.state == VpnState.ERROR || snapshot.state == VpnState.AUTH_FAILED

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(HeroTop, HeroBottom),
                ),
            )
            .padding(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .then(
                            if (canSwitchProfile) {
                                Modifier.clickable(
                                    onClickLabel = "Switch profile",
                                    onClick = onOpenProfileSwitcher,
                                )
                            } else {
                                Modifier
                            },
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                    if (canSwitchProfile) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Switch profile",
                            tint = HeroMuted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                HeroStatusPill(state = snapshot.state, paused = paused)
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
                        text = heroHeadline(snapshot.state, paused),
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (failed) HeroDanger else HeroText,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = heroSupporting(snapshot.state, paused, profile),
                        style = MaterialTheme.typography.bodySmall,
                        color = HeroMuted,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(18.dp))

                Button(
                    onClick = {
                        if (paused) {
                            onResumeAfterPortal()
                            return@Button
                        }
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
                    shape = RoundedCornerShape(50),
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
                        text = heroActionLabel(snapshot.state, paused),
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
private fun HeroStatusPill(state: VpnState, paused: Boolean) {
    val dotColor = when {
        paused -> StatusAmberLight
        state == VpnState.CONNECTED -> HeroAccent
        state == VpnState.CONNECTING || state == VpnState.PREPARING ||
            state == VpnState.RECONNECTING || state == VpnState.DISCONNECTING -> StatusAmberLight
        state == VpnState.AUTH_FAILED || state == VpnState.ERROR -> HeroDanger
        state == VpnState.NEEDS_CHALLENGE -> StatusBlueLight
        else -> HeroMuted
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
                text = heroStatusLabel(state, paused).uppercase(),
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
            .clip(RoundedCornerShape(18.dp))
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

private fun heroStatusLabel(state: VpnState, paused: Boolean): String = when {
    paused -> "Paused"
    state == VpnState.DISCONNECTED -> "Inactive"
    state == VpnState.PREPARING -> "Preparing"
    state == VpnState.CONNECTING -> "Connecting"
    state == VpnState.CONNECTED -> "Protected"
    state == VpnState.RECONNECTING -> "Reconnecting"
    state == VpnState.DISCONNECTING -> "Closing"
    state == VpnState.NEEDS_CHALLENGE -> "Action needed"
    state == VpnState.AUTH_FAILED -> "Sign-in failed"
    else -> "Error"
}

private fun heroHeadline(state: VpnState, paused: Boolean): String = when {
    paused -> "VPN paused for sign-in"
    state == VpnState.PREPARING -> "Preparing the tunnel"
    state == VpnState.CONNECTING -> "Establishing a session"
    state == VpnState.RECONNECTING -> "Restoring the session"
    state == VpnState.DISCONNECTING -> "Closing the tunnel"
    state == VpnState.NEEDS_CHALLENGE -> "Verification required"
    state == VpnState.AUTH_FAILED -> "Sign-in was rejected"
    state == VpnState.ERROR -> "Connection stopped"
    state == VpnState.DISCONNECTED -> "You are not protected"
    else -> "Connected"
}

private fun heroSupporting(
    state: VpnState,
    paused: Boolean,
    profile: ProfileSummary,
): String = when {
    // A paused snapshot is never CONNECTED, so it can never render "Protected".
    paused -> "Your traffic is NOT protected until you finish signing in and resume the VPN."
    state == VpnState.DISCONNECTED ->
        "Connect to route this device through ${profile.metadata.displayServer ?: "your server"}."
    state == VpnState.PREPARING -> "Preparing the device tunnel…"
    state == VpnState.CONNECTING -> "Negotiating encryption with the server…"
    state == VpnState.RECONNECTING -> "Re-establishing the secure session…"
    state == VpnState.DISCONNECTING -> "Closing the tunnel…"
    state == VpnState.ERROR || state == VpnState.AUTH_FAILED ->
        "Check diagnostics for the engine reason, then try again."
    state == VpnState.NEEDS_CHALLENGE -> "The server is asking for an extra response."
    else -> "Your traffic is routed through the tunnel."
}

private fun heroActionLabel(state: VpnState, paused: Boolean): String = when {
    paused -> "Resume VPN"
    state == VpnState.CONNECTED || state == VpnState.RECONNECTING -> "Disconnect"
    state == VpnState.PREPARING || state == VpnState.CONNECTING -> "Cancel"
    state == VpnState.DISCONNECTING -> "Disconnecting…"
    state == VpnState.NEEDS_CHALLENGE -> "Respond to server"
    state == VpnState.ERROR || state == VpnState.AUTH_FAILED -> "Try again"
    else -> "Connect"
}

/**
 * Unambiguous portal-pause banner. The tunnel is down and Android restored the
 * underlying network, so the device is exposed until the user signs in and
 * resumes; this banner must never soften that language.
 */
@Composable
private fun PausedForPortalBanner(
    onSignIn: () -> Unit,
    onResume: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = scheme.errorContainer,
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = scheme.onErrorContainer,
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "VPN paused for network sign-in",
                        style = MaterialTheme.typography.titleSmall,
                        color = scheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "Your traffic is NOT protected. Sign in to this network, then " +
                            "resume the VPN.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onErrorContainer,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onSignIn,
                    colors = ButtonDefaults.textButtonColors(contentColor = scheme.onErrorContainer),
                ) { Text("Sign in") }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = onResume,
                    shape = RoundedCornerShape(50),
                ) { Text("Resume VPN") }
            }
        }
    }
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
                    text = publicIpSubtitle(state, connected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRefresh, enabled = !state.loading) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh public IP")
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            state.loading -> PublicIpLoading(throughTunnel = state.throughTunnel)
            state.error != null -> PublicIpError(state.error.orEmpty(), onRefresh)
            state.ip != null -> PublicIpResult(state)
            else -> PublicIpEmpty(onRefresh)
        }
    }
}

private fun publicIpSubtitle(state: PublicIpState, connected: Boolean): String = when {
    state.loading && state.throughTunnel -> "Checking through the tunnel…"
    state.loading -> "Checking the direct connection…"
    state.error != null -> "The last check did not complete"
    state.ip != null && state.throughTunnel -> "Last checked through the tunnel"
    state.ip != null -> "Last checked on the direct connection"
    connected -> "Check the address the internet sees through the tunnel"
    else -> "What the internet currently sees"
}

/**
 * Deliberately static placeholder: a shimmer/spinner would keep animating (and
 * waking the frame clock) for the whole request, which is exactly the kind of
 * avoidable work this screen is trying to remove.
 */
@Composable
private fun PublicIpLoading(throughTunnel: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.45f)
                .height(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(scheme.surfaceContainerHigh),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(scheme.surfaceContainerHigh.copy(alpha = 0.7f)),
        )
        Text(
            text = if (throughTunnel) {
                "Querying the IP service through the active tunnel…"
            } else {
                "Querying the IP service on the active network…"
            },
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PublicIpError(message: String, onRefresh: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = scheme.error,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onRefresh) { Text("Try again") }
        }
    }
}

@Composable
private fun PublicIpResult(state: PublicIpState) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val checkedAt = state.checkedAtMillis
    val checkedLabel = if (checkedAt != null) {
        val timeFormat = remember(context) {
            android.text.format.DateFormat.getTimeFormat(context)
        }
        timeFormat.format(Date(checkedAt))
    } else {
        null
    }
    Text(
        text = state.ip.orEmpty(),
        style = MaterialTheme.typography.headlineSmall,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        color = scheme.onSurface,
    )
    Spacer(Modifier.height(8.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MetaChip(
            text = if (state.throughTunnel) "Via tunnel" else "Direct",
            emphasized = state.throughTunnel,
        )
        if (checkedLabel != null) {
            Text(
                text = "Checked $checkedLabel",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Egress address reported by the IP service. Not a full leak test.",
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant,
    )
}

@Composable
private fun PublicIpEmpty(onRefresh: () -> Unit) {
    Text(
        text = "The check sends one HTTPS request to an IP echo service through the active network.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    Button(
        onClick = onRefresh,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Text("Check IP address")
    }
}
