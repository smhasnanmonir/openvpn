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
import com.smhasnan.openvpn.config.StoredProfile
import com.smhasnan.openvpn.nativevpn.NativeCallbacks
import com.smhasnan.openvpn.nativevpn.NativeEngine
import com.smhasnan.openvpn.nativevpn.NativeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_CHALLENGE_RESPONSE = "challenge_response"

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

        fun respondToChallenge(context: Context, response: String) {
            context.startService(
                Intent(context, MyVpnService::class.java)
                    .setAction(ACTION_RESPOND_CHALLENGE)
                    .putExtra(EXTRA_CHALLENGE_RESPONSE, response),
            )
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionLock = Any()
    private val starting = AtomicBoolean(false)
    private val finishing = AtomicBoolean(false)

    // Read from the stats coroutine and engine callbacks without the session lock.
    @Volatile private var session: NativeSession? = null
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
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
                if (profileId.isNullOrBlank()) {
                    if (showPreparingForeground("unknown")) failFast("No VPN profile was selected.")
                    return START_NOT_STICKY
                }
                if (synchronized(sessionLock) { session != null } || starting.get()) {
                    VpnController.appendLog(LogLevel.WARN, "Ignoring duplicate VPN connection request")
                    return START_NOT_STICKY
                }
                if (showPreparingForeground(profileId)) {
                    beginConnect(profileId)
                }
            }
            ACTION_DISCONNECT -> requestDisconnect(revoked = false)
            ACTION_RECONNECT -> {
                val current = session
                if (current == null) {
                    stopSelf()
                } else {
                    VpnController.appendLog(LogLevel.INFO, "Manual reconnect requested")
                    current.reconnect(0)
                }
            }
            ACTION_RESPOND_CHALLENGE -> {
                val response = intent.getStringExtra(EXTRA_CHALLENGE_RESPONSE).orEmpty()
                respondToChallenge(response)
            }
            ACTION_STOP -> stopSelfSafely()
            null -> {
                // START_NOT_STICKY: never restore a session from a stale process.
                stopSelf()
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

    private fun beginConnect(profileId: String) {
        if (!starting.compareAndSet(false, true)) {
            VpnController.appendLog(LogLevel.WARN, "Ignoring duplicate connect request")
            return
        }
        serviceScope.launch {
            try {
                if (synchronized(sessionLock) { session != null }) {
                    VpnController.appendLog(LogLevel.WARN, "A VPN session is already active")
                    return@launch
                }
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
                val connectConfig = ProfileImportPolicy.withNegotiableTlsCiphers(stored.config)
                if (connectConfig != stored.config) {
                    VpnController.appendLog(
                        LogLevel.INFO,
                        "Removed a legacy-only tls-cipher pin for this connection (profile imported before 1.1.2).",
                    )
                }

                val started = newSession.connect(
                    config = connectConfig,
                    username = credentials.username,
                    password = credentials.password,
                    response = credentials.response,
                    keyPassword = credentials.privateKeyPassword,
                    blockUnusedFamilies = appContainer.settings.settingsNow().blockUnusedFamilies,
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

    private fun requestDisconnect(revoked: Boolean) {
        finishing.set(true)
        if (!revoked && VpnController.snapshot.value.state != VpnState.ERROR) {
            VpnController.disconnecting()
        }
        updateNotification()
        val current = synchronized(sessionLock) {
            session.also { session = null }
        }
        if (current == null) {
            if (!revoked) VpnController.disconnected()
            stopSelfSafely()
            return
        }
        serviceScope.launch {
            runCatching {
                current.disconnect()
                current.close()
            }
            if (revoked) {
                VpnController.error("VPN permission was revoked. The tunnel has been closed.")
            } else {
                VpnController.disconnected()
            }
            stopSelfSafely()
        }
    }

    private fun onSessionFinished() {
        if (!finishing.compareAndSet(false, true)) return
        // Do not join the native worker from its own callback thread. The native
        // worker sends SESSION_FINISHED immediately before returning.
        serviceScope.launch {
            delay(80L)
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
                snapshot.error?.let { VpnNotification.showError(this@MyVpnService, it) }
            }
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
        mainHandler.post {
            if (!destroyed) {
                VpnNotification.update(this, VpnController.snapshot.value)
                VpnTileService.requestRefresh(this)
            }
        }
    }

    private fun fail(message: String) {
        VpnController.error(message)
        VpnNotification.showError(this, message)
        updateNotification()
        onSessionFinished()
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

            val dns = config.optJSONArray("dnsServers")
            var addedDns = 0
            if (dns != null) {
                for (i in 0 until dns.length()) {
                    val server = dns.optString(i)
                    if (server.isNotBlank()) {
                        runCatching { builder.addDnsServer(server) }.onSuccess { addedDns++ }
                    }
                }
            }
            if (addedDns == 0) {
                VpnController.appendLog(
                    LogLevel.WARN,
                    "The server pushed no usable DNS server; Android may use the underlying network's DNS.",
                )
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
            VpnController.engineEvent(name, info, error, fatal)
            when (name) {
                "CONNECTED" -> {
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
                "SESSION_FINISHED" -> onSessionFinished()
            }
            updateNotification()
        }

        override fun onEngineLog(line: String) {
            VpnController.appendLog(LogLevel.INFO, line)
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
