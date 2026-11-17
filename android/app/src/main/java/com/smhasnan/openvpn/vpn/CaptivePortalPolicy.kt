package com.smhasnan.openvpn.vpn

/**
 * Pure captive-portal policy (#18).
 *
 * A full-tunnel VPN makes a captive portal unreachable, so the user cannot sign
 * in to hotel/airport Wi-Fi while the tunnel is up. The service can pause the
 * tunnel for the sign-in, but it must only claim to resume automatically once
 * Android reports the network validated; guessing would either leak traffic
 * through a half-open portal or leave the user disconnected.
 */
data class NetworkState(
    val hasInternet: Boolean,
    val validated: Boolean,
    val captivePortal: Boolean,
)

enum class PortalState {
    /** Not enough information yet (no network, or validation still in progress). */
    UNKNOWN,

    /** The network is validated and the portal is not required. */
    NOT_REQUIRED,

    /** Android reports a captive portal that needs a sign-in. */
    SIGN_IN_REQUIRED,
}

object CaptivePortalPolicy {
    fun classify(state: NetworkState): PortalState = when {
        // The explicit portal capability wins: even if the network is validated,
        // Android still knows a sign-in is required.
        state.captivePortal -> PortalState.SIGN_IN_REQUIRED
        // Without Internet access there is nothing to sign in to and validation
        // cannot be interpreted.
        !state.hasInternet -> PortalState.UNKNOWN
        state.validated -> PortalState.NOT_REQUIRED
        // Internet is present but not yet validated: the portal sign-in may still
        // be in progress, so do not resume the tunnel yet.
        else -> PortalState.UNKNOWN
    }

    /**
     * True only when the tunnel was paused for a portal and the network is now
     * validated. The service reconnects on the next network callback otherwise.
     */
    fun shouldAutoResume(pausedForPortal: Boolean, state: PortalState): Boolean =
        pausedForPortal && state == PortalState.NOT_REQUIRED
}
