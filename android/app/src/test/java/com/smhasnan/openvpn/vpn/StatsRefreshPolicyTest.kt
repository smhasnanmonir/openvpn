package com.smhasnan.openvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsRefreshPolicyTest {
    @Test
    fun interactivePollingRunsAtTheFastCadence() {
        assertEquals(1_500L, StatsRefreshPolicy.intervalMs(isInteractive = true))
    }

    @Test
    fun screenOffPollingIsThrottled() {
        assertEquals(10_000L, StatsRefreshPolicy.intervalMs(isInteractive = false))
        assertTrue(
            "screen-off polling must be slower than interactive polling",
            StatsRefreshPolicy.intervalMs(false) > StatsRefreshPolicy.intervalMs(true),
        )
    }

    @Test
    fun trafficStatsArePolledWhileConnectedAndWhileReconnecting() {
        assertTrue(StatsRefreshPolicy.shouldPoll(VpnState.CONNECTED))
        assertTrue(StatsRefreshPolicy.shouldPoll(VpnState.RECONNECTING))
    }

    @Test
    fun disconnectedAndBusyStatesAreNotPolled() {
        for (state in VpnState.entries) {
            if (state == VpnState.CONNECTED || state == VpnState.RECONNECTING) continue
            assertFalse("$state must not poll traffic stats", StatsRefreshPolicy.shouldPoll(state))
        }
    }

    @Test
    fun onlyLiveTunnelStatesArePolled() {
        assertEquals(
            setOf(VpnState.CONNECTED, VpnState.RECONNECTING),
            VpnState.entries.filter { StatsRefreshPolicy.shouldPoll(it) }.toSet(),
        )
    }
}
