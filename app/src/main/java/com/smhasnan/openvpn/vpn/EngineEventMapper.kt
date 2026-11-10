package com.smhasnan.openvpn.vpn

/**
 * Maps OpenVPN 3 Core event names to the app's state machine. Only events that
 * actually come from the pinned native engine are mapped here.
 */
object EngineEventMapper {
    data class Mapped(
        val state: VpnState?,
        val message: String? = null,
        val clearError: Boolean = false,
        val requiresChallenge: Boolean = false,
    )

    fun map(name: String, info: String, error: Boolean, fatal: Boolean): Mapped = when (name) {
        "CONNECTING" -> Mapped(VpnState.CONNECTING, clearError = true)
        "CONNECTED" -> Mapped(VpnState.CONNECTED, clearError = true)
        "RECONNECTING" -> Mapped(VpnState.RECONNECTING, clearError = true)
        "DISCONNECTED" -> Mapped(VpnState.DISCONNECTED, clearError = true)
        "AUTH_FAILED" -> Mapped(VpnState.AUTH_FAILED, info.ifBlank { "Authentication failed." })
        "DYNAMIC_CHALLENGE" -> Mapped(
            VpnState.NEEDS_CHALLENGE,
            info.ifBlank { "The server requires an additional response." },
            requiresChallenge = true,
        )
        "PROFILE_REJECTED", "CREDENTIALS_REJECTED", "TUN_SETUP_FAILED", "TUN_ERROR",
        "CONNECTION_TIMEOUT", "ENGINE_ERROR" -> Mapped(
            VpnState.ERROR,
            info.ifBlank { name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase) },
        )
        "SESSION_FINISHED" -> when {
            error -> Mapped(VpnState.ERROR, info.ifBlank { "The VPN session ended with an error." })
            else -> Mapped(VpnState.DISCONNECTED, clearError = true)
        }
        else -> when {
            fatal -> Mapped(VpnState.ERROR, info.ifBlank { "The VPN engine stopped." })
            else -> Mapped(null, if (error) info.ifBlank { null } else null)
        }
    }
}
