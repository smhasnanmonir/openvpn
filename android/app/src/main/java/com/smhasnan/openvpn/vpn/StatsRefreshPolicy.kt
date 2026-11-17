package com.smhasnan.openvpn.vpn

/**
 * Battery-aware refresh cadence for the traffic counters.
 *
 * Observed traffic stats are only useful while the tunnel can move packets,
 * and they are only looked at while the screen is on. Polling at the
 * interactive cadence in the background would keep the CPU busy for data no
 * one can see.
 */
object StatsRefreshPolicy {
    /** Fast enough to feel live in the UI, slow enough to stay cheap. */
    const val INTERACTIVE_INTERVAL_MS = 1_500L

    /** While the screen is off nobody watches the counters; refresh rarely. */
    const val SCREEN_OFF_INTERVAL_MS = 10_000L

    fun intervalMs(isInteractive: Boolean): Long =
        if (isInteractive) INTERACTIVE_INTERVAL_MS else SCREEN_OFF_INTERVAL_MS

    fun shouldPoll(state: VpnState): Boolean =
        state == VpnState.CONNECTED || state == VpnState.RECONNECTING
}
