package com.smhasnan.openvpn.ui

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smhasnan.openvpn.BuildConfig
import com.smhasnan.openvpn.settings.AppSettings
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
    onThemeChange: (ThemeMode) -> Unit,
    onBiometricToggle: (Boolean) -> Unit,
    onBlockUnusedFamiliesToggle: (Boolean) -> Unit,
    onAutoCheckIpToggle: (Boolean) -> Unit,
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
            SectionLabel("Network")
            SectionCard {
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
            SectionLabel("System VPN")
            SectionCard {
                LinkRow(
                    title = "Always-on VPN and lockdown",
                    subtitle = "Managed by Android. This app does not claim a guaranteed kill switch.",
                    onClick = {
                        val intent = Intent(Settings.ACTION_VPN_SETTINGS)
                        runCatching { context.startActivity(intent) }
                            .onFailure {
                                runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                            }
                    },
                )
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
}
