package com.smhasnan.openvpn.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.provider.Settings
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smhasnan.openvpn.appContainer
import com.smhasnan.openvpn.config.ImportOutcome
import com.smhasnan.openvpn.nativevpn.NativeEngine
import com.smhasnan.openvpn.ui.components.ChallengeDialog
import com.smhasnan.openvpn.ui.components.CredentialsDialog
import com.smhasnan.openvpn.vpn.CredentialHandoff
import com.smhasnan.openvpn.vpn.MyVpnService
import com.smhasnan.openvpn.vpn.VpnState
import com.smhasnan.openvpn.vpn.VpnTileService

private enum class AppTab(val label: String) {
    HOME("Home"),
    PROFILES("Profiles"),
    LOGS("Logs"),
    SETTINGS("Settings"),
}

@Composable
fun AppRoot(
    onBiometricChangeRequest: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: AppViewModel = viewModel()
    val context = LocalContext.current
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedId.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snapshot by viewModel.vpn.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val ipState by viewModel.ipState.collectAsStateWithLifecycle()
    val credentialPrompt by viewModel.credentialPrompt.collectAsStateWithLifecycle()
    val importReport by viewModel.importReport.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val selfTest by viewModel.selfTest.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(AppTab.HOME) }
    var pendingConsent by remember { mutableStateOf<ConnectRequest?>(null) }
    var challengeDialogVisible by remember { mutableStateOf(snapshot.state == VpnState.NEEDS_CHALLENGE) }
    val snackbarHostState = remember { SnackbarHostState() }

    val selectedProfile = remember(profiles, selectedId) {
        profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            viewModel.message("Notifications are off. Quick controls will not be visible.", isError = true)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importProfiles(uris)
    }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val request = pendingConsent
        pendingConsent = null
        if (result.resultCode == Activity.RESULT_OK && request != null) {
            launchService(context, request, viewModel)
        } else {
            viewModel.onConsentDenied()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message.text, withDismissAction = true)
        }
    }

    LaunchedEffect(snapshot.state) {
        if (snapshot.state == VpnState.NEEDS_CHALLENGE) challengeDialogVisible = true
    }

    LaunchedEffect(Unit) {
        viewModel.connectRequests.collect { request ->
            val consent = VpnService.prepare(context)
            if (consent != null) {
                pendingConsent = request
                consentLauncher.launch(consent)
            } else {
                launchService(context, request, viewModel)
            }
        }
    }

    LaunchedEffect(snapshot.state) {
        if (snapshot.state == VpnState.NEEDS_CHALLENGE) {
            challengeDialogVisible = true
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            !context.appContainer.settings.notificationPermissionRequested()
        ) {
            context.appContainer.settings.markNotificationPermissionRequested()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = {
                            Icon(
                                imageVector = when (item) {
                                    AppTab.HOME -> Icons.Filled.Home
                                    AppTab.PROFILES -> Icons.Filled.Person
                                    AppTab.LOGS -> Icons.Filled.List
                                    AppTab.SETTINGS -> Icons.Filled.Settings
                                },
                                contentDescription = item.label,
                            )
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        when (tab) {
            AppTab.HOME -> HomeScreen(
                snapshot = snapshot,
                profile = selectedProfile,
                ipState = ipState,
                importing = importing,
                onConnect = viewModel::requestConnect,
                onDisconnect = viewModel::disconnect,
                onReconnect = viewModel::reconnect,
                onRefreshIp = viewModel::checkIp,
                onImport = { importLauncher.launch(arrayOf("*/*")) },
                onOpenProfiles = { tab = AppTab.PROFILES },
                onOpenLogs = { tab = AppTab.LOGS },
                onRespondChallenge = { challengeDialogVisible = true },
                modifier = Modifier.padding(innerPadding),
            )

            AppTab.PROFILES -> ProfilesScreen(
                profiles = profiles,
                selectedId = selectedProfile?.id,
                importing = importing,
                onImport = { importLauncher.launch(arrayOf("*/*")) },
                onSelect = viewModel::selectProfile,
                onRename = viewModel::renameProfile,
                onDelete = viewModel::deleteProfile,
                onSetEmoji = viewModel::setEmoji,
                modifier = Modifier.padding(innerPadding),
            )

            AppTab.LOGS -> LogsScreen(
                logs = logs,
                onClear = viewModel::clearLogs,
                onMessage = { viewModel.message(it) },
                modifier = Modifier.padding(innerPadding),
            )

            AppTab.SETTINGS -> SettingsScreen(
                settings = settings,
                selfTest = selfTest,
                engineInfo = remember { NativeEngine.engineInfo() },
                onThemeChange = viewModel::updateTheme,
                onBiometricToggle = onBiometricChangeRequest,
                onBlockUnusedFamiliesToggle = viewModel::updateBlockUnusedFamilies,
                onAutoCheckIpToggle = viewModel::updateAutoCheckIp,
                onRunSelfTest = viewModel::runSelfTest,
                notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED,
                onOpenNotificationSettings = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    runCatching { context.startActivity(intent) }.onFailure {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ),
                            )
                        }
                    }
                },
                modifier = Modifier.padding(innerPadding),
            )
        }
    }

    credentialPrompt?.let { prompt ->
        CredentialsDialog(
            prompt = prompt,
            onDismiss = viewModel::dismissCredentialPrompt,
            onSubmit = viewModel::submitCredentials,
        )
    }

    if (snapshot.state == VpnState.NEEDS_CHALLENGE && snapshot.challenge != null && challengeDialogVisible) {
        ChallengeDialog(
            challenge = snapshot.challenge!!,
            onDismiss = { challengeDialogVisible = false },
            onSubmit = { response ->
                challengeDialogVisible = false
                viewModel.submitChallenge(response)
            },
        )
    }

    importReport?.let { report ->
        ImportReportDialog(report = report, onDismiss = viewModel::dismissImportReport)
    }
}

private fun launchService(
    context: android.content.Context,
    request: ConnectRequest,
    viewModel: AppViewModel,
) {
    CredentialHandoff.put(request.profileId, request.credentials)
    runCatching {
        MyVpnService.connect(context, request.profileId)
        VpnTileService.requestRefresh(context)
    }.onFailure {
        viewModel.onConnectionStartFailed(
            "Could not start the VPN service: ${it.javaClass.simpleName}",
        )
    }
}

@Composable
private fun ImportReportDialog(report: ImportReport, onDismiss: () -> Unit) {
    val successes = report.results.filterIsInstance<ImportOutcome.Success>()
    val failures = report.results.filterIsInstance<ImportOutcome.Failure>()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    failures.isEmpty() -> "${successes.size} profile${if (successes.size == 1) "" else "s"} imported"
                    successes.isEmpty() -> "Import failed"
                    else -> "Import finished with issues"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                report.results.forEach { result ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = if (result is ImportOutcome.Success) {
                                Icons.Filled.CheckCircle
                            } else {
                                Icons.Filled.Warning
                            },
                            contentDescription = null,
                            tint = if (result is ImportOutcome.Success) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                result.fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                when (result) {
                                    is ImportOutcome.Success -> if (result.alreadyImported) {
                                        "Already imported. Selected the existing profile."
                                    } else {
                                        "Imported successfully."
                                    }
                                    is ImportOutcome.Failure -> result.message
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (result is ImportOutcome.Success) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }
                }
                if (failures.isNotEmpty()) {
                    Text(
                        "Profiles with missing external files are rejected rather than silently imported. Select companion files together with the .ovpn file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Done") }
        },
    )
}
