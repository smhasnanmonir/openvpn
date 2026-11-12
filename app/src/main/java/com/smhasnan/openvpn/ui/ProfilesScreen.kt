package com.smhasnan.openvpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smhasnan.openvpn.config.ProfileSummary
import com.smhasnan.openvpn.ui.components.DeleteProfileDialog
import com.smhasnan.openvpn.ui.components.EmojiPickerDialog
import com.smhasnan.openvpn.ui.components.EmptyState
import com.smhasnan.openvpn.ui.components.MetaChip
import com.smhasnan.openvpn.ui.components.ProfileAvatar
import com.smhasnan.openvpn.ui.components.RenameDialog
import com.smhasnan.openvpn.ui.components.ScreenHeader
import com.smhasnan.openvpn.ui.components.SelectedCheck
import com.smhasnan.openvpn.ui.components.SectionCard
import com.smhasnan.openvpn.ui.theme.Dimens

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
        contentPadding = PaddingValues(
            start = Dimens.ScreenPadding,
            end = Dimens.ScreenPadding,
            top = 14.dp,
            bottom = 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.CardGap),
    ) {
        item {
            ScreenHeader(
                eyebrow = "Library",
                title = "Profiles",
                subtitle = if (profiles.isEmpty()) {
                    "Import your first OpenVPN profile"
                } else {
                    "${profiles.size} profile${if (profiles.size == 1) "" else "s"} on this device"
                },
                action = {
                    Button(
                        onClick = onImport,
                        enabled = !importing,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.height(42.dp),
                    ) {
                        if (importing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = if (importing) "Importing" else "Import",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                },
            )
        }

        if (profiles.size > 4) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 11.dp),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            decorationBox = { innerTextField ->
                                if (query.isEmpty()) {
                                    Text(
                                        text = "Search profiles or servers",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                innerTextField()
                            },
                        )
                    }
                }
            }
        }

        if (profiles.isEmpty()) {
            item {
                SectionCard {
                    EmptyState(
                        icon = { ProfileAvatar(emoji = "🛡️", size = 52.dp) },
                        title = "No profiles yet",
                        message = "Import one or more .ovpn files to get started. Include external certificate or key files in the same picker.",
                        actionLabel = if (importing) "Importing…" else "Import .ovpn",
                        onAction = if (importing) null else onImport,
                        iconBackground = false,
                    )
                }
            }
        } else if (filtered.isEmpty()) {
            item {
                SectionCard {
                    EmptyState(
                        icon = { ProfileAvatar(emoji = "🔎", size = 52.dp) },
                        title = "No matches",
                        message = "No profile matches “$query”.",
                        actionLabel = "Clear search",
                        onAction = { query = "" },
                        iconBackground = false,
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

@OptIn(ExperimentalLayoutApi::class)
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
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminanceCompat() < 0.5f
    val selectedContainer = scheme.primaryContainer.copy(alpha = if (dark) 0.5f else 0.35f)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) selectedContainer else scheme.surface,
        border = if (selected) {
            BorderStroke(1.dp, scheme.primary.copy(alpha = 0.55f))
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileAvatar(profile.emoji, size = 46.dp, selected = selected)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (selected) {
                        Spacer(Modifier.width(6.dp))
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
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MetaChip(authModeLabel(profile))
                    if (profile.metadata.staticChallenge != null) {
                        MetaChip("Static challenge")
                    }
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

private fun authModeLabel(profile: ProfileSummary): String = when {
    profile.metadata.externalPki -> "External PKI · unsupported"
    profile.metadata.clientCertNotRequired -> "Username & password"
    !profile.metadata.autologin -> "Requires sign-in"
    profile.metadata.privateKeyPasswordRequired -> "Private key password"
    else -> "Certificate"
}

private fun Color.luminanceCompat(): Float {
    fun channel(value: Float): Float =
        if (value <= 0.03928f) value / 12.92f else ((value + 0.055f) / 1.055f).let { it * it * it }
    return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
}
