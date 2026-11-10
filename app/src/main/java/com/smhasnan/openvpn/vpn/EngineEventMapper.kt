package com.smhasnan.openvpn.vpn

/**
 * Maps OpenVPN 3 Core event names to the app's state machine. Only events that
 * actually come from the pinned native engine are mapped here.
 *
 * Core's fatal timeout events (`CONNECTION_TIMEOUT`, `INACTIVE_TIMEOUT`,
 * `TLS_VERSION_MIN`, ...) carry no `info` text: their `render()` is empty. They
 * therefore need an explicit, actionable message here, otherwise the UI and the
 * diagnostics list would show nothing for a failed connection.
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
        // The core emits DISCONNECTED right after a fatal failure as well; the
        // transition is handled by VpnController so an error is not erased.
        "DISCONNECTED" -> Mapped(VpnState.DISCONNECTED)
        "AUTH_FAILED" -> Mapped(VpnState.AUTH_FAILED, info.ifBlank { "Authentication failed." })
        "DYNAMIC_CHALLENGE" -> Mapped(
            VpnState.NEEDS_CHALLENGE,
            info.ifBlank { "The server requires an additional response." },
            requiresChallenge = true,
        )
        "CONNECTION_TIMEOUT" -> Mapped(
            VpnState.ERROR,
            info.ifBlank {
                "Connection timed out before the TLS handshake completed. Check the profile's " +
                    "tls-cipher / tls-version-min options and that the server is reachable."
            },
        )
        "INACTIVE_TIMEOUT" -> Mapped(
            VpnState.ERROR,
            info.ifBlank {
                "The session was closed because the server stopped sending traffic " +
                    "(inactive timeout)."
            },
        )
        "TLS_VERSION_MIN" -> Mapped(
            VpnState.ERROR,
            info.ifBlank {
                "The client and server could not agree on a TLS version. Update the profile's " +
                    "TLS options for this server."
            },
        )
        "PROFILE_REJECTED", "CREDENTIALS_REJECTED", "TUN_SETUP_FAILED", "TUN_ERROR",
        "TUN_IFACE_CREATE", "TUN_IFACE_DISABLED", "ENGINE_ERROR", "CLIENT_HALT",
        "CLIENT_SETUP", "CERT_VERIFY_FAIL", "EPKI_ERROR", "EPKI_INVALID_ALIAS",
        "PROXY_ERROR", "PROXY_NEED_CREDS", "RELAY_ERROR", "COMPRESS_ERROR",
        "NTLM_MISSING_CRYPTO", "SESSION_EXPIRED" -> Mapped(
            VpnState.ERROR,
            info.ifBlank { humanized(name) },
        )
        "SESSION_FINISHED" -> when {
            error -> Mapped(
                VpnState.ERROR,
                info.ifBlank { "The VPN session ended with an error." },
            )
            else -> Mapped(VpnState.DISCONNECTED, clearError = true)
        }
        else -> when {
            fatal -> Mapped(VpnState.ERROR, info.ifBlank { humanized(name) })
            // Non-fatal error events (transport errors between retries) are
            // logged but do not replace the connection state.
            error -> Mapped(null, info.ifBlank { humanized(name) })
            else -> Mapped(null, null)
        }
    }

    private fun humanized(name: String): String =
        name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)
}
