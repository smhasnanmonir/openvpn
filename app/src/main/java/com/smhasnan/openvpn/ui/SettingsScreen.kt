package com.smhasnan.openvpn.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smhasnan.openvpn.BuildConfig
import com.smhasnan.openvpn.settings.AppSettings
import com.smhasnan.openvpn.settings.ThemeMode
import com.smhasnan.openvpn.ui.components.DividerLine
import com.smhasnan.openvpn.ui.components.InfoRow
import com.smhasnan.openvpn.ui.components.MessageBanner
import com.smhasnan.openvpn.ui.components.ScreenHeader
import com.smhasnan.openvpn.ui.components.SectionCard

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
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ScreenHeader(
                title = "Settings",
                subtitle = "Security, network policy, and diagnostics",
            )
        }

        item {
            SectionCard {
                Text("Appearance", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Use the system theme or keep a consistent look.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { onThemeChange(mode) },
                            label = {
                                Text(
                                    when (mode) {
                                        ThemeMode.SYSTEM -> "System"
                                        ThemeMode.LIGHT -> "Light"
                                        ThemeMode.DARK -> "Dark"
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }

        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("App lock", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Require fingerprint or device credential when opening this app",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.biometricLock,
                        onCheckedChange = onBiometricToggle,
                    )
                }
                Spacer(Modifier.height(12.dp))
                DividerLine()
                Spacer(Modifier.height(12.dp))
                Text(
                    "The app lock protects the interface. An active VPN keeps running while the UI is locked. Saved profiles and credentials use AES-256-GCM keys held by Android Keystore and app-private storage excluded from backups.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard {
                Text("Network policy", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                SettingsSwitchRow(
                    title = "Block untunneled address families",
                    subtitle = "Keep Android's default VPN routing, which blocks a family when the tunnel has no address, route, or DNS for it. Turning this off may allow traffic over the underlying network.",
                    checked = settings.blockUnusedFamilies,
                    onCheckedChange = onBlockUnusedFamiliesToggle,
                )
                DividerLine()
                SettingsSwitchRow(
                    title = "Check public IP after connecting",
                    subtitle = "Makes one request to api.ipify.org through the active network to show the egress address.",
                    checked = settings.autoCheckIp,
                    onCheckedChange = onAutoCheckIpToggle,
                )
            }
        }

        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Notification controls", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (notificationsGranted) "Connected-state notification and quick actions are enabled." else "Notifications are disabled. Turn them on to disconnect from the notification panel.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!notificationsGranted) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onOpenNotificationSettings) {
                        Text("Open notification settings")
                    }
                }
            }
        }

        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Kill switch and always-on VPN", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Android's Always-on VPN and Lockdown features are controlled by the system. This app does not claim to provide a guaranteed kill switch. Configure Always-on VPN in system settings if you need system-level lockdown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = {
                    val intent = Intent(Settings.ACTION_VPN_SETTINGS)
                    runCatching { context.startActivity(intent) }
                        .onFailure {
                            runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                        }
                }) {
                    Text("Open system VPN settings")
                }
            }
        }

        item {
            SectionCard {
                Text("Engine diagnostics", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Verified by the pinned native OpenVPN 3 Core library.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                when (selfTest) {
                    SelfTestState.Idle -> Button(onClick = onRunSelfTest) {
                        Text("Run crypto self-test")
                    }
                    SelfTestState.Running -> Button(onClick = {}, enabled = false) {
                        Text("Running…")
                    }
                    is SelfTestState.Passed -> {
                        MessageBanner(message = selfTest.message, isError = false)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onRunSelfTest) { Text("Run again") }
                    }
                    is SelfTestState.Failed -> {
                        MessageBanner(message = selfTest.message, isError = true)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onRunSelfTest) { Text("Try again") }
                    }
                }
                Spacer(Modifier.height(14.dp))
                DividerLine()
                Spacer(Modifier.height(6.dp))
                InfoRow("OpenVPN Core", engineInfo["coreVersion"] ?: "unknown")
                InfoRow("Platform", engineInfo["platform"] ?: "unknown")
                InfoRow("Core revision", BuildConfig.OPENVPN_CORE_REVISION.take(12))
                InfoRow("App version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                InfoRow("License", "AGPL-3.0-only")
            }
        }

        item {
            SectionCard {
                Text("About this build", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "OpenVPN Client uses the official OpenVPN 3 Core engine for all protocol, TLS, certificate validation, and packet processing. No cryptography or VPN protocol code is implemented by this app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Profiles, credentials, and logs are never sent to a backend. Logs are kept in memory and sanitized before display.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
