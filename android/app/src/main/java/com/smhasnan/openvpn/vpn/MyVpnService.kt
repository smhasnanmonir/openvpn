package com.smhasnan.openvpn.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.IpPrefix
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.system.OsConstants
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.smhasnan.openvpn.MainActivity
import com.smhasnan.openvpn.appContainer
import com.smhasnan.openvpn.config.ProfileCredentials
import com.smhasnan.openvpn.config.ProfileImportPolicy
import com.smhasnan.openvpn.config.ProfileSecurityPolicy
import com.smhasnan.openvpn.config.ProfileTrustPolicy
import com.smhasnan.openvpn.config.StoredProfile
import com.smhasnan.openvpn.nativevpn.NativeCallbacks
import com.smhasnan.openvpn.nativevpn.NativeEngine
import com.smhasnan.openvpn.nativevpn.NativeSession
import com.smhasnan.openvpn.settings.CipherPolicy
import com.smhasnan.openvpn.settings.CompressionPolicy
import com.smhasnan.openvpn.settings.SplitTunnelMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the Android TUN interface and the native OpenVPN Core session.
 *
 * The service never marks the connection as CONNECTED itself: state comes from
 * OpenVPN Core callbacks. TUN/Wi-Fi/mobile transitions are applied to
 * VpnService's underlying network; OpenVPN Core performs transport reconnects.
 */
class MyVpnService : VpnService() {
    companion object {
        const val ACTION_CONNECT = "com.smhasnan.openvpn.action.CONNECT"
        const val ACTION_DISCONNECT = "com.smhasnan.openvpn.action.DISCONNECT"
        const val ACTION_RECONNECT = "com.smhasnan.openvpn.action.RECONNECT"
        const val ACTION_RESPOND_CHALLENGE = "com.smhasnan.openvpn.action.RESPOND_CHALLENGE"
        const val ACTION_STOP = "com.smhasnan.openvpn.action.STOP"

        /**
         * Temporarily closes the tunnel so the user can sign in to a captive
         * portal. The persisted desired-connected profile is kept, so the UI can
         * offer an explicit resume; the VPN is never reported as protecting
         * traffic while paused.
         */
        const val ACTION_PAUSE_FOR_PORTAL = "com.smhasnan.openvpn.action.PAUSE_FOR_PORTAL"

        const val EXTRA_PROFILE_ID = "profile_id"

        fun connect(context: Context, profileId: String) {
            val intent = Intent(context, MyVpnService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_PROFILE_ID, profileId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun disconnect(context: Context) {
            context.startService(Intent(context, MyVpnService::class.java).setAction(ACTION_DISCONNECT))
        }

        fun reconnect(context: Context) {
            context.startService(Intent(context, MyVpnService::class.java).setAction(ACTION_RECONNECT))
        }

        fun pauseForPortal(context: Context) {
            context.startService(
                Intent(context, MyVpnService::class.java).setAction(ACTION_PAUSE_FOR_PORTAL),
            )
        }

        /**
         * The response itself travels through [CredentialHandoff], never an
         * Intent extra: it is often a one-time/2FA code (audit 2026-11-12,
         * finding 3). The intent only signals that a response is ready.
         */
        fun respondToChallenge(context: Context) {
            context.startService(
                Intent(context, MyVpnService::class.java)
                    .setAction(ACTION_RESPOND_CHALLENGE),
            )
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionLock = Any()
    private val starting = AtomicBoolean(false)
    private val finishing = AtomicBoolean(false)
    private val notificationUpdateScheduled = AtomicBoolean(false)

    // Read from the stats coroutine and engine callbacks without the session lock.
    @Volatile private var session: NativeSession? = null
    @Volatile private var resumeJob: Job? = null
    /** Resume attempts since the session last reached CONNECTED. */
    @Volatile private var resumeAttempts = 0
    /** Set by the engine log so an unhandled-exception event can be identified as a rekey failure. */
    @Volatile private var sawKeyingMaterialError = false
    /** MTU warning is emitted once per connection instead of for every oversized packet. */
    @Volatile private var mtuWarningLogged = false
    /**
     * Reason the app refused to build the TUN (fail-closed DNS or split-tunnel
     * policy). Core's terminal event text for this case is generic, so the
     * service hands the reason to [VpnController.engineEvent] as a hint.
     */
    @Volatile private var tunError: String? = null
    /** Legacy cipher findings are reported once per connection, not per reconnect. */
    @Volatile private var legacyCipherWarningLogged = false
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var powerManager: PowerManager
    private var warnedAboutLegacyIpv6Protection = false
    @Volatile private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        VpnNotification.createChannels(this)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        networkMonitor = NetworkMonitor(this)
        networkMonitor.start()
        serviceScope.launch {
            networkMonitor.lastChange.collectLatest { change ->
                if (change != null) {
                    VpnController.appendLog(
                        LogLevel.INFO,
                        "Network changed: ${change.label}",
                    )
                    applyUnderlyingNetwork()
                    refreshConnectionDetails()
                    updateNotification()
                }
            }
        }
        startStatsObservation()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                if (destroyed || finishing.get()) {
                    VpnController.appendLog(
                        LogLevel.WARN,
                        "Ignoring connect request while the service is stopping",
                    )
                    // connect() uses startForegroundService(); a late tap must not
                    // leave a started-but-not-foreground service behind.
                    stopSelfSafely()
                    return START_NOT_STICKY
                }
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
                if (profileId.isNullOrBlank()) {
                    if (showPreparingForeground("unknown")) failFast("No VPN profile was selected.")
                    return START_NOT_STICKY
                }
                if (synchronized(sessionLock) { session != null } || starting.get()) {
                    VpnController.appendLog(LogLevel.WARN, "Ignoring duplicate VPN connection request")
                    // An already-active session must survive a process kill, so the
                    // service stays sticky instead of degrading the restart mode.
                    return START_STICKY
                }
                if (!showPreparingForeground(profileId)) return START_NOT_STICKY
                beginConnect(profileId, userRequested = true)
                // A user-requested session is restartable: if the process is
                // killed, Android redelivers a null intent and the persisted
                // desired-connected profile is restored (issue #14). The native
                // session itself is never restored; a new one is created.
                return START_STICKY
            }
            // Android starts the VpnService with this action for Always-on VPN
            // (including after boot and when lockdown restores the tunnel). The
            // app must connect the persisted selected profile without waiting
            // for the user to open the UI.
            VpnService.SERVICE_INTERFACE -> {
                startFromAlwaysOn()
                // Ask the system to redeliver a null intent if the process is
                // killed while the Always-on service is running.
                return START_STICKY
            }
            ACTION_DISCONNECT -> {
                requestDisconnect(revoked = false)
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                val current = session
                if (current == null) {
                    stopSelf()
                } else {
                    VpnController.appendLog(LogLevel.INFO, "Manual reconnect requested")
                    current.reconnect(0)
                }
                return START_NOT_STICKY
            }
            ACTION_RESPOND_CHALLENGE -> {
                val profileId = VpnController.snapshot.value.profileId
                val response = profileId?.let(CredentialHandoff::takeResponse).orEmpty()
                respondToChallenge(response)
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                // ACTION_STOP ends the persistent connect intent; the service
                // must not restore a session after it.
                clearDesiredConnection()
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_FOR_PORTAL -> {
                if (destroyed || finishing.get()) {
                    stopSelfSafely()
                    return START_NOT_STICKY
                }
                // The persisted desired-connected profile is deliberately kept:
                // the portal flow is a pause, not a user disconnect. The paused
                // flag survives disconnecting()/disconnected() so the UI can offer
                // an explicit resume and never reports "protected" while paused.
                VpnController.appendLog(
                    LogLevel.WARN,
                    "VPN paused for captive-portal sign-in; traffic is no longer protected until it is resumed.",
                )
                VpnController.pausedForPortal()
                requestDisconnect(revoked = false, keepDesired = true)
                return START_NOT_STICKY
            }
            null -> {
                if (destroyed || finishing.get()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                // Android redelivers a null intent when it restarts a sticky
                // service after a process death. Restore only the persisted
                // intent: Always-on keeps using the system-selected profile;
                // otherwise the last user-requested profile is restored when it
                // still exists. A dead native pointer is never restored.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isAlwaysOn) {
                    startFromAlwaysOn()
                    // Keep the Always-on restart mode sticky across process deaths.
                    return START_STICKY
                }
                return restoreDesiredSession()
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        VpnController.error("VPN permission was revoked. The tunnel has been closed.")
        VpnNotification.showError(this, "VPN permission was revoked by Android.")
        requestDisconnect(revoked = true)
        super.onRevoke()
    }

    override fun onDestroy() {
        destroyed = true
        resumeJob?.cancel()
        serviceScope.cancel()
        networkMonitor.stop()
        val current = synchronized(sessionLock) {
            session.also { session = null }
        }
        runCatching {
            current?.disconnect()
            current?.close()
        }
        val state = VpnController.snapshot.value.state
        if (state != VpnState.ERROR && state != VpnState.AUTH_FAILED) {
            VpnController.disconnected()
        }
        VpnNotification.cancelForeground(this)
        super.onDestroy()
    }

    private fun showPreparingForeground(profileId: String): Boolean {
        val summary = appContainer.profiles.summary(profileId)
        VpnController.prepare(
            profileId = profileId,
            name = summary?.name ?: "VPN profile",
            emoji = summary?.emoji ?: "🛡️",
        )
        val notification = VpnNotification.build(this, VpnController.snapshot.value)
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(
                this,
                VpnNotification.FOREGROUND_ID,
                notification,
                type,
            )
            return true
        } catch (e: Exception) {
            VpnController.error("Could not start the VPN foreground service: ${e.javaClass.simpleName}")
            stopSelf()
            return false
        }
    }

    /**
     * Always-on VPN / lockdown starts the service with [VpnService.SERVICE_INTERFACE]
     * instead of the app's own ACTION_CONNECT. Use the persisted profile
     * selection and stored credentials so the tunnel comes back after boot or a
     * process restart without requiring the UI.
     */
    private fun startFromAlwaysOn() {
        if (destroyed || finishing.get()) return
        if (synchronized(sessionLock) { session != null } || starting.get()) return
        // Read the persisted selection before refresh() can reconcile it to a
        // different profile: Always-on must not silently bring up an exit the
        // user did not choose. A deleted selection is surfaced as an error.
        val persistedProfileId = appContainer.settings.selectedProfileIdNow()
        if (!showPreparingForeground(persistedProfileId ?: "always-on")) return
        serviceScope.launch {
            try {
                // The process may have just started; make sure the summary index
                // is available before resolving the stored profile.
                appContainer.profiles.refresh()
                if (destroyed || finishing.get()) return@launch
                if (persistedProfileId.isNullOrBlank()) {
                    fail("Always-on VPN is enabled, but no VPN profile is selected. Open the app and choose a profile.")
                    return@launch
                }
                VpnController.appendLog(
                    LogLevel.INFO,
                    "Always-on VPN start: connecting the selected profile.",
                )
                beginConnect(persistedProfileId, userRequested = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Could not restore the Always-on VPN profile: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Restores the last user-requested session after a process death (issue
     * #14). Only the persisted *intent* is restored: the profile must still
     * exist, credentials come from encrypted storage, and a brand-new native
     * session is created by [beginConnect]. A dead native pointer is never
     * restored.
     *
     * @return [START_STICKY] when a restore was started, otherwise
     *   [START_NOT_STICKY] after stopping the service.
     */
    private fun restoreDesiredSession(): Int {
        val desiredProfileId = appContainer.settings.desiredConnectedProfileIdNow()
        if (desiredProfileId.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!showPreparingForeground(desiredProfileId)) return START_NOT_STICKY
        serviceScope.launch {
            try {
                // The process may have just started; load the profile index first.
                appContainer.profiles.refresh()
                if (destroyed || finishing.get()) return@launch
                if (appContainer.profiles.get(desiredProfileId) == null) {
                    VpnController.appendLog(
                        LogLevel.WARN,
                        "The last connected profile no longer exists; the VPN session will not be restored.",
                    )
                    clearDesiredConnection()
                    stopSelfSafely()
                    return@launch
                }
                VpnController.appendLog(
                    LogLevel.INFO,
                    "Restoring the last VPN session after process restart",
                )
                beginConnect(desiredProfileId, userRequested = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Could not restore the last VPN session: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return START_STICKY
    }

    private fun beginConnect(profileId: String, userRequested: Boolean) {
        if (!starting.compareAndSet(false, true)) {
            VpnController.appendLog(LogLevel.WARN, "Ignoring duplicate connect request")
            return
        }
        // Persist the user's connect intent before any asynchronous work, so a
        // process death during the attempt can still be restored. The Always-on
        // and post-restart paths leave an already-persisted intent untouched.
        if (userRequested) {
            appContainer.settings.setDesiredConnectedProfile(profileId)
        }
        serviceScope.launch {
            try {
                if (destroyed || finishing.get()) return@launch
                if (synchronized(sessionLock) { session != null }) {
                    VpnController.appendLog(LogLevel.WARN, "A VPN session is already active")
                    return@launch
                }
                resumeAttempts = 0
                sawKeyingMaterialError = false
                mtuWarningLogged = false
                tunError = null
                legacyCipherWarningLogged = false
                val settings = appContainer.settings.settingsNow()
                val repository = appContainer.profiles
                val stored: StoredProfile? = repository.get(profileId)
                if (stored == null) {
                    fail("The selected profile no longer exists.")
                    return@launch
                }
                VpnController.prepare(profileId, stored.summary.name, stored.summary.emoji)
                updateNotification()

                val handedOff = CredentialHandoff.take(profileId)
                val storedCredentials = repository.loadCredentials(profileId) ?: ProfileCredentials()
                val base = handedOff ?: storedCredentials
                val credentials = if (!stored.summary.metadata.userlockedUsername.isNullOrBlank()) {
                    base.copy(username = stored.summary.metadata.userlockedUsername)
                } else {
                    base
                }
                val missing = missingCredentials(stored, credentials)
                if (missing != null) {
                    fail(missing)
                    return@launch
                }

                val callbacks = EngineCallbacks()
                val newSession = try {
                    NativeSession(callbacks)
                } catch (e: Exception) {
                    fail("OpenVPN engine is unavailable: ${e.message ?: "unknown error"}")
                    return@launch
                }
                if (destroyed) {
                    runCatching { newSession.close() }
                    return@launch
                }
                synchronized(sessionLock) {
                    if (destroyed) {
                        runCatching { newSession.close() }
                        return@launch
                    }
                    session = newSession
                }

                // Profiles imported before the compatibility fix still carry a
                // legacy-only tls-cipher pin; normalize at connect time as well
                // so an app update fixes existing profiles without re-import.
                val negotiatedConfig = ProfileImportPolicy.withNegotiableTlsCiphers(stored.config)
                if (negotiatedConfig != stored.config) {
                    VpnController.appendLog(
                        LogLevel.INFO,
                        "Removed a legacy-only tls-cipher pin for this connection (profile imported before 1.1.2).",
                    )
                }
                // Existing profiles imported before server-certificate hardening
                // also get remote-cert-tls server without a re-import.
                var connectConfig = ProfileImportPolicy.withServerCertificateUsage(negotiatedConfig)
                if (connectConfig != negotiatedConfig) {
                    VpnController.appendLog(
                        LogLevel.INFO,
                        "Enforced remote-cert-tls server for this connection (profile imported before 1.3.0).",
                    )
                }

                // Issue #11: never connect a profile that carries no trust anchor.
                // Without a CA, peer-fingerprint pin or tls-remote directive the
                // server cannot be authenticated, so connecting would be unsafe.
                if (!ProfileTrustPolicy.trustMaterial(connectConfig).hasServerTrust) {
                    fail(
                        "This profile has no CA certificate and no peer-fingerprint pin, so the " +
                            "app cannot verify that it is really connecting to the intended VPN " +
                            "server. Re-import a profile that includes a <ca> certificate or a " +
                            "<peer-fingerprint>, then reconnect.",
                    )
                    return@launch
                }

                // Issue #19: report legacy (non-AEAD) cipher findings once per
                // connection, without blocking profiles that explicitly use the
                // compatibility cipher policy.
                val legacyCiphers = ProfileSecurityPolicy.legacyCipherFindings(connectConfig)
                if (legacyCiphers.isNotEmpty() && !legacyCipherWarningLogged) {
                    legacyCipherWarningLogged = true
                    VpnController.appendLog(
                        LogLevel.WARN,
                        "This profile allows non-AEAD data cipher(s) " +
                            legacyCiphers.joinToString { "${it.value} (${it.directive})" } +
                            ". Enable the AEAD-only cipher policy in Settings to refuse them.",
                    )
                }
                if (settings.cipherPolicy == CipherPolicy.AEAD_ONLY) {
                    val refusal = ProfileSecurityPolicy.validateAeadOnly(connectConfig)
                    if (refusal != null) {
                        fail(refusal)
                        return@launch
                    }
                    connectConfig = ProfileSecurityPolicy.withAeadOnlyPushPolicy(connectConfig)
                    VpnController.appendLog(
                        LogLevel.INFO,
                        "AEAD-only cipher policy: non-AEAD ciphers pushed by the server will be refused.",
                    )
                }

                // Issue #19: strict compression mode also installs push filters, so a
                // server-pushed compressor (including compression stubs) is refused
                // before Core can create a stub context, and profile-local compression
                // directives cannot re-enable one.
                if (settings.compressionPolicy == CompressionPolicy.STRICT) {
                    connectConfig = ProfileSecurityPolicy.withStrictCompressionPolicy(connectConfig)
                    VpnController.appendLog(
                        LogLevel.INFO,
                        "Strict compression policy: server-pushed compressors will be refused.",
                    )
                }

                // Issue #10: an explicit MTU override must survive a server-pushed
                // tun-mtu, so it is applied to the profile text before Core parses it.
                connectConfig = MtuPolicy.withTunMtuOverride(connectConfig, settings.mtuMode)
                VpnController.appendLog(
                    LogLevel.INFO,
                    "TUN MTU: ${MtuPolicy.describe(settings.mtuMode)}",
                )

                val started = newSession.connect(
                    config = connectConfig,
                    username = credentials.username,
                    password = credentials.password,
                    response = credentials.response,
                    keyPassword = credentials.privateKeyPassword,
                    blockUnusedFamilies = settings.blockUnusedFamilies,
                    // Issue #19: strict mode makes Core refuse server-pushed
                    // compression instead of the ADR-005 asymmetric fallback.
                    strictCompression = settings.compressionPolicy == CompressionPolicy.STRICT,
                )
                if (!started) {
                    synchronized(sessionLock) {
                        if (session === newSession) session = null
                    }
                    newSession.close()
                    fail("The VPN engine rejected the connection request.")
                    return@launch
                }
                VpnController.appendLog(LogLevel.INFO, "Connection request handed to OpenVPN Core")
                updateNotification()
            } catch (e: CancellationException) {
                // A user-initiated disconnect cancels the scope; that is not a failure.
                throw e
            } catch (e: Exception) {
                fail("Could not start the VPN: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                starting.set(false)
            }
        }
    }

    private fun missingCredentials(stored: StoredProfile, credentials: ProfileCredentials): String? {
        val metadata = stored.summary.metadata
        if (!metadata.autologin) {
            if (credentials.username.isNullOrBlank() || credentials.password.isNullOrBlank()) {
                return "This profile needs a username and password. Open the app to sign in."
            }
        }
        if (!metadata.staticChallenge.isNullOrBlank() && credentials.response.isNullOrBlank()) {
            return "This profile requires a static challenge response. Open the app to enter it."
        }
        if (metadata.privateKeyPasswordRequired && credentials.privateKeyPassword.isNullOrBlank()) {
            return "This profile requires a private key password. Open the app to enter it."
        }
        if (metadata.externalPki) {
            return "External PKI profiles are not supported."
        }
        return null
    }

    /**
     * Traffic counters are only meaningful while the tunnel can move packets,
     * so polling follows VpnController's state instead of running forever.
     * collectLatest cancels the loop as soon as the state is no longer
     * CONNECTED/RECONNECTING, and the screen state then picks the cadence.
     */
    private fun startStatsObservation() {
        serviceScope.launch {
            VpnController.snapshot
                .map { it.state }
                .distinctUntilChanged()
                .collectLatest { state ->
                    if (!StatsRefreshPolicy.shouldPoll(state)) return@collectLatest
                    while (isActive) {
                        pollTrafficStats()
                        delay(StatsRefreshPolicy.intervalMs(powerManager.isInteractive))
                    }
                }
        }
    }

    private fun pollTrafficStats() {
        // The state can change while the native call is in flight; never publish
        // counters for a session that is already closing.
        if (!StatsRefreshPolicy.shouldPoll(VpnController.snapshot.value.state)) return
        val active = session ?: return
        // A closing session can make native calls fail; a stats refresh must
        // never take the service coroutine down.
        val stats = runCatching { NativeEngine.stats(active.statsJson()) }.getOrNull() ?: return
        if (stats.hasData || stats.packetsIn > 0 || stats.packetsOut > 0) {
            VpnController.updateStats(stats)
        }
    }

    /**
     * Connection details can change with the transport the core ends up using,
     * so they are refreshed once when the underlying network changes. The
     * stats loop itself never re-reads them.
     */
    private suspend fun refreshConnectionDetails() {
        val active = session ?: return
        if (VpnController.snapshot.value.state != VpnState.CONNECTED) return
        runCatching {
            val details = NativeEngine.connectionDetails(active.connectionInfoJson())
            if (details.serverHost != null || details.vpnIp4 != null || details.vpnIp6 != null) {
                VpnController.updateDetails(details)
            }
        }
    }

    private fun respondToChallenge(response: String) {
        val current = session
        if (current == null) {
            failFast("The VPN session is no longer active.")
            return
        }
        if (response.isBlank()) {
            failFast("Enter the response requested by the server.")
            return
        }
        VpnController.appendLog(LogLevel.INFO, "Submitting challenge response")
        VpnController.engineEvent("CONNECTING", "", false, false)
        updateNotification()
        serviceScope.launch {
            if (!current.respondToChallenge(response)) {
                fail("The challenge response could not be submitted.")
            }
        }
    }

    /**
     * @param revoked the system revoked VPN permission (no clean "disconnected"
     *   state; the error is shown instead).
     * @param keepDesired true for the captive-portal pause, which keeps the
     *   persisted connect intent so the UI can resume. Every other explicit
     *   stop clears it.
     */
    private fun requestDisconnect(revoked: Boolean, keepDesired: Boolean = false) {
        if (!keepDesired) clearDesiredConnection()
        finishing.set(true)
        resumeJob?.cancel()
        if (!revoked && VpnController.snapshot.value.state != VpnState.ERROR) {
            VpnController.disconnecting()
        }
        updateNotification()
        val current = synchronized(sessionLock) {
            session.also { session = null }
        }
        if (current == null) {
            if (!revoked) VpnController.disconnected(keepPaused = keepDesired)
            stopSelfSafely()
            return
        }
        serviceScope.launch {
            // Join the retry job before the native session is destroyed: it may
            // otherwise be inside a JNI resume call when close() frees the handle.
            resumeJob?.cancelAndJoin()
            runCatching {
                current.disconnect()
                current.close()
            }
            if (revoked) {
                VpnController.error("VPN permission was revoked. The tunnel has been closed.")
            } else {
                // keepDesired is set by the captive-portal pause: keep the paused
                // banner (and the persisted connect intent) so the user can resume.
                VpnController.disconnected(keepPaused = keepDesired)
            }
            stopSelfSafely()
        }
    }

    /**
     * Core pauses a session instead of stopping it when a reconnect attempt
     * times out after the connection was already established.
     *
     * While paused there is no tunnel, so retrying without a bound would leave
     * the app claiming "Reconnecting" while traffic is unprotected. Retries use
     * [PauseRetryPolicy] (exponential backoff, capped attempts); when the budget
     * is exhausted the session fails visibly instead.
     */
    private fun onPaused() {
        if (destroyed || finishing.get()) return
        if (VpnController.snapshot.value.state == VpnState.DISCONNECTING) return
        VpnController.appendLog(
            LogLevel.WARN,
            "Connection attempt timed out; the tunnel is down and traffic is not protected while retrying.",
        )
        resumeJob?.cancel()
        resumeJob = serviceScope.launch {
            while (isActive) {
                // Do not trust the JNI resume() result: core silently ignores a
                // resume that races a stop, so keep retrying until an observed
                // RESUME/CONNECTED/failure event cancels this job. Jitter spreads
                // simultaneous reconnects after a shared outage (issue #14).
                delay(PauseRetryPolicy.delayMsWithJitter(resumeAttempts + 1))
                if (destroyed || finishing.get()) return@launch
                if (VpnController.snapshot.value.state != VpnState.RECONNECTING) return@launch
                if (!networkMonitor.isOnline()) continue
                val active = session ?: return@launch
                if (PauseRetryPolicy.shouldGiveUp(resumeAttempts)) {
                    fail(
                        "The VPN could not reconnect. Traffic is no longer protected; " +
                            "reconnect or choose another profile.",
                    )
                    return@launch
                }
                resumeAttempts += 1
                VpnController.appendLog(
                    LogLevel.INFO,
                    "Resuming the VPN session (attempt $resumeAttempts of " +
                        "${PauseRetryPolicy.MAX_ATTEMPTS})",
                )
                active.resume()
            }
        }
    }

    private fun onSessionFinished() {
        if (!finishing.compareAndSet(false, true)) return
        resumeJob?.cancel()
        // Do not join the native worker from its own callback thread. The native
        // worker sends SESSION_FINISHED immediately before returning.
        serviceScope.launch {
            delay(80L)
            resumeJob?.cancelAndJoin()
            val current = synchronized(sessionLock) {
                session.also { session = null }
            }
            runCatching {
                current?.disconnect()
                current?.close()
            }
            val snapshot = VpnController.snapshot.value
            if (snapshot.state == VpnState.CONNECTING || snapshot.state == VpnState.RECONNECTING) {
                VpnController.disconnected()
            }
            if (snapshot.state == VpnState.ERROR || snapshot.state == VpnState.AUTH_FAILED) {
                // Terminal failure: the sticky service must not resurrect this
                // session after a process death; the user reconnects deliberately.
                clearDesiredConnection()
                snapshot.error?.let { VpnNotification.showError(this@MyVpnService, it) }
            }
            // The TUN hint has been delivered (or is no longer relevant).
            tunError = null
            stopSelfSafely()
        }
    }

    private fun stopSelfSafely() {
        mainHandler.post {
            runCatching {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
            VpnNotification.cancelForeground(this)
            stopSelf()
        }
    }

    private fun updateNotification() {
        if (destroyed) return
        // Engine callbacks and stats polls can request an update several times
        // before the main thread runs; keep at most one post and let it read the
        // latest snapshot instead of notifying once per request.
        if (!notificationUpdateScheduled.compareAndSet(false, true)) return
        mainHandler.post {
            notificationUpdateScheduled.set(false)
            if (destroyed) return@post
            val posted = VpnNotification.update(this, VpnController.snapshot.value)
            // The tile renders the same state/profile content as the
            // notification, so stats-only updates must not wake SystemUI.
            if (posted) VpnTileService.requestRefresh(this)
        }
    }

    private fun fail(message: String) {
        // Every fail() path is terminal for the persistent connect intent: a
        // sticky-service restart must not revive a session that just failed.
        clearDesiredConnection()
        VpnController.error(message)
        VpnNotification.showError(this, message)
        updateNotification()
        onSessionFinished()
    }

    /** Ends the persisted user connect intent (see [SettingsRepository.setDesiredConnectedProfile]). */
    private fun clearDesiredConnection() {
        appContainer.settings.setDesiredConnectedProfile(null)
    }

    private fun failFast(message: String) {
        fail(message)
    }

    private fun applyUnderlyingNetwork() {
        val network = networkMonitor.currentNetwork.value ?: return
        runCatching { setUnderlyingNetworks(arrayOf(network)) }
    }

    /**
     * Called by OpenVPN Core (on its connection thread) after it has collected
     * the tun builder configuration. We create the Android TUN and transfer fd
     * ownership to native Core, which performs all packet I/O.
     */
    private fun establishTun(configJson: String): Int {
        var pfd: ParcelFileDescriptor? = null
        return try {
            val config = JSONObject(configJson)
            val builder = Builder()
                .setSession(config.optString("sessionName", "OpenVPN Client").ifBlank { "OpenVPN Client" })
                .setConfigureIntent(openAppIntent())
                .setBlocking(false)

            // Inherit the underlying network's meteredness where the platform
            // supports it; otherwise the VPN would look unmetered and apps
            // could ignore the user's data-saver settings.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { builder.setMetered(networkMonitor.isMetered()) }
            }

            val mtu = config.optInt("mtu", 0)
            if (mtu in 576..9000) builder.setMtu(mtu)

            val addresses = config.optJSONArray("addresses")
            if (addresses != null) {
                for (i in 0 until addresses.length()) {
                    val item = addresses.optJSONObject(i) ?: continue
                    val address = item.optString("address")
                    val prefix = item.optInt("prefix", -1)
                    if (address.isBlank() || prefix < 0) continue
                    builder.addAddress(address, prefix)
                }
            }

            val routes = config.optJSONArray("routes")
            if (routes != null) {
                for (i in 0 until routes.length()) {
                    val item = routes.optJSONObject(i) ?: continue
                    val address = item.optString("address")
                    val prefix = item.optInt("prefix", -1)
                    if (address.isBlank() || prefix < 0) continue
                    builder.addRoute(address, prefix)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val excluded = config.optJSONArray("excludeRoutes")
                if (excluded != null) {
                    for (i in 0 until excluded.length()) {
                        val item = excluded.optJSONObject(i) ?: continue
                        runCatching {
                            builder.excludeRoute(
                                IpPrefix(
                                    InetAddress.getByName(item.optString("address")),
                                    item.optInt("prefix"),
                                ),
                            )
                        }
                    }
                }
            }

            val allowIpv4 = config.optBoolean("allowIpv4", true)
            val allowIpv6 = config.optBoolean("allowIpv6", true)
            val blockUnused = config.optBoolean("blockUnusedFamilies", true)
            val addressArray = config.optJSONArray("addresses")
            val hasIpv4 = addressArray?.let { list ->
                (0 until list.length()).any { list.optJSONObject(it)?.optBoolean("ipv6", false) == false }
            } ?: false
            val hasIpv6 = addressArray?.let { list ->
                (0 until list.length()).any { list.optJSONObject(it)?.optBoolean("ipv6", false) == true }
            } ?: false

            // Android blocks an address family when the VPN has no
            // address, route, or DNS server of that family. That default is
            // the leak-prevention baseline. allowFamily() intentionally
            // unblocks a family, so only call it when the user opts out.
            if (!blockUnused) {
                if (!hasIpv4 && allowIpv4) builder.allowFamily(OsConstants.AF_INET)
                if (!hasIpv6 && allowIpv6) builder.allowFamily(OsConstants.AF_INET6)
                VpnController.appendLog(
                    LogLevel.WARN,
                    "Untunneled address families are allowed by app setting; traffic may use the underlying network.",
                )
            } else {
                if (hasIpv4 && !allowIpv4) {
                    VpnController.appendLog(
                        LogLevel.WARN,
                        "Profile requests blocking IPv4 while an IPv4 address is configured; Android cannot enforce that safely.",
                    )
                }
                if (hasIpv6 && !allowIpv6) {
                    VpnController.appendLog(
                        LogLevel.WARN,
                        "Profile requests blocking IPv6 while an IPv6 address is configured; Android cannot enforce that safely.",
                    )
                }
            }

            val settings = appContainer.settings.settingsNow()
            // Issue #8: fail closed instead of silently falling back to the
            // underlying network's resolver when no tunnel DNS is available.
            val pushedDns = config.optJSONArray("dnsServers")?.let { array ->
                (0 until array.length())
                    .mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty()
            when (
                val decision = DnsLeakPolicy.decide(
                    pushedServers = pushedDns,
                    requireTunnelDns = settings.requireTunnelDns,
                    fallbackServers = DnsLeakPolicy.parseFallback(settings.fallbackDnsServers),
                    tunnelIpv4 = hasIpv4,
                    tunnelIpv6 = hasIpv6,
                )
            ) {
                is Decision.TunnelDns -> {
                    if (decision.filteredServers.isNotEmpty()) {
                        // Never log the resolver addresses themselves: they can
                        // reveal the provider/network configuration.
                        VpnController.appendLog(
                            LogLevel.WARN,
                            "${decision.filteredServers.size} DNS resolver(s) were not installed " +
                                "because this tunnel does not route their address family; " +
                                "that family stays blocked by Android instead of leaking.",
                        )
                    }
                    var addedDns = 0
                    for (server in decision.servers) {
                        runCatching { builder.addDnsServer(server) }.onSuccess { addedDns++ }
                    }
                    if (decision.usedFallback) {
                        VpnController.appendLog(
                            LogLevel.INFO,
                            "No DNS was pushed by the server; using ${decision.servers.size} " +
                                "configured fallback resolver(s) through the tunnel.",
                        )
                    }
                    if (addedDns == 0) {
                        if (settings.requireTunnelDns) {
                            val reason =
                                "The \"Require tunnel DNS\" setting is on, but Android accepted no " +
                                    "usable tunnel DNS server. The connection was stopped so DNS " +
                                    "requests cannot leak to the underlying network."
                            tunError = reason
                            VpnController.appendLog(LogLevel.ERROR, reason)
                            runCatching { pfd?.close() }
                            return -1
                        }
                        VpnController.appendLog(
                            LogLevel.WARN,
                            "The server pushed no usable DNS server and no fallback is configured; " +
                                "Android may use the underlying network's DNS because \"Require tunnel DNS\" is off.",
                        )
                    }
                }
                is Decision.Refused -> {
                    tunError = decision.reason
                    VpnController.appendLog(LogLevel.ERROR, decision.reason)
                    runCatching { pfd?.close() }
                    return -1
                }
            }

            // Issue #17: per-app split tunneling. The policy refuses plans that
            // would protect nothing; the effective list is applied to the TUN.
            val installedPackages = runCatching {
                packageManager.getInstalledPackages(0).map { it.packageName }.toSet()
            }.getOrDefault(emptySet())
            when (
                val splitValidation = SplitTunnelPolicy.validate(
                    settings.splitTunnelMode,
                    installedPackages,
                    settings.splitTunnelPackages,
                )
            ) {
                is Validation.Rejected -> {
                    tunError = splitValidation.reason
                    VpnController.appendLog(LogLevel.ERROR, splitValidation.reason)
                    runCatching { pfd?.close() }
                    return -1
                }
                Validation.Ok -> Unit
            }
            if (settings.splitTunnelMode.usesAppList) {
                val effective = SplitTunnelPolicy.effectivePackages(
                    settings.splitTunnelPackages,
                    installedPackages,
                )
                val skipped = SplitTunnelPolicy.uninstalled(
                    settings.splitTunnelPackages,
                    installedPackages,
                )
                VpnController.appendLog(
                    LogLevel.INFO,
                    "Split tunneling (${settings.splitTunnelMode}): " +
                        "${effective.size} selected app(s) applied" +
                        if (skipped.isEmpty()) {
                            ""
                        } else {
                            "; ${skipped.size} selected app(s) are not installed and were skipped"
                        },
                )
                when (settings.splitTunnelMode) {
                    SplitTunnelMode.INCLUDE_SELECTED -> {
                        var refused = 0
                        for (packageName in effective) {
                            runCatching { builder.addAllowedApplication(packageName) }
                                .onFailure { refused++ }
                        }
                        if (refused > 0) {
                            // In INCLUDE mode a package missing from the allow-list
                            // bypasses the tunnel; refusing to connect is safer than
                            // silently leaving selected apps unprotected.
                            val reason =
                                "Android refused to add $refused of the selected app(s) to the " +
                                    "VPN allow-list, so they would bypass the tunnel. The " +
                                    "connection was stopped to avoid leaving apps unprotected. " +
                                    "Re-select the apps (they may have been uninstalled), then " +
                                    "reconnect."
                            tunError = reason
                            VpnController.appendLog(LogLevel.ERROR, reason)
                            runCatching { pfd?.close() }
                            return -1
                        }
                    }
                    SplitTunnelMode.EXCLUDE_SELECTED -> {
                        var failed = 0
                        for (packageName in effective) {
                            runCatching { builder.addDisallowedApplication(packageName) }
                                .onFailure { failed++ }
                        }
                        if (failed > 0) {
                            // Failing to exclude is the safe direction: those apps stay
                            // inside the tunnel. Never log the package names.
                            VpnController.appendLog(
                                LogLevel.WARN,
                                "$failed selected app(s) could not be excluded from the VPN; " +
                                    "their traffic still uses the tunnel (protected).",
                            )
                        }
                    }
                    SplitTunnelMode.OFF -> Unit
                }
                // The UI must never claim the tunnel covers apps that bypass it.
                SplitTunnelPolicy.warning(settings.splitTunnelMode, effective.size)?.let {
                    VpnController.appendLog(LogLevel.WARN, it)
                }
            }
            val searchDomains = config.optJSONArray("searchDomains")
            if (searchDomains != null) {
                for (i in 0 until searchDomains.length()) {
                    val domain = searchDomains.optString(i)
                    if (domain.isNotBlank()) runCatching { builder.addSearchDomain(domain) }
                }
            }

            val proxy = config.optJSONObject("httpProxy")
            if (proxy != null && proxy.optBoolean("enabled")) {
                val host = proxy.optString("host")
                val port = proxy.optInt("port", 0)
                if (host.isNotBlank() && port in 1..65535) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        @Suppress("DEPRECATION")
                        builder.setHttpProxy(ProxyInfo.buildDirectProxy(host, port))
                    } else {
                        VpnController.appendLog(
                            LogLevel.WARN,
                            "HTTP proxy profiles require Android 10 or newer; proxy setting ignored.",
                        )
                    }
                }
            }

            pfd = builder.establish()
            if (pfd == null) {
                VpnController.appendLog(LogLevel.ERROR, "Android VpnService.Builder did not create a TUN interface")
                return -1
            }
            val fd = pfd.detachFd()
            applyUnderlyingNetwork()
            fd
        } catch (e: Exception) {
            VpnController.appendLog(
                LogLevel.ERROR,
                "TUN setup failed: ${e.javaClass.simpleName}",
            )
            runCatching { pfd?.close() }
            -1
        }
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(this, 42, intent, flags)
    }

    private inner class EngineCallbacks : NativeCallbacks {
        override fun onEngineEvent(name: String, info: String, error: Boolean, fatal: Boolean) {
            if (destroyed) return
            // A PAUSE that races a user disconnect must not resurrect the
            // Reconnecting state or re-arm the retry loop during teardown.
            if (name == "PAUSE" && (finishing.get() || VpnController.snapshot.value.state == VpnState.DISCONNECTING)) {
                return
            }
            // Core reports a rekey key-export failure as an unhandled exception;
            // the preceding engine log identifies it, so the mapper can explain it.
            val rekeyHint = name == "SESSION_FINISHED" && error && sawKeyingMaterialError
            // When the service refused to build the TUN (fail-closed DNS or
            // split-tunnel policy), Core's terminal event text is generic. Carry
            // the app's own reason once and clear it afterwards.
            val tunHint = tunError
            if (tunHint != null &&
                (name == "SESSION_FINISHED" || name.startsWith("TUN") || name == "CLIENT_HALT")
            ) {
                tunError = null
            }
            VpnController.engineEvent(name, info, error, fatal, rekeyHint, tunHint = tunHint)
            when (name) {
                "CONNECTED" -> {
                    resumeJob?.cancel()
                    resumeAttempts = 0
                    sawKeyingMaterialError = false
                    val active = session
                    if (active != null) {
                        // Fetch details once here; the stats loop only reads counters.
                        runCatching {
                            val details = NativeEngine.connectionDetails(active.connectionInfoJson())
                            VpnController.updateDetails(details)
                            val stats = NativeEngine.stats(active.statsJson())
                            VpnController.updateStats(stats)
                        }
                    }
                }
                "PAUSE" -> onPaused()
                "RESUME" -> resumeJob?.cancel()
                "CLIENT_RESTART" -> {
                    // Core normally restarts transparently on a server RESTART. When
                    // the restart is caused by our own pull-filter policy refusing a
                    // pushed option, Core would loop forever; fail the session
                    // visibly so the user can change the policy or profile.
                    if (info.contains("rejected pushed option", ignoreCase = true)) {
                        fail(
                            "The server pushed an option this app's security policy refuses " +
                                "(for example a non-AEAD cipher, or compression in strict mode). " +
                                "Review the cipher/compression policy in Settings, or use a " +
                                "compatible profile.",
                        )
                    }
                }
                "SESSION_FINISHED" -> {
                    resumeJob?.cancel()
                    onSessionFinished()
                }
            }
            updateNotification()
        }

        override fun onEngineLog(line: String) {
            VpnController.appendLog(LogLevel.INFO, line)
            if (line.contains("TLS Keying material export error", ignoreCase = true) ||
                line.contains("KEY_EXPANSION_ERROR", ignoreCase = true)) {
                sawKeyingMaterialError = true
            }
            if (!mtuWarningLogged &&
                (line.contains("Message too long", ignoreCase = true) ||
                    line.contains("EMSGSIZE", ignoreCase = true))
            ) {
                mtuWarningLogged = true
                VpnController.appendLog(
                    LogLevel.WARN,
                    "A UDP packet exceeded the path MTU. If this repeats on this network, lower the " +
                        "TUN MTU in Settings > MTU (for example 1400) instead of editing the profile.",
                )
            }
        }

        override fun onTunEstablish(configJson: String): Int = establishTun(configJson)

        override fun onProtectSocket(fd: Int): Boolean =
            runCatching { protect(fd) }.getOrDefault(false)

        override fun onChallenge(
            challenge: String,
            echo: Boolean,
            responseRequired: Boolean,
            stateId: String,
        ) {
            VpnController.setChallenge(challenge)
            VpnController.appendLog(LogLevel.WARN, "Server requires an additional challenge response")
            updateNotification()
        }
    }
}
