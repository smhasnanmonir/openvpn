package com.smhasnan.openvpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smhasnan.openvpn.config.EmojiCatalog
import com.smhasnan.openvpn.config.ProfileNameValidator

data class CredentialsInput(
    val username: String?,
    val password: String?,
    val response: String?,
    val privateKeyPassword: String?,
    val remember: Boolean,
)

data class CredentialPrompt(
    val profileId: String,
    val profileName: String,
    val emoji: String,
    val needsUsername: Boolean,
    val needsPassword: Boolean,
    val needsResponse: Boolean,
    val needsPrivateKeyPassword: Boolean,
    val challengeText: String? = null,
    val challengeEcho: Boolean = true,
    val allowPasswordSave: Boolean = true,
)

private val DialogShape = RoundedCornerShape(22.dp)
private val DialogActionShape = RoundedCornerShape(14.dp)

@Composable
fun CredentialsDialog(
    prompt: CredentialPrompt,
    onDismiss: () -> Unit,
    onSubmit: (CredentialsInput) -> Unit,
) {
    var username by remember(prompt.profileId) { mutableStateOf("") }
    var password by remember(prompt.profileId) { mutableStateOf("") }
    var response by remember(prompt.profileId) { mutableStateOf("") }
    var keyPassword by remember(prompt.profileId) { mutableStateOf("") }
    var remember by remember(prompt.profileId) { mutableStateOf(prompt.allowPasswordSave) }
    var revealPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar(prompt.emoji, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Sign in", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        prompt.profileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (prompt.challengeText != null) {
                    MessageBanner(
                        message = prompt.challengeText,
                        isError = false,
                    )
                }
                if (prompt.needsUsername) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it; error = null },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                }
                if (prompt.needsPassword) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; error = null },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = if (revealPassword) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            TextButton(onClick = { revealPassword = !revealPassword }) {
                                Text(if (revealPassword) "Hide" else "Show")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next,
                        ),
                    )
                }
                if (prompt.needsResponse) {
                    OutlinedTextField(
                        value = response,
                        onValueChange = { response = it; error = null },
                        label = { Text("Challenge response") },
                        singleLine = true,
                        visualTransformation = if (prompt.challengeEcho) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                }
                if (prompt.needsPrivateKeyPassword) {
                    OutlinedTextField(
                        value = keyPassword,
                        onValueChange = { keyPassword = it; error = null },
                        label = { Text("Private key password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                    )
                }
                if (prompt.needsUsername || prompt.needsPassword || prompt.needsPrivateKeyPassword) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { remember = !remember }
                            .padding(vertical = 4.dp),
                    ) {
                        Checkbox(checked = remember, onCheckedChange = { remember = it })
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Remember credentials on this device",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Stored in encrypted app-private storage using Android Keystore. Challenge responses are never saved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (error != null) {
                    Text(
                        text = error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val message = when {
                        prompt.needsUsername && username.isBlank() -> "Enter your username."
                        prompt.needsPassword && password.isBlank() -> "Enter your password."
                        prompt.needsResponse && response.isBlank() -> "Enter the challenge response."
                        prompt.needsPrivateKeyPassword && keyPassword.isBlank() ->
                            "Enter the private key password."
                        else -> null
                    }
                    if (message != null) {
                        error = message
                    } else {
                        onSubmit(
                            CredentialsInput(
                                username = username.takeIf { prompt.needsUsername },
                                password = password.takeIf { prompt.needsPassword },
                                response = response.takeIf { prompt.needsResponse },
                                privateKeyPassword = keyPassword.takeIf { prompt.needsPrivateKeyPassword },
                                remember = remember,
                            ),
                        )
                    }
                },
                shape = DialogActionShape,
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
fun ChallengeDialog(
    challenge: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var response by remember(challenge) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Server challenge") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(challenge, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = response,
                    onValueChange = { response = it; error = null },
                    label = { Text("Response") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (response.isBlank()) error = "Enter the requested response."
                    else onSubmit(response)
                },
                shape = DialogActionShape,
            ) { Text("Submit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var value by remember(currentName) { mutableStateOf(currentName) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Rename profile") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; error = null },
                    label = { Text("Display name") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Only the display name changes. VPN directives are not modified.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (error != null) {
                    Text(
                        error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    ProfileNameValidator.validate(value).onSuccess { onRename(it) }
                        .onFailure { error = it.message }
                },
                shape = DialogActionShape,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun EmojiPickerDialog(
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var custom by remember { mutableStateOf("") }
    val customValue = custom.trim()
    val customValid = EmojiCatalog.isValidCustom(customValue)
    val customInvalid = custom.isNotEmpty() && !customValid

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Choose an icon") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    modifier = Modifier.height(220.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(EmojiCatalog.choices) { emoji ->
                        val selected = emoji == current
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainer,
                                )
                                .border(
                                    if (selected) 2.dp else 0.dp,
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable { onPick(emoji) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = emoji,
                                fontSize = 24.sp,
                            )
                        }
                    }
                }
                HorizontalDivider()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Custom icon",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .border(
                                width = 1.dp,
                                color = if (customInvalid) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(14.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (customValid) {
                            Text(text = customValue, fontSize = 18.sp, maxLines = 1)
                        } else {
                            Text(
                                text = "?",
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    placeholder = { Text("Paste or type any emoji") },
                    singleLine = true,
                    isError = customInvalid,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (customInvalid) {
                    Text(
                        text = "Use 1–2 emoji.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onPick(EmojiCatalog.normalized(custom)) },
                enabled = customValid,
                shape = DialogActionShape,
            ) { Text("Use") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun DeleteProfileDialog(
    profileName: String,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Delete profile?") },
        text = {
            Text(
                "“$profileName” and its saved credentials will be permanently removed from this device.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Button(
                onClick = onDelete,
                shape = DialogActionShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } },
    )
}
