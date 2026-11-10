package com.smhasnan.openvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineEventMapperTest {
    @Test
    fun mapsCoreConnectionEvents() {
        assertEquals(VpnState.CONNECTING, EngineEventMapper.map("CONNECTING", "", false, false).state)
        assertEquals(VpnState.CONNECTED, EngineEventMapper.map("CONNECTED", "", false, false).state)
        assertEquals(VpnState.RECONNECTING, EngineEventMapper.map("RECONNECTING", "", false, false).state)
        assertEquals(VpnState.DISCONNECTED, EngineEventMapper.map("DISCONNECTED", "", false, false).state)
    }

    @Test
    fun authFailureCarriesServerMessage() {
        val mapped = EngineEventMapper.map("AUTH_FAILED", "AUTH_FAILED", false, true)
        assertEquals(VpnState.AUTH_FAILED, mapped.state)
        assertEquals("AUTH_FAILED", mapped.message)
    }

    @Test
    fun dynamicChallengeNeedsResponse() {
        val mapped = EngineEventMapper.map("DYNAMIC_CHALLENGE", "Enter token", false, false)
        assertEquals(VpnState.NEEDS_CHALLENGE, mapped.state)
        assertTrue(mapped.requiresChallenge)
    }

    @Test
    fun unknownNonFatalEventDoesNotChangeState() {
        assertNull(EngineEventMapper.map("ECHO", "hello", false, false).state)
    }

    @Test
    fun sessionFinishedWithErrorIsError() {
        assertEquals(
            VpnState.ERROR,
            EngineEventMapper.map("SESSION_FINISHED", "failed", true, false).state,
        )
    }

    @Test
    fun sessionFinishedWithoutErrorIsDisconnected() {
        assertEquals(
            VpnState.DISCONNECTED,
            EngineEventMapper.map("SESSION_FINISHED", "", false, false).state,
        )
    }
}
