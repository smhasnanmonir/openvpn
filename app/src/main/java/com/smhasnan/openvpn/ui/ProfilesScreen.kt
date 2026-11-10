package com.smhasnan.openvpn.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smhasnan.openvpn.config.ProfileSummary
import com.smhasnan.openvpn.ui.components.DeleteProfileDialog
import com.smhasnan.openvpn.ui.components.EmojiPickerDialog
import com.smhasnan.openvpn.ui.components.EmptyState
import com.smhasnan.openvpn.ui.components.ProfileAvatar
import com.smhasnan.openvpn.ui.components.RenameDialog
import com.smhasnan.openvpn.ui.components.ScreenHeader
import com.smhasnan.openvpn.ui.components.SelectedCheck
import com.smhasnan.openvpn.ui.components.SectionCard
import java.util.Locale

@Composable
fun ProfilesScreen(
    profiles: List<ProfileSummary>,
    selectedId: String?,
    importing: Boolean,
    onImport: () -> Unit,
    onSelect: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onSetEmoji: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ProfileSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ProfileSummary?>(null) }
    var emojiTarget by remember { mutableStateOf<ProfileSummary?>(null) }

    val filtered = remember(profiles, query) {
        if (query.isBlank()) profiles else profiles.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.metadata.serverHost?.contains(query, ignoreCase = true) == true
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(
                title = "Profiles",
                subtitle = if (profiles.isEmpty()) {
                    "Import your first OpenVPN profile"
                } else {
                    "${profiles.size} profile${if (profiles.size == 1) "" else "s"} on this device"
                },
                action = {
                    Button(onClick = onImport, enabled = !importing) {
                        if (importing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (importing) "Importing" else "Import")
                    }
                },
            )
        }

        if (profiles.size > 4) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search profiles or servers") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    shape = RoundedCornerShape(16.dp),
                )
            }
        }

        if (profiles.isEmpty()) {
            item {
                SectionCard {
                    EmptyState(
                        icon = { ProfileAvatar(emoji = "🛡️", size = 42.dp) },
                        title = "No profiles yet",
                        message = "Import one or more .ovpn files. If a profile references external certificate or key files, select them together in the file picker.",
                        actionLabel = if (importing) "Importing…" else "Import .ovpn",
                        onAction = if (importing) null else onImport,
                    )
                }
            }
        } else if (filtered.isEmpty()) {
            item {
                SectionCard {
                    EmptyState(
                        icon = { ProfileAvatar(emoji = "🔎", size = 42.dp) },
                        title = "No matches",
                        message = "No profile matches “$query”.",
                        actionLabel = "Clear search",
                        onAction = { query = "" },
                    )
                }
            }
        } else {
            items(filtered, key = { it.id }) { profile ->
                ProfileCard(
                    profile = profile,
                    selected = profile.id == selectedId,
                    onSelect = { onSelect(profile.id) },
                    onRename = { renameTarget = profile },
                    onSetEmoji = { emojiTarget = profile },
                    onDelete = { deleteTarget = profile },
                )
            }
        }

        item {
            Text(
                "Profiles are stored encrypted in app-private storage. Import copies only the profile you select; no files are written to shared storage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
            )
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            currentName = target.name,
            onDismiss = { renameTarget = null },
            onRename = { newName ->
                onRename(target.id, newName)
                renameTarget = null
            },
        )
    }
    deleteTarget?.let { target ->
        DeleteProfileDialog(
            profileName = target.name,
            onDismiss = { deleteTarget = null },
            onDelete = {
                onDelete(target.id)
                deleteTarget = null
            },
        )
    }
    emojiTarget?.let { target ->
        EmojiPickerDialog(
            current = target.emoji,
            onDismiss = { emojiTarget = null },
            onPick = { emoji ->
                onSetEmoji(target.id, emoji)
                emojiTarget = null
            },
        )
    }
}

@Composable
private fun ProfileCard(
    profile: ProfileSummary,
    selected: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onSetEmoji: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(22.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.44f)
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileAvatar(profile.emoji, size = 50.dp, selected = selected)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (selected) {
                        Spacer(Modifier.width(8.dp))
                        SelectedCheck(true)
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = listOfNotNull(
                        profile.metadata.displayServer,
                        profile.metadata.protocolLabel,
                    ).joinToString(" · ").ifBlank { "Server details unavailable" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when {
                        profile.metadata.externalPki -> "External PKI · unsupported"
                        !profile.metadata.autologin -> "Requires sign-in"
                        profile.metadata.privateKeyPasswordRequired -> "Private key password"
                        else -> "Certificate / auto-login"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (profile.metadata.staticChallenge != null) {
                    Text(
                        "Static challenge",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Profile actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Change icon") },
                        onClick = { menuOpen = false; onSetEmoji() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

private fun String.hostLabel(): String =
    this.lowercase(Locale.ROOT).ifBlank { "unknown" }
