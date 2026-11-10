package com.smhasnan.openvpn.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smhasnan.openvpn.appContainer
import com.smhasnan.openvpn.config.EmojiCatalog
import com.smhasnan.openvpn.config.ImportOutcome
import com.smhasnan.openvpn.config.ProfileCredentials
import com.smhasnan.openvpn.config.ProfileNameValidator
import com.smhasnan.openvpn.config.ProfileSummary
import com.smhasnan.openvpn.nativevpn.NativeEngine
import com.smhasnan.openvpn.ui.components.CredentialPrompt
import com.smhasnan.openvpn.ui.components.CredentialsInput
import com.smhasnan.openvpn.vpn.LogLevel
import com.smhasnan.openvpn.vpn.MyVpnService
import com.smhasnan.openvpn.vpn.PublicIpState
import com.smhasnan.openvpn.vpn.VpnController
import com.smhasnan.openvpn.vpn.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class UiMessage(val text: String, val isError: Boolean = false)

data class ImportReport(val fileName: String, val results: List<ImportOutcome>)

data class ConnectRequest(val profileId: String, val credentials: ProfileCredentials)

sealed interface SelfTestState {
    data object Idle : SelfTestState
    data object Running : SelfTestState
    data class Passed(val message: String) : SelfTestState
    data class Failed(val message: String) : SelfTestState
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val container = application.appContainer

    val profiles = container.profiles.profiles
    val selectedId = container.profiles.selectedId
    val settings = container.settings.settings
    val vpn = VpnController.snapshot
    val logs = VpnController.logs

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    private val _connectRequests = MutableSharedFlow<ConnectRequest>(extraBufferCapacity = 4)
    val connectRequests: SharedFlow<ConnectRequest> = _connectRequests.asSharedFlow()

    private val _credentialPrompt = MutableStateFlow<CredentialPrompt?>(null)
    val credentialPrompt: StateFlow<CredentialPrompt?> = _credentialPrompt.asStateFlow()

    private val _importReport = MutableStateFlow<ImportReport?>(null)
    val importReport: StateFlow<ImportReport?> = _importReport.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _ipState = MutableStateFlow(PublicIpState())
    val ipState: StateFlow<PublicIpState> = _ipState.asStateFlow()

    private val _selfTest = MutableStateFlow<SelfTestState>(SelfTestState.Idle)
    val selfTest: StateFlow<SelfTestState> = _selfTest.asStateFlow()

    private var promptBase = ProfileCredentials()
    private var lastVpnState = VpnState.DISCONNECTED
    private var ipCheckGeneration = 0L
    private val connectivityManager =
        application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    init {
        // AppContainer already refreshes profiles once per process; refreshing
        // again here re-read and re-decrypted every stored profile on each open.
        viewModelScope.launch {
            vpn.map { it.state }.distinctUntilChanged().collect { state ->
                val previous = lastVpnState
                if (state == VpnState.CONNECTED && previous != VpnState.CONNECTED) {
                    // The egress path just changed. Drop any in-flight direct
                    // result and either re-check through the tunnel or clear the
                    // address that no longer describes this device's egress.
                    if (container.settings.settingsNow().autoCheckIp) {
                        startIpCheck(force = true)
                    } else {
                        invalidateIpCheck()
                    }
                } else if (previous == VpnState.CONNECTED && state != VpnState.CONNECTED) {
                    invalidateIpCheck()
                }
                lastVpnState = state
            }
        }
    }

    fun message(text: String, isError: Boolean = false) {
        _messages.tryEmit(UiMessage(text, isError))
    }

    fun refreshProfiles() {
        viewModelScope.launch { container.profiles.refresh() }
    }

    fun selectProfile(id: String) {
        val active = VpnController.snapshot.value
        if (active.state.isActive && active.profileId != null && active.profileId != id) {
            message("Disconnect the VPN before switching profiles.", isError = true)
            return
        }
        viewModelScope.launch {
            container.profiles.select(id)
            message("Profile selected")
        }
    }

    fun requestConnect() {
        val profile = container.profiles.selectedProfile()
        if (profile == null) {
            message("Import a profile first.", isError = true)
            return
        }
        if (profile.metadata.externalPki) {
            message("External PKI profiles are not supported.", isError = true)
            return
        }
        viewModelScope.launch {
            val stored = container.profiles.loadCredentials(profile.id) ?: ProfileCredentials()
            val userlocked = profile.metadata.userlockedUsername
            promptBase = if (userlocked.isNullOrBlank()) stored else stored.copy(username = userlocked)
            val forceInteractive = VpnController.snapshot.value.state == VpnState.AUTH_FAILED
            val needsUsername = !profile.metadata.autologin &&
                (forceInteractive || (userlocked.isNullOrBlank() && stored.username.isNullOrBlank()))
            val needsPassword = !profile.metadata.autologin && (forceInteractive || stored.password.isNullOrBlank())
            val needsResponse = !profile.metadata.staticChallenge.isNullOrBlank()
            val needsKey = profile.metadata.privateKeyPasswordRequired &&
                stored.privateKeyPassword.isNullOrBlank()
            if (!needsUsername && !needsPassword && !needsResponse && !needsKey) {
                _connectRequests.emit(ConnectRequest(profile.id, promptBase))
            } else {
                _credentialPrompt.value = CredentialPrompt(
                    profileId = profile.id,
                    profileName = profile.name,
                    emoji = profile.emoji,
                    needsUsername = needsUsername,
                    needsPassword = needsPassword,
                    needsResponse = needsResponse,
                    needsPrivateKeyPassword = needsKey,
                    challengeText = profile.metadata.staticChallenge,
                    challengeEcho = profile.metadata.staticChallengeEcho,
                    allowPasswordSave = profile.metadata.allowPasswordSave,
                )
            }
        }
    }

    fun dismissCredentialPrompt() {
        _credentialPrompt.value = null
    }

    fun submitCredentials(input: CredentialsInput) {
        val prompt = _credentialPrompt.value ?: return
        val merged = ProfileCredentials(
            username = input.username ?: promptBase.username,
            password = input.password ?: promptBase.password,
            privateKeyPassword = input.privateKeyPassword ?: promptBase.privateKeyPassword,
            response = input.response,
        )
        _credentialPrompt.value = null
        val collectedCredentialFields = prompt.needsUsername ||
            prompt.needsPassword || prompt.needsPrivateKeyPassword
        viewModelScope.launch {
            if (input.remember) {
                container.profiles.saveCredentials(
                    prompt.profileId,
                    ProfileCredentials(
                        username = merged.username,
                        password = merged.password,
                        privateKeyPassword = merged.privateKeyPassword,
                    ),
                ).onFailure { message("Credentials could not be saved securely.", isError = true) }
            } else if (collectedCredentialFields) {
                container.profiles.deleteCredentials(prompt.profileId)
            }
            _connectRequests.emit(ConnectRequest(prompt.profileId, merged))
        }
    }

    fun submitChallenge(response: String) {
        VpnController.appendLog(LogLevel.INFO, "Submitting challenge response")
        MyVpnService.respondToChallenge(getApplication(), response)
    }

    fun onConsentDenied() {
        message("VPN permission is required to connect.", isError = true)
    }

    fun onConnectionStartFailed(reason: String) {
        message(reason, isError = true)
    }

    fun disconnect() {
        MyVpnService.disconnect(getApplication())
    }

    fun reconnect() {
        MyVpnService.reconnect(getApplication())
    }

    fun checkIp() {
        startIpCheck(force = false)
    }

    /**
     * Runs one public-IP lookup. A completed result is applied only when it is
     * still the newest request, so switching network paths while a request is in
     * flight cannot publish the address of the previous path.
     */
    private fun startIpCheck(force: Boolean) {
        if (_ipState.value.loading && !force) return
        val generation = ++ipCheckGeneration
        val throughTunnel = VpnController.snapshot.value.state == VpnState.CONNECTED
        if (!hasInternetConnectivity()) {
            _ipState.value = PublicIpState(
                error = "No network connection. Connect this device to a network and try again.",
                throughTunnel = throughTunnel,
            )
            return
        }
        _ipState.value = PublicIpState(loading = true, throughTunnel = throughTunnel)
        viewModelScope.launch {
            val result = container.publicIp.fetch()
            if (generation != ipCheckGeneration) return@launch
            _ipState.value = result.fold(
                onSuccess = { address ->
                    PublicIpState(
                        ip = address,
                        checkedAtMillis = System.currentTimeMillis(),
                        throughTunnel = throughTunnel,
                    )
                },
                onFailure = { failure ->
                    PublicIpState(
                        error = publicIpFailureMessage(failure),
                        throughTunnel = throughTunnel,
                    )
                },
            )
        }
    }

    private fun invalidateIpCheck() {
        ipCheckGeneration++
        if (_ipState.value != PublicIpState()) _ipState.value = PublicIpState()
    }

    private fun hasInternetConnectivity(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun publicIpFailureMessage(failure: Throwable): String = when (failure) {
        is SocketTimeoutException -> "The IP service did not answer in time. Try again."
        is UnknownHostException -> "The IP service could not be resolved. Check your connection."
        else -> failure.message
            ?.take(140)
            ?.takeIf { it.isNotBlank() }
            ?: "Could not check the public IP address."
    }

    fun importProfiles(uris: List<Uri>) {
        if (_importing.value) return
        viewModelScope.launch {
            _importing.value = true
            val results = withContext(Dispatchers.IO) { container.importer.import(uris) }
            container.profiles.refresh()
            _importReport.value = ImportReport("Import", results)
            val successes = results.filterIsInstance<ImportOutcome.Success>()
            if (successes.size == 1 && results.size == 1) {
                container.profiles.select(successes.first().profileId)
            }
            _importing.value = false
        }
    }

    fun dismissImportReport() {
        _importReport.value = null
    }

    fun renameProfile(id: String, name: String) {
        ProfileNameValidator.validate(name).onSuccess { valid ->
            viewModelScope.launch {
                container.profiles.rename(id, valid)
                    .onSuccess { message("Profile renamed") }
                    .onFailure { message("Could not rename the profile.", isError = true) }
            }
        }.onFailure { message(it.message ?: "Invalid name.", isError = true) }
    }

    fun setEmoji(id: String, emoji: String) {
        viewModelScope.launch {
            container.profiles.setEmoji(id, EmojiCatalog.normalized(emoji))
                .onSuccess { message("Profile icon updated") }
                .onFailure { message("Could not update the icon.", isError = true) }
        }
    }

    fun deleteProfile(id: String) {
        val active = VpnController.snapshot.value
        if (active.state.isActive && active.profileId == id) {
            message("Disconnect the VPN before deleting its profile.", isError = true)
            return
        }
        viewModelScope.launch {
            container.profiles.delete(id)
                .onSuccess { message("Profile deleted") }
                .onFailure { message("Could not delete the profile.", isError = true) }
        }
    }

    fun clearLogs() {
        VpnController.clearLogs()
    }

    fun runSelfTest() {
        if (_selfTest.value == SelfTestState.Running) return
        _selfTest.value = SelfTestState.Running
        viewModelScope.launch {
            _selfTest.value = NativeEngine.selfTest().fold(
                onSuccess = { SelfTestState.Passed(it) },
                onFailure = { SelfTestState.Failed(it.message ?: "Crypto self-test failed.") },
            )
        }
    }

    fun updateBiometricLock(enabled: Boolean) {
        container.settings.update { it.copy(biometricLock = enabled) }
    }

    fun updateTheme(mode: com.smhasnan.openvpn.settings.ThemeMode) {
        container.settings.update { it.copy(themeMode = mode) }
    }

    fun updateBlockUnusedFamilies(enabled: Boolean) {
        container.settings.update { it.copy(blockUnusedFamilies = enabled) }
    }

    fun updateAutoCheckIp(enabled: Boolean) {
        container.settings.update { it.copy(autoCheckIp = enabled) }
    }

    fun selectedProfile(): ProfileSummary? = container.profiles.selectedProfile()
}
