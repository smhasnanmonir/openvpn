package com.smhasnan.openvpn.vpn

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.smhasnan.openvpn.MainActivity
import com.smhasnan.openvpn.R

object VpnNotification {
    const val CHANNEL_ID = "vpn_status"
    const val ERROR_CHANNEL_ID = "vpn_errors"
    const val FOREGROUND_ID = 1001
    private const val ERROR_ID = 1002

    fun createChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val status = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        val errors = NotificationChannel(
            ERROR_CHANNEL_ID,
            "VPN errors",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Connection errors that need your attention."
            setShowBadge(true)
        }
        manager.createNotificationChannel(status)
        manager.createNotificationChannel(errors)
    }

    fun build(context: Context, snapshot: VpnSnapshot): Notification {
        val active = snapshot.state.isActive || snapshot.state == VpnState.DISCONNECTING
        val text = when (snapshot.state) {
            VpnState.PREPARING -> "Preparing the secure tunnel…"
            VpnState.CONNECTING -> "Connecting to ${snapshot.profileName ?: "your server"}…"
            VpnState.CONNECTED -> "${snapshot.profileName ?: "VPN"} · ${snapshot.details.protocol ?: "secure tunnel"}"
            VpnState.RECONNECTING -> "Reconnecting to ${snapshot.profileName ?: "your server"}…"
            VpnState.DISCONNECTING -> "Closing the tunnel…"
            VpnState.NEEDS_CHALLENGE -> "The server needs an additional response. Open the app."
            VpnState.AUTH_FAILED -> "Authentication failed. Open the app for details."
            VpnState.ERROR -> snapshot.error?.take(140) ?: "The VPN connection stopped."
            VpnState.DISCONNECTED -> "VPN disconnected."
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(stateTitle(snapshot.state))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(context))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(active)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (snapshot.state == VpnState.CONNECTED && snapshot.connectedAtMillis != null) {
            builder.setWhen(snapshot.connectedAtMillis)
            builder.setShowWhen(true)
            builder.setUsesChronometer(true)
        } else {
            builder.setShowWhen(false)
        }

        if (snapshot.state.canDisconnect) {
            builder.addAction(
                0,
                "Disconnect",
                serviceIntent(context, MyVpnService.ACTION_DISCONNECT, requestCode = 10),
            )
        }
        if (snapshot.state == VpnState.CONNECTED || snapshot.state == VpnState.RECONNECTING) {
            builder.addAction(
                0,
                "Reconnect",
                serviceIntent(context, MyVpnService.ACTION_RECONNECT, requestCode = 11),
            )
        }
        if (snapshot.state == VpnState.NEEDS_CHALLENGE) {
            builder.addAction(0, "Open app", openAppIntent(context, requestCode = 12))
        }
        return builder.build()
    }

    @SuppressLint("MissingPermission")
    fun update(context: Context, snapshot: VpnSnapshot) {
        // Notification permission is requested by the UI. Even when it is denied,
        // startForeground() is still required and Android shows the service in the
        // task manager, so this call is intentionally not gated on the permission.
        runCatching {
            NotificationManagerCompat.from(context).notify(FOREGROUND_ID, build(context, snapshot))
        }
    }

    @SuppressLint("MissingPermission")
    fun showError(context: Context, message: String) {
        val builder = NotificationCompat.Builder(context, ERROR_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("VPN connection failed")
            .setContentText(message.take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.take(500)))
            .setContentIntent(openAppIntent(context, requestCode = 20))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        runCatching {
            NotificationManagerCompat.from(context).notify(ERROR_ID, builder.build())
        }
    }

    fun cancelForeground(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(FOREGROUND_ID) }
    }

    private fun stateTitle(state: VpnState): String = when (state) {
        VpnState.PREPARING, VpnState.CONNECTING -> "VPN connecting"
        VpnState.CONNECTED -> "VPN connected"
        VpnState.RECONNECTING -> "VPN reconnecting"
        VpnState.DISCONNECTING -> "VPN disconnecting"
        VpnState.NEEDS_CHALLENGE -> "VPN action required"
        VpnState.AUTH_FAILED -> "VPN authentication failed"
        VpnState.ERROR -> "VPN error"
        VpnState.DISCONNECTED -> "VPN disconnected"
    }

    private fun openAppIntent(context: Context, requestCode: Int = 0): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            pendingIntentFlags(),
        )
    }

    private fun serviceIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MyVpnService::class.java).setAction(action)
        return PendingIntent.getService(context, requestCode, intent, pendingIntentFlags())
    }

    private fun pendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
}
