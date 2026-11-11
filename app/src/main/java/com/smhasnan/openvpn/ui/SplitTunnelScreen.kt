package com.smhasnan.openvpn.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smhasnan.openvpn.settings.AppSettings
import com.smhasnan.openvpn.settings.SplitTunnelMode
import com.smhasnan.openvpn.ui.components.DividerLine
import com.smhasnan.openvpn.ui.components.EmptyState
import com.smhasnan.openvpn.ui.components.MessageBanner
import com.smhasnan.openvpn.ui.components.SectionCard
import com.smhasnan.openvpn.ui.components.SectionLabel
import com.smhasnan.openvpn.ui.theme.Dimens
import com.smhasnan.openvpn.vpn.VpnState
import java.util.Locale

/** Human label for a split-tunnel mode, used by Settings and this screen. */
internal fun splitTunnelModeLabel(mode: SplitTunnelMode): String = when (mode) {
    SplitTunnelMode.OFF -> "Off"
    SplitTunnelMode.INCLUDE_SELECTED -> "Only selected apps"
    SplitTunnelMode.EXCLUDE_SELECTED -> "All apps except selected"
}

/** Short summary of the saved policy for the Settings row. */
internal fun splitTunnelModeSummary(settings: AppSettings): String {
    val count = settings.splitTunnelPackages.size
    return when (settings.splitTunnelMode) {
        SplitTunnelMode.OFF -> "Every app uses the tunnel."
        SplitTunnelMode.INCLUDE_SELECTED ->
            if (count == 0) {
                "No apps selected: every app currently bypasses the tunnel."
            } else {
                "$count selected ${if (count == 1) "app" else "apps"} use the tunnel; all other apps bypass it."
            }
        SplitTunnelMode.EXCLUDE_SELECTED ->
            if (count == 0) {
                "No apps bypass the tunnel."
            } else {
                "$count selected ${if (count == 1) "app" else "apps"} bypass the tunnel."
            }
    }
}

/**
 * Persistent leak warning shown on Home and in Settings while per-app routing is
 * active. Returns `null` when nothing bypasses the tunnel.
 */
internal fun splitTunnelLeakWarning(settings: AppSettings): String? {
    val count = settings.splitTunnelPackages.size
    return when (settings.splitTunnelMode) {
        SplitTunnelMode.OFF -> null
        SplitTunnelMode.INCLUDE_SELECTED ->
            if (count == 0) {
                "Split tunneling is on with no apps selected: every app bypasses the VPN and is not protected."
            } else {
                "Split tunneling: only $count selected ${if (count == 1) "app uses" else "apps use"} the tunnel. " +
                    "All other apps bypass it and are not protected."
            }
        SplitTunnelMode.EXCLUDE_SELECTED ->
            if (count == 0) {
                null
            } else {
                "Split tunneling: $count selected ${if (count == 1) "app bypasses" else "apps bypass"} the tunnel. " +
                    "Their traffic is not protected."
            }
    }
}

/**
 * Number of installed apps whose traffic bypasses the tunnel for the current
 * policy, or `null` when the installed list has not been loaded yet.
 */
private fun bypassCount(
    mode: SplitTunnelMode,
    selected: Set<String>,
    apps: List<InstalledApp>,
): Int? {
    if (apps.isEmpty()) {
        return when (mode) {
            SplitTunnelMode.EXCLUDE_SELECTED -> selected.size
            else -> null
        }
    }
    return when (mode) {
        SplitTunnelMode.OFF -> null
        SplitTunnelMode.INCLUDE_SELECTED -> apps.count { it.packageName !in selected }
        SplitTunnelMode.EXCLUDE_SELECTED -> apps.count { it.packageName in selected }
    }
}

/**
 * Per-app split-tunneling editor (#17). The policy is saved as soon as a control
 * changes and is applied when the TUN is next created; a reconnect entry point is
 * offered while a session is connected. The screen always states which apps
 * bypass the tunnel so a policy change can never silently leak.
 */
@Composable
fun SplitTunnelScreen(
    settings: AppSettings,
    apps: List<InstalledApp>,
    appsLoading: Boolean,
    vpnState: VpnState,
    onBack: () -> Unit,
    onModeChange: (SplitTunnelMode) -> Unit,
    onPackagesChange: (Set<String>) -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var search by rememberSaveable { mutableStateOf("") }
    val selected = settings.splitTunnelPackages
    val mode = settings.splitTunnelMode
    val installedPackages = remember(apps) { apps.mapTo(HashSet()) { it.packageName } }
    val uninstalled = remember(selected, apps) {
        if (apps.isEmpty()) emptySet() else selected - installedPackages
    }
    val filtered = remember(apps, search) {
        val query = search.trim().lowercase(Locale.ROOT)
        if (query.isEmpty()) {
            apps
        } else {
            apps.filter {
                it.label.lowercase(Locale.ROOT).contains(query) ||
                    it.packageName.lowercase(Locale.ROOT).contains(query)
            }
        }
    }
    val bypassing = bypassCount(mode, selected, apps)
    val connected = vpnState == VpnState.CONNECTED

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to settings",
                )
            }
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Per-app routing",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Choose which apps use the VPN tunnel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = Dimens.ScreenPadding,
                end = Dimens.ScreenPadding,
                top = 8.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.SectionGap),
        ) {
            item {
                SectionLabel("Mode")
                SectionCard {
                    SplitTunnelMode.entries.forEach { candidate ->
                        ModeOption(
                            title = splitTunnelModeLabel(candidate),
                            subtitle = when (candidate) {
                                SplitTunnelMode.OFF -> "Every app uses the VPN tunnel."
                                SplitTunnelMode.INCLUDE_SELECTED ->
                                    "Only the selected apps use the tunnel; every other app bypasses it."
                                SplitTunnelMode.EXCLUDE_SELECTED ->
                                    "The selected apps bypass the tunnel; every other app uses it."
                            },
                            selected = mode == candidate,
                            onClick = { onModeChange(candidate) },
                        )
                        if (candidate != SplitTunnelMode.entries.last()) {
                            DividerLine()
                        }
                    }
                }
            }

            if (mode != SplitTunnelMode.OFF) {
                item {
                    if (mode == SplitTunnelMode.INCLUDE_SELECTED && selected.isEmpty()) {
                        MessageBanner(
                            message = "No apps are selected, so every app bypasses the VPN and is not protected. " +
                                "Select at least one app, or turn split tunneling off.",
                            isError = true,
                        )
                    } else if (bypassing != null && bypassing > 0) {
                        MessageBanner(
                            message = "$bypassing ${if (bypassing == 1) "app bypasses" else "apps bypass"} the tunnel; " +
                                "their traffic is not protected.",
                            isError = true,
                        )
                    } else if (mode == SplitTunnelMode.EXCLUDE_SELECTED) {
                        MessageBanner(
                            message = "No apps bypass the tunnel.",
                            isError = false,
                        )
                    }
                }

                if (uninstalled.isNotEmpty()) {
                    item {
                        MessageBanner(
                            message = "${uninstalled.size} selected " +
                                "${if (uninstalled.size == 1) "app is" else "apps are"} no longer installed " +
                                "and will be ignored.",
                            isError = false,
                            actionLabel = "Remove",
                            onAction = { onPackagesChange(selected - uninstalled) },
                        )
                    }
                }

                item {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        label = { Text("Search apps") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = when {
                                appsLoading -> "Loading installed apps…"
                                filtered.isEmpty() && search.isNotBlank() -> "No matching apps"
                                else -> "${filtered.size} ${if (filtered.size == 1) "app" else "apps"}"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                onPackagesChange(selected + filtered.map { it.packageName })
                            },
                            enabled = filtered.isNotEmpty(),
                        ) { Text("Select all") }
                        TextButton(
                            onClick = { onPackagesChange(emptySet()) },
                            enabled = selected.isNotEmpty(),
                        ) { Text("Clear") }
                    }
                }

                if (appsLoading && apps.isEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "Reading installed apps…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (filtered.isEmpty()) {
                    item {
                        EmptyState(
                            icon = {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(30.dp),
                                )
                            },
                            title = if (search.isBlank()) "No apps found" else "No matching apps",
                            message = if (search.isBlank()) {
                                "This device reports no launchable apps to select."
                            } else {
                                "Try a different name or package."
                            },
                        )
                    }
                } else {
                    items(filtered, key = { it.packageName }) { app ->
                        AppSelectionRow(
                            app = app,
                            checked = app.packageName in selected,
                            onToggle = {
                                onPackagesChange(
                                    if (app.packageName in selected) {
                                        selected - app.packageName
                                    } else {
                                        selected + app.packageName
                                    },
                                )
                            },
                        )
                    }
                }
            }

            item {
                SectionLabel("When it applies")
                SectionCard {
                    Text(
                        text = if (connected) {
                            "Changes apply on the next connection. Reconnect to apply them now."
                        } else {
                            "Changes are saved and apply the next time you connect."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (connected) {
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onReconnect, shape = RoundedCornerShape(14.dp)) {
                            Text("Reconnect now")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Routing is enforced by Android's VpnService. Apps that bypass the tunnel " +
                            "use the underlying network directly and are not protected by the VPN.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AppSelectionRow(
    app: InstalledApp,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
