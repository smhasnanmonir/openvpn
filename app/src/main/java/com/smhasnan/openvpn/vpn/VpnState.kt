package com.smhasnan.openvpn.vpn

import androidx.compose.runtime.Immutable

enum class VpnState {
    DISCONNECTED,
    PREPARING,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    DISCONNECTING,
    NEEDS_CHALLENGE,
    AUTH_FAILED,
    ERROR,
    ;

    val isActive: Boolean
        get() = this == PREPARING || this == CONNECTING || this == CONNECTED ||
            this == RECONNECTING || this == NEEDS_CHALLENGE

    val isConnected: Boolean
        get() = this == CONNECTED

    val canDisconnect: Boolean
        get() = isActive || this == AUTH_FAILED || this == ERROR
}

@Immutable
data class TrafficStats(
    val bytesIn: Long = 0L,
    val bytesOut: Long = 0L,
    val packetsIn: Long = 0L,
    val packetsOut: Long = 0L,
) {
    val hasData: Boolean
        get() = bytesIn > 0L || bytesOut > 0L
}

@Immutable
data class ConnectionDetails(
    val serverHost: String? = null,
    val serverPort: String? = null,
    val protocol: String? = null,
    val serverIp: String? = null,
    val vpnIp4: String? = null,
    val vpnIp6: String? = null,
    val mtu: String? = null,
    val tunName: String? = null,
) {
    val serverLabel: String?
        get() = when {
            serverHost.isNullOrBlank() -> null
            serverPort.isNullOrBlank() -> serverHost
            else -> "$serverHost:$serverPort"
        }
}

@Immutable
data class VpnSnapshot(
    val state: VpnState = VpnState.DISCONNECTED,
    val profileId: String? = null,
    val profileName: String? = null,
    val profileEmoji: String? = null,
    val connectedAtMillis: Long? = null,
    val error: String? = null,
    val challenge: String? = null,
    val details: ConnectionDetails = ConnectionDetails(),
    val stats: TrafficStats = TrafficStats(),
) {
    val isBusy: Boolean
        get() = state == VpnState.PREPARING || state == VpnState.CONNECTING ||
            state == VpnState.DISCONNECTING
}

@Immutable
data class LogEntry(
    val timestampMillis: Long,
    val level: LogLevel,
    val message: String,
)

enum class LogLevel { INFO, WARN, ERROR }

/**
 * UI state of the public-IP check. A state carries at most one outcome:
 * [loading], [error], or [ip]. Starting a new check replaces the whole value, so
 * an address from an earlier tunnel/direct path can never be mistaken for the
 * current result while a retry is in flight.
 */
@Immutable
data class PublicIpState(
    val loading: Boolean = false,
    val ip: String? = null,
    val error: String? = null,
    val checkedAtMillis: Long? = null,
    /** Whether the last started check travels (or travelled) through the tunnel. */
    val throughTunnel: Boolean = false,
)
