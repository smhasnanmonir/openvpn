package com.smhasnan.openvpn.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smhasnan.openvpn.appContainer
import com.smhasnan.openvpn.config.AlwaysOnVpnPolicy
import com.smhasnan.openvpn.config.EmojiCatalog
import com.smhasnan.openvpn.config.ImportOutcome
import com.smhasnan.openvpn.config.ProfileCredentials
import com.smhasnan.openvpn.config.ProfileNameValidator
import com.smhasnan.openvpn.config.ProfileSummary
import com.smhasnan.openvpn.config.SystemVpnState
import com.smhasnan.openvpn.nativevpn.NativeEngine
import com.smhasnan.openvpn.settings.AlwaysOnVpnStatus
import com.smhasnan.openvpn.settings.CipherPolicy
import com.smhasnan.openvpn.settings.CompressionPolicy
import com.smhasnan.openvpn.settings.MtuMode
import com.smhasnan.openvpn.settings.SplitTunnelMode
import com.smhasnan.openvpn.ui.components.CredentialPrompt
import com.smhasnan.openvpn.ui.components.CredentialsInput
import com.smhasnan.openvpn.vpn.CaptivePortalPolicy
import com.smhasnan.openvpn.vpn.LogLevel
import com.smhasnan.openvpn.vpn.MyVpnService
import com.smhasnan.openvpn.vpn.NetworkState
import com.smhasnan.openvpn.vpn.PortalState
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class UiMessage(val text: String, val isError: Boolean = false)

data class ImportReport(val fileName: String, val results: List<ImportOutcome>)

data class ConnectRequest(val profileId: String, val credentials: ProfileCredentials)

/** One launchable installed app offered by the per-app routing picker. */
data class InstalledApp(val packageName: String, val label: String)

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

    /** Captive-portal classification of the active network; never claims protection. */
    private val _portalState = MutableStateFlow(PortalState.UNKNOWN)
    val portalState: StateFlow<PortalState> = _portalState.asStateFlow()

    /** Best-effort Android Always-on/lockdown readiness, refreshed on demand. */
    private val _alwaysOnState = MutableStateFlow(SystemVpnState.UNKNOWN)
    val alwaysOnState: StateFlow<SystemVpnState> = _alwaysOnState.asStateFlow()

    private val _splitTunnelApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val splitTunnelApps: StateFlow<List<InstalledApp>> = _splitTunnelApps.asStateFlow()

    private val _splitTunnelAppsLoading = MutableStateFlow(false)
    val splitTunnelAppsLoading: StateFlow<Boolean> = _splitTunnelAppsLoading.asStateFlow()

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
    private var splitTunnelAppsLoaded = false

    /** True once the user paused the tunnel through the captive-portal flow. */
    private var portalPauseRequested = false

    /** Prevents more than one automatic reconnect per portal pause. */
    private var autoResumeConsumed = false
    private var lastPortalState = PortalState.UNKNOWN

    private val connectivityManager =
        application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Portal classification callback. Registered for the default network so a
     * sign-in page appearing/clearing updates the banner without polling. The
     * callback body only writes an immutable StateFlow value, which is safe from
     * the binder thread it is delivered on.
     */
    private val portalNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            _portalState.value = classifyNetwork(networkCapabilities)
        }

        override fun onAvailable(network: Network) {
            refreshPortalState()
        }

        override fun onLost(network: Network) {
            refreshPortalState()
        }
    }

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
        registerPortalCallback()
        refreshAlwaysOnStatus()
        viewModelScope.launch {
            combine(
                VpnController.snapshot.map { it.pausedForPortal },
                _portalState,
            ) { pausedForPortal, portal -> pausedForPortal to portal }
                .distinctUntilChanged()
                .collect { (pausedForPortal, portal) ->
                    val previousPortal = lastPortalState
                    lastPortalState = portal
                    // Auto-resume exactly once per pause, and only after the
                    // network leaves the portal state (a portal that was never
                    // sign-in-required cannot be "completed" by waiting).
                    if (pausedForPortal &&
                        portalPauseRequested &&
                        !autoResumeConsumed &&
                        portal == PortalState.NOT_REQUIRED &&
                        previousPortal != PortalState.NOT_REQUIRED
                    ) {
                        autoResumeConsumed = true
                        message("Network sign-in detected. Reconnecting the VPN…")
                        resumeAfterPortal()
                    }
                }
        }
    }

    override fun onCleared() {
        runCatching { connectivityManager.unregisterNetworkCallback(portalNetworkCallback) }
        super.onCleared()
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
        // An explicit disconnect must never be undone by portal auto-resume.
        portalPauseRequested = false
        autoResumeConsumed = true
        MyVpnService.disconnect(getApplication())
    }

    fun reconnect() {
        MyVpnService.reconnect(getApplication())
    }

    // ------------------------------------------------------------------
    // Captive portal (#18)
    // ------------------------------------------------------------------

    /**
     * Pauses the tunnel so the user can reach the network's sign-in page. The
     * snapshot immediately stops claiming protection; the service closes the
     * native session and keeps the desired profile for [resumeAfterPortal].
     */
    fun pauseForPortal() {
        val snapshot = VpnController.snapshot.value
        if (snapshot.state == VpnState.DISCONNECTED) {
            message("The VPN is already disconnected.", isError = true)
            refreshPortalState()
            return
        }
        portalPauseRequested = true
        autoResumeConsumed = false
        // The snapshot is only changed by the service once the pause is actually
        // applied (MyVpnService ACTION_PAUSE_FOR_PORTAL). Publishing the paused
        // state here would show "not protected" even when the request failed and
        // the tunnel is still up.
        runCatching { MyVpnService.pauseForPortal(getApplication()) }
            .onFailure {
                portalPauseRequested = false
                message("Could not pause the VPN for network sign-in.", isError = true)
            }
    }

    /**
     * Reconnects the tunnel the user paused for portal sign-in, or falls back to
     * the selected profile when the paused profile is gone.
     */
    fun resumeAfterPortal() {
        autoResumeConsumed = true
        portalPauseRequested = false
        val snapshot = VpnController.snapshot.value
        if (!snapshot.pausedForPortal && snapshot.state.isActive) {
            // Already connecting/connected: nothing to resume.
            return
        }
        val pausedId = snapshot.profileId
        val pausedProfile = pausedId?.let { container.profiles.summary(it) }
        if (pausedProfile == null) {
            requestConnect()
            return
        }
        if (container.profiles.selectedId.value == pausedProfile.id) {
            requestConnect()
        } else {
            viewModelScope.launch {
                container.profiles.select(pausedProfile.id)
                requestConnect()
            }
        }
    }

    /** Opens the platform's connectivity check in a browser. */
    fun openPortalSignIn() {
        runCatching {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("http://connectivitycheck.gstatic.com/generate_204"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
        }.onFailure {
            message("No browser is available to open the sign-in page.", isError = true)
        }
    }

    private fun registerPortalCallback() {
        runCatching { connectivityManager.registerDefaultNetworkCallback(portalNetworkCallback) }
            .onFailure {
                // Some OEM builds reject the default callback. Fall back to a
                // one-shot classification of the active network; connect and
                // disconnect paths refresh it again.
                refreshPortalState()
            }
    }

    private fun refreshPortalState() {
        val capabilities = connectivityManager.activeNetwork?.let { network ->
            runCatching { connectivityManager.getNetworkCapabilities(network) }.getOrNull()
        }
        _portalState.value = classifyNetwork(capabilities)
    }

    private fun classifyNetwork(capabilities: NetworkCapabilities?): PortalState =
        CaptivePortalPolicy.classify(
            NetworkState(
                hasInternet = capabilities
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                validated = capabilities
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                captivePortal = capabilities
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
            ),
        )

    // ------------------------------------------------------------------
    // Always-on / lockdown readiness (#9)
    // ------------------------------------------------------------------

    /**
     * Re-reads the (hidden) system Always-on VPN settings. Detection is
     * best-effort; [SystemVpnState.UNKNOWN] is shown as "could not detect" and
     * never as protection.
     */
    fun refreshAlwaysOnStatus() {
        val application = getApplication<Application>()
        val reading = runCatching { AlwaysOnVpnStatus.read(application) }.getOrNull()
        _alwaysOnState.value = AlwaysOnVpnPolicy.classify(
            appPackage = application.packageName,
            alwaysOnPackage = reading?.alwaysOnPackage,
            lockdownEnabled = reading?.lockdownEnabled,
        )
    }

    // ------------------------------------------------------------------
    // Per-app split tunneling (#17)
    // ------------------------------------------------------------------

    /**
     * Loads launchable apps for the picker on IO and caches the list. Repeated
     * opens reuse the cache; pass `force = true` to re-query after an install.
     */
    fun loadInstalledApps(force: Boolean = false) {
        if (_splitTunnelAppsLoading.value) return
        if (splitTunnelAppsLoaded && !force) return
        _splitTunnelAppsLoading.value = true
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                runCatching { queryInstalledApps() }.getOrDefault(emptyList())
            }
            _splitTunnelApps.value = apps
            splitTunnelAppsLoaded = true
            _splitTunnelAppsLoading.value = false
        }
    }

    /**
     * Only launcher apps are listed: they are the apps a user recognises, and
     * service/system packages cannot be meaningfully routed or audited.
     *
     * This app's own package is deliberately excluded. Its only network use is
     * the optional public-IP check, which is not user traffic, so offering it as
     * a routable app would be meaningless; in an allow-list it would also look
     * like a leak when omitted. Excluding it keeps the summary honest.
     */
    @Suppress("DEPRECATION")
    private fun queryInstalledApps(): List<InstalledApp> {
        val application = getApplication<Application>()
        val packageManager = application.packageManager
        val ownPackage = application.packageName
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    launcherIntent,
                    PackageManager.ResolveInfoFlags.of(0L),
                )
            } else {
                packageManager.queryIntentActivities(launcherIntent, 0)
            }
        }.getOrDefault(emptyList())

        val byPackage = LinkedHashMap<String, InstalledApp>()
        for (info in resolved) {
            val appInfo = info.activityInfo?.applicationInfo ?: continue
            val packageName = appInfo.packageName
            if (packageName == ownPackage || byPackage.containsKey(packageName)) continue
            val label = runCatching { packageManager.getApplicationLabel(appInfo).toString() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: packageName
            byPackage[packageName] = InstalledApp(packageName = packageName, label = label)
        }
        return byPackage.values.sortedWith(
            compareBy<InstalledApp, String>(String.CASE_INSENSITIVE_ORDER) { it.label }
                .thenBy { it.packageName },
        )
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

    // ------------------------------------------------------------------
    // New security / reliability settings (#8, #10, #17, #19)
    // ------------------------------------------------------------------

    fun updateRequireTunnelDns(enabled: Boolean) {
        container.settings.update { it.copy(requireTunnelDns = enabled) }
    }

    /**
     * Stores the fallback DNS text verbatim. Parsing/validation happens in the
     * DNS leak policy at connect time, so partially typed input is always safe.
     */
    fun updateFallbackDns(raw: String) {
        container.settings.update { it.copy(fallbackDnsServers = raw) }
    }

    fun updateMtuMode(mode: MtuMode) {
        container.settings.update { it.copy(mtuMode = mode) }
    }

    fun updateSplitTunnelMode(mode: SplitTunnelMode) {
        container.settings.update { it.copy(splitTunnelMode = mode) }
    }

    fun updateSplitTunnelPackages(packages: Set<String>) {
        container.settings.update { it.copy(splitTunnelPackages = packages) }
    }

    fun updateCompressionPolicy(policy: CompressionPolicy) {
        container.settings.update { it.copy(compressionPolicy = policy) }
    }

    fun updateCipherPolicy(policy: CipherPolicy) {
        container.settings.update { it.copy(cipherPolicy = policy) }
    }

    fun selectedProfile(): ProfileSummary? = container.profiles.selectedProfile()
}
