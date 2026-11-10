package com.smhasnan.openvpn.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.smhasnan.openvpn.MainActivity
import com.smhasnan.openvpn.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile: a second system-level control surface beside the notification. */
class VpnTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listenerJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listenerJob?.cancel()
        listenerJob = scope.launch {
            VpnController.snapshot.collect { snapshot -> updateTile(snapshot) }
        }
    }

    override fun onStopListening() {
        listenerJob?.cancel()
        listenerJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val snapshot = VpnController.snapshot.value
        unlockAndRun {
            when {
                snapshot.state == VpnState.NEEDS_CHALLENGE ||
                    snapshot.state == VpnState.AUTH_FAILED ||
                    snapshot.state == VpnState.ERROR -> openApp()

                snapshot.state.isActive -> MyVpnService.disconnect(this)

                else -> connectFromTile()
            }
        }
    }

    private fun connectFromTile() {
        val profile = appContainer.profiles.selectedProfile()
        if (profile == null) {
            openApp()
            return
        }
        if (VpnService.prepare(this) != null) {
            // Consent can only be granted in an Activity.
            openApp()
            return
        }
        scope.launch {
            val credentials = appContainer.profiles.loadCredentials(profile.id)
            val needsCredentials = !profile.metadata.autologin
            val missingUsername = needsCredentials &&
                profile.metadata.userlockedUsername.isNullOrBlank() &&
                credentials?.username.isNullOrBlank()
            val missingPassword = needsCredentials && credentials?.password.isNullOrBlank()
            val missingKey = profile.metadata.privateKeyPasswordRequired &&
                credentials?.privateKeyPassword.isNullOrBlank()
            if (missingUsername || missingPassword || missingKey ||
                !profile.metadata.staticChallenge.isNullOrBlank()
            ) {
                openApp()
                return@launch
            }
            MyVpnService.connect(this@VpnTileService, profile.id)
        }
    }

    private fun updateTile(snapshot: VpnSnapshot) {
        val tile = qsTile ?: return
        tile.state = if (snapshot.state.isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = when (snapshot.state) {
            VpnState.CONNECTED -> "VPN connected"
            VpnState.CONNECTING, VpnState.PREPARING -> "VPN connecting"
            VpnState.RECONNECTING -> "VPN reconnecting"
            VpnState.NEEDS_CHALLENGE -> "VPN action needed"
            VpnState.ERROR, VpnState.AUTH_FAILED -> "VPN error"
            else -> "OpenVPN"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = snapshot.profileName ?: if (snapshot.state == VpnState.DISCONNECTED) {
                "Tap to connect"
            } else {
                "OpenVPN"
            }
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun requestRefresh(context: android.content.Context) {
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, VpnTileService::class.java),
                )
            }
        }
    }
}
