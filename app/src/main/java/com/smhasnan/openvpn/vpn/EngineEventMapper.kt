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
    /**
     * Core 3.9 reports a rekey failure as this exception because
     * `Error::KEY_EXPANSION_ERROR` is not handled by `ClientConnect`'s error
     * switch. The raw name is meaningless to a user, so give it an actionable
     * explanation while keeping the engine detail in Diagnostics.
     */
    private const val UNHANDLED_ENGINE_EXCEPTION = "client_connect_unhandled_exception"

    private const val KEYING_MATERIAL_ERROR = "TLS Keying material export error"

    private const val UNHANDLED_ENGINE_MESSAGE =
        "The VPN engine stopped unexpectedly. Reconnect to continue; Diagnostics has the engine error."

    private const val REKEY_KEY_EXPORT_MESSAGE =
        "The VPN session ended during key renegotiation because the TLS backend could not " +
            "export keying material. Reconnect to continue."

    private const val NETWORK_UNAVAILABLE_MESSAGE =
        "No usable network is available. Check Wi-Fi or mobile data and try again."

    data class Mapped(
        val state: VpnState?,
        val message: String? = null,
        val clearError: Boolean = false,
        val requiresChallenge: Boolean = false,
    )

    /**
     * @param rekeyHint true when the engine's log for this session contains a
     *   TLS keying-material export failure. Core reports the rekey failure as
     *   the generic `client_connect_unhandled_exception` text, so the event
     *   payload alone cannot distinguish it from other unhandled exceptions;
     *   the caller supplies the hint from the preceding engine log line.
     */
    fun map(
        name: String,
        info: String,
        error: Boolean,
        fatal: Boolean,
        rekeyHint: Boolean = false,
    ): Mapped = when (name) {
        "CONNECTING" -> Mapped(VpnState.CONNECTING, clearError = true)
        "CONNECTED" -> Mapped(VpnState.CONNECTED, clearError = true)
        "RECONNECTING" -> Mapped(VpnState.RECONNECTING, clearError = true)
        // Core pauses instead of stopping when a reconnect attempt exceeds
        // connTimeout after the session has already been connected. The
        // service keeps the session alive and retries when the network is
        // usable again (for example after the device wakes from sleep).
        "PAUSE" -> Mapped(
            VpnState.RECONNECTING,
            "The connection attempt timed out. The session is kept alive and will retry " +
                "when the network is available.",
            clearError = true,
        )
        "RESUME" -> Mapped(VpnState.RECONNECTING, clearError = true)
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
        "CERT_VERIFY_FAIL" -> Mapped(
            VpnState.ERROR,
            info.ifBlank {
                "The server certificate could not be verified. The app requires a server " +
                    "certificate (serverAuth extended key usage) for CA-based profiles; check " +
                    "the profile's CA, server identity and the device clock."
            },
        )
        "PROXY_NEED_CREDS" -> Mapped(
            VpnState.ERROR,
            info.ifBlank { "The configured proxy requires credentials." },
        )
        "PROFILE_REJECTED", "CREDENTIALS_REJECTED", "TUN_SETUP_FAILED", "TUN_ERROR",
        "TUN_IFACE_CREATE", "TUN_IFACE_DISABLED", "ENGINE_ERROR", "CLIENT_HALT",
        "CLIENT_SETUP", "EPKI_ERROR", "EPKI_INVALID_ALIAS",
        "PROXY_ERROR", "RELAY_ERROR", "COMPRESS_ERROR",
        "NTLM_MISSING_CRYPTO", "SESSION_EXPIRED" -> Mapped(
            VpnState.ERROR,
            info.ifBlank { humanized(name) },
        )
        "SESSION_FINISHED" -> when {
            error -> Mapped(
                VpnState.ERROR,
                when {
                    // Core reports a rekey key-export failure as the generic
                    // client_connect_unhandled_exception text, so prefer the
                    // log-derived hint before the generic branch.
                    rekeyHint ||
                        info.contains(KEYING_MATERIAL_ERROR, ignoreCase = true) ||
                        info.contains("KEY_EXPANSION_ERROR", ignoreCase = true) ->
                        REKEY_KEY_EXPORT_MESSAGE
                    info.contains(UNHANDLED_ENGINE_EXCEPTION, ignoreCase = true) ->
                        UNHANDLED_ENGINE_MESSAGE
                    info.contains("Network Unavailable", ignoreCase = true) ||
                        info.contains("NETWORK_UNAVAILABLE", ignoreCase = true) ->
                        NETWORK_UNAVAILABLE_MESSAGE
                    info.isNotBlank() -> info
                    else -> "The VPN session ended with an error."
                },
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
