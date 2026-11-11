package com.smhasnan.openvpn.ui

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.smhasnan.openvpn.BuildConfig
import com.smhasnan.openvpn.config.AlwaysOnVpnPolicy
import com.smhasnan.openvpn.config.SystemVpnState
import com.smhasnan.openvpn.settings.AppSettings
import com.smhasnan.openvpn.settings.CipherPolicy
import com.smhasnan.openvpn.settings.CompressionPolicy
import com.smhasnan.openvpn.settings.MtuMode
import com.smhasnan.openvpn.settings.SplitTunnelMode
import com.smhasnan.openvpn.settings.ThemeMode
import com.smhasnan.openvpn.ui.components.DividerLine
import com.smhasnan.openvpn.ui.components.KeyValueRow
import com.smhasnan.openvpn.ui.components.LinkRow
import com.smhasnan.openvpn.ui.components.MessageBanner
import com.smhasnan.openvpn.ui.components.MetaChip
import com.smhasnan.openvpn.ui.components.ScreenHeader
import com.smhasnan.openvpn.ui.components.SectionCard
import com.smhasnan.openvpn.ui.components.SectionLabel
import com.smhasnan.openvpn.ui.components.SegmentedOptions
import com.smhasnan.openvpn.ui.components.ToggleRow
import com.smhasnan.openvpn.ui.theme.Dimens

@Composable
fun SettingsScreen(
    settings: AppSettings,
    selfTest: SelfTestState,
    engineInfo: Map<String, String>,
    alwaysOnState: SystemVpnState,
    onThemeChange: (ThemeMode) -> Unit,
    onBiometricToggle: (Boolean) -> Unit,
    onBlockUnusedFamiliesToggle: (Boolean) -> Unit,
    onAutoCheckIpToggle: (Boolean) -> Unit,
    onRequireTunnelDnsToggle: (Boolean) -> Unit,
    onFallbackDnsChange: (String) -> Unit,
    onMtuModeChange: (MtuMode) -> Unit,
    onCompressionPolicyChange: (CompressionPolicy) -> Unit,
    onCipherPolicyChange: (CipherPolicy) -> Unit,
    onOpenSplitTunnel: () -> Unit,
    onRefreshAlwaysOn: () -> Unit,
    onRunSelfTest: () -> Unit,
    notificationsGranted: Boolean,
    onOpenNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coreVersion = engineInfo["coreVersion"] ?: "unknown"
    val platform = remember(engineInfo["platform"], coreVersion) {
        val raw = engineInfo["platform"] ?: "unknown"
        raw.replaceFirst(Regex("^OpenVPN core \\S+ "), "").ifBlank { raw }
    }
    var showAlwaysOnInfo by remember { mutableStateOf(false) }

    // The hidden Always-on settings can change outside the app; re-read them
    // whenever Settings becomes visible.
    LaunchedEffect(Unit) { onRefreshAlwaysOn() }

    val openSystemVpnSettings: () -> Unit = {
        runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
            .onFailure {
                runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
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
                eyebrow = "Preferences",
                title = "Settings",
                subtitle = "Security, network policy, and diagnostics",
            )
        }

        item {
            SectionLabel("Appearance")
            SectionCard {
                Text("Theme", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                SegmentedOptions(
                    options = ThemeMode.entries,
                    selected = settings.themeMode,
                    label = {
                        when (it) {
                            ThemeMode.SYSTEM -> "System"
                            ThemeMode.LIGHT -> "Light"
                            ThemeMode.DARK -> "Dark"
                        }
                    },
                    onSelect = onThemeChange,
                )
            }
        }

        item {
            SectionLabel("Security")
            SectionCard {
                ToggleRow(
                    title = "App lock",
                    subtitle = "Require fingerprint or device credential to open the app.",
                    checked = settings.biometricLock,
                    onCheckedChange = onBiometricToggle,
                )
                Spacer(Modifier.height(10.dp))
                DividerLine()
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "An active tunnel keeps running while the interface is locked. " +
                            "Profiles and credentials are stored with Android Keystore encryption.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionLabel("VPN security")
            SectionCard {
                ToggleRow(
                    title = "Require tunnel DNS",
                    subtitle = "Fail closed. If the server pushes no usable resolver and no fallback " +
                        "DNS is set, the connection is refused instead of falling back to the " +
                        "underlying network's DNS.",
                    checked = settings.requireTunnelDns,
                    onCheckedChange = onRequireTunnelDnsToggle,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.fallbackDnsServers,
                    onValueChange = onFallbackDnsChange,
                    label = { Text("Fallback DNS servers") },
                    placeholder = { Text("e.g. 1.1.1.1, 9.9.9.9") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                    supportingText = {
                        Text(
                            "Comma or space separated IPv4/IPv6 addresses, used through the tunnel " +
                                "when the server pushes none. Parsed and validated when connecting, so " +
                                "partial input is kept as typed.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                DividerLine()
                Spacer(Modifier.height(12.dp))
                Text("Compression policy", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                SegmentedOptions(
                    options = CompressionPolicy.entries,
                    selected = settings.compressionPolicy,
                    label = { compressionPolicyLabel(it) },
                    onSelect = onCompressionPolicyChange,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = compressionPolicyExplanation(settings.compressionPolicy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (settings.compressionPolicy == CompressionPolicy.STRICT) {
                    Spacer(Modifier.height(8.dp))
                    MessageBanner(
                        message = "Strict refuses any server-pushed compression. A provider that " +
                            "requires compression will fail to connect until you switch back to " +
                            "Compatibility.",
                        isError = true,
                    )
                }
                Spacer(Modifier.height(14.dp))
                DividerLine()
                Spacer(Modifier.height(12.dp))
                Text("Cipher policy", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                SegmentedOptions(
                    options = CipherPolicy.entries,
                    selected = settings.cipherPolicy,
                    label = { cipherPolicyLabel(it) },
                    onSelect = onCipherPolicyChange,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = cipherPolicyExplanation(settings.cipherPolicy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (settings.cipherPolicy == CipherPolicy.AEAD_ONLY) {
                    Spacer(Modifier.height(8.dp))
                    MessageBanner(
                        message = "AEAD-only can break legacy providers. Profiles or servers that " +
                            "require non-AEAD data ciphers (for example BF-CBC or other CBC modes) " +
                            "will fail to negotiate.",
                        isError = true,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Compression and cipher policy changes apply to the next connection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionLabel("Network")
            SectionCard {
                Text("TUN MTU", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Auto keeps the server-pushed MTU. A fixed, lower MTU avoids path-MTU " +
                        "black holes — typical on PPPoE, cellular, and IPv6 paths — where oversized " +
                        "packets are silently dropped and large transfers stall, at a small " +
                        "throughput cost.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                SegmentedOptions(
                    options = MtuMode.entries,
                    selected = settings.mtuMode,
                    label = { mtuModeLabel(it) },
                    onSelect = onMtuModeChange,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Applies to the next connection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                DividerLine()
                Spacer(Modifier.height(10.dp))
                ToggleRow(
                    title = "Block untunneled traffic",
                    subtitle = "Keep Android's default VPN routing, which blocks an address family " +
                        "when the tunnel provides no address, route, or DNS for it.",
                    checked = settings.blockUnusedFamilies,
                    onCheckedChange = onBlockUnusedFamiliesToggle,
                )
                Spacer(Modifier.height(8.dp))
                DividerLine()
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    title = "Check public IP after connecting",
                    subtitle = "One request to api.ipify.org through the active network.",
                    checked = settings.autoCheckIp,
                    onCheckedChange = onAutoCheckIpToggle,
                )
            }
        }

        item {
            SectionLabel("Per-app routing")
            SectionCard {
                LinkRow(
                    title = "Split tunneling",
                    subtitle = splitTunnelModeSummary(settings),
                    trailingLabel = if (settings.splitTunnelMode == SplitTunnelMode.OFF) {
                        "Off"
                    } else {
                        "Manage"
                    },
                    onClick = onOpenSplitTunnel,
                )
                splitTunnelLeakWarning(settings)?.let { warning ->
                    Spacer(Modifier.height(8.dp))
                    MessageBanner(message = warning, isError = true)
                }
            }
        }

        item {
            SectionLabel("System VPN")
            SectionCard {
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = alwaysOnTitle(alwaysOnState),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = AlwaysOnVpnPolicy.explanation(alwaysOnState),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    MetaChip(
                        text = alwaysOnChip(alwaysOnState),
                        emphasized = alwaysOnState == SystemVpnState.ALWAYS_ON_LOCKDOWN,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = alwaysOnCaveat(alwaysOnState),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showAlwaysOnInfo = true }) {
                        Text("How this works")
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onRefreshAlwaysOn) {
                        Text("Re-check")
                    }
                }
                DividerLine()
                Spacer(Modifier.height(4.dp))
                LinkRow(
                    title = "Open system VPN settings",
                    subtitle = "Android owns Always-on and lockdown; this app only links you there.",
                    trailingLabel = "Open",
                    onClick = openSystemVpnSettings,
                )
            }
        }

        item {
            SectionLabel("Notifications")
            SectionCard {
                if (notificationsGranted) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Notification controls", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "Connected-state notification and quick actions are enabled.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        MetaChip("Enabled", emphasized = true)
                    }
                } else {
                    LinkRow(
                        title = "Notification controls",
                        subtitle = "Notifications are off. Enable them to disconnect from the panel.",
                        trailingLabel = "Enable",
                        onClick = onOpenNotificationSettings,
                    )
                }
            }
        }

        item {
            SectionLabel("Diagnostics")
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Crypto self-test", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "Runs the pinned OpenVPN 3 Core mbedTLS self-test.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = onRunSelfTest,
                        enabled = selfTest != SelfTestState.Running,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) {
                        Text(
                            text = when (selfTest) {
                                SelfTestState.Idle -> "Run"
                                SelfTestState.Running -> "Running…"
                                is SelfTestState.Passed -> "Run again"
                                is SelfTestState.Failed -> "Retry"
                            },
                        )
                    }
                }
                when (selfTest) {
                    SelfTestState.Idle, SelfTestState.Running -> Unit
                    is SelfTestState.Passed -> {
                        Spacer(Modifier.height(12.dp))
                        MessageBanner(message = selfTest.message, isError = false)
                    }
                    is SelfTestState.Failed -> {
                        Spacer(Modifier.height(12.dp))
                        MessageBanner(message = selfTest.message, isError = true)
                    }
                }
                Spacer(Modifier.height(14.dp))
                DividerLine()
                Spacer(Modifier.height(4.dp))
                KeyValueRow(label = "OpenVPN Core", value = coreVersion)
                KeyValueRow(label = "Platform", value = platform)
                KeyValueRow(
                    label = "Core revision",
                    value = BuildConfig.OPENVPN_CORE_REVISION.take(12),
                    monospace = true,
                )
                KeyValueRow(
                    label = "App version",
                    value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    monospace = true,
                )
                KeyValueRow(label = "License", value = "AGPL-3.0-only")
            }
        }

        item {
            SectionLabel("About")
            SectionCard {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "OpenVPN Client uses the official OpenVPN 3 Core engine for protocol, " +
                                "TLS, certificate validation, and packet processing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Profiles, credentials, and logs never leave the device. Logs are " +
                                "kept in memory and sanitized before display.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showAlwaysOnInfo) {
        AlwaysOnExplanationDialog(
            onDismiss = { showAlwaysOnInfo = false },
            onOpenSystemSettings = {
                showAlwaysOnInfo = false
                openSystemVpnSettings()
            },
        )
    }
}

@Composable
private fun AlwaysOnExplanationDialog(
    onDismiss: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Always-on VPN and lockdown") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Always-on VPN: Android starts this app's VPN automatically after a " +
                        "reboot, after network changes, and after the app is updated. A saved " +
                        "profile is required, and the encrypted storage is only readable after the " +
                        "device has been unlocked once.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Lockdown (\"Block connections without VPN\"): while enabled, Android " +
                        "blocks all traffic that is not inside the VPN, even while the tunnel is " +
                        "down. This is the closest thing to a system-level kill switch.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Trade-offs: lockdown can block captive-portal sign-in and other " +
                        "networks until you pause the VPN or turn lockdown off. Always-on without " +
                        "lockdown is not a kill switch — traffic can leave unprotected while the " +
                        "tunnel is reconnecting.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Android hides these settings, so this app's detection is best-effort. " +
                        "It cannot enable Always-on or lockdown and cannot guarantee enforcement; " +
                        "confirm the state on the system VPN screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Got it") }
        },
        dismissButton = {
            TextButton(onClick = onOpenSystemSettings) { Text("Open settings") }
        },
    )
}

private fun mtuModeLabel(mode: MtuMode): String = when (mode) {
    MtuMode.AUTO -> "Auto"
    MtuMode.MTU1500 -> "1500"
    MtuMode.MTU1400 -> "1400"
    MtuMode.MTU1280 -> "1280"
}

private fun compressionPolicyLabel(policy: CompressionPolicy): String = when (policy) {
    CompressionPolicy.COMPATIBILITY -> "Compatibility"
    CompressionPolicy.STRICT -> "Strict"
}

private fun compressionPolicyExplanation(policy: CompressionPolicy): String = when (policy) {
    CompressionPolicy.COMPATIBILITY ->
        "Allows server-to-client LZO compression for provider support, so profiles that " +
            "receive compressed traffic keep working. Symmetric compression is always refused. " +
            "This is the compatible default."
    CompressionPolicy.STRICT ->
        "Refuses any server-pushed compression. A server that requires compression fails the " +
            "connection with an explicit error instead of silently receiving compressed traffic."
}

private fun cipherPolicyLabel(policy: CipherPolicy): String = when (policy) {
    CipherPolicy.PROFILE -> "Profile"
    CipherPolicy.AEAD_ONLY -> "AEAD only"
}

private fun cipherPolicyExplanation(policy: CipherPolicy): String = when (policy) {
    CipherPolicy.PROFILE ->
        "Uses the data-cipher policy from the profile. Legacy ciphers are negotiated when the " +
            "server pushes them."
    CipherPolicy.AEAD_ONLY ->
        "Only AEAD data ciphers (AES-GCM or ChaCha20-Poly1305) are accepted. A profile or server " +
            "that pushes a legacy cipher fails visibly instead of negotiating it."
}

private fun alwaysOnTitle(state: SystemVpnState): String = when (state) {
    SystemVpnState.ALWAYS_ON -> "Always-on is set for this app"
    SystemVpnState.ALWAYS_ON_LOCKDOWN -> "Always-on + lockdown is set for this app"
    SystemVpnState.OFF -> "Always-on is not set for this app"
    SystemVpnState.OTHER_APP -> "Another app is the Always-on VPN"
    SystemVpnState.UNKNOWN -> "System VPN state could not be detected"
}

private fun alwaysOnChip(state: SystemVpnState): String = when (state) {
    SystemVpnState.ALWAYS_ON -> "Always-on"
    SystemVpnState.ALWAYS_ON_LOCKDOWN -> "Lockdown"
    SystemVpnState.OFF -> "Off"
    SystemVpnState.OTHER_APP -> "Other app"
    SystemVpnState.UNKNOWN -> "Unknown"
}

/**
 * Honest caveat per detected state. The app never claims a guaranteed kill
 * switch: enforcement belongs to Android, and Always-on without lockdown does
 * not block traffic while the tunnel is down.
 */
private fun alwaysOnCaveat(state: SystemVpnState): String = when (state) {
    SystemVpnState.ALWAYS_ON_LOCKDOWN ->
        "Lockdown is the closest thing to a system kill switch, but Android still owns " +
            "enforcement. Captive portals may need the in-app pause/sign-in flow."
    SystemVpnState.ALWAYS_ON ->
        "Always-on restarts the VPN after boot and network changes, but it is not a kill switch. " +
            "Enable lockdown (\"Block connections without VPN\") in system settings for " +
            "system-level blocking."
    SystemVpnState.OFF ->
        "Traffic is unprotected whenever the VPN is disconnected. Android owns this setting; " +
            "this app only links you to it."
    SystemVpnState.OTHER_APP ->
        "Another app is configured, so this app does not start automatically. Android owns this " +
            "setting."
    SystemVpnState.UNKNOWN ->
        "Detection is best-effort because Android hides these settings. Check the system VPN " +
            "screen to confirm what is enabled."
}
