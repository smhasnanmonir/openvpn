package com.smhasnan.openvpn.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smhasnan.openvpn.config.ProfileSummary

/**
 * Bottom sheet used by Home to switch the active profile without leaving the
 * connection screen. Selection is refused by the caller while a tunnel is
 * active, so the rows show the lock state instead of failing afterwards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSwitcherSheet(
    profiles: List<ProfileSummary>,
    selectedId: String?,
    locked: Boolean,
    onSelect: (String) -> Unit,
    onManageProfiles: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            Text("Switch profile", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(3.dp))
            Text(
                text = when {
                    locked -> "Disconnect the VPN to switch to another profile."
                    profiles.size == 1 -> "1 profile on this device"
                    else -> "${profiles.size} profiles on this device"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (locked) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.height(14.dp))
            LazyColumn(
                modifier = Modifier.heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileSwitchRow(
                        profile = profile,
                        selected = profile.id == selectedId,
                        enabled = !locked,
                        onClick = { onSelect(profile.id) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            DividerLine()
            LinkRow(
                title = "Manage profiles",
                subtitle = "Rename, change icons, import, or delete profiles",
                trailingLabel = "Open",
                onClick = onManageProfiles,
            )
        }
    }
}

@Composable
private fun ProfileSwitchRow(
    profile: ProfileSummary,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) {
            scheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            scheme.surfaceContainerLow
        },
        border = if (selected) {
            BorderStroke(1.dp, scheme.primary.copy(alpha = 0.5f))
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileAvatar(profile.emoji, size = 40.dp, selected = selected)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOfNotNull(
                        profile.metadata.displayServer,
                        profile.metadata.protocolLabel,
                    ).joinToString(" · ").ifBlank { "Server details unavailable" },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                SelectedCheck(true)
            }
        }
    }
}
