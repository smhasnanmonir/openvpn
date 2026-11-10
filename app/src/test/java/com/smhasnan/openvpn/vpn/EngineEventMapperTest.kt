package com.smhasnan.openvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun unhandledEngineExceptionGetsAnActionableMessage() {
        val mapped = EngineEventMapper.map(
            "SESSION_FINISHED",
            "client_connect_unhandled_exception",
            error = true,
            fatal = false,
        )
        assertEquals(VpnState.ERROR, mapped.state)
        assertTrue(mapped.message!!.contains("unexpectedly"))
        assertTrue(mapped.message!!.contains("Diagnostics"))
        assertFalse(mapped.message!!.contains("client_connect_unhandled_exception"))
    }

    @Test
    fun keyingMaterialExportFailureExplainsTheRekey() {
        val mapped = EngineEventMapper.map(
            "SESSION_FINISHED",
            "TLS Keying material export error",
            error = true,
            fatal = false,
        )
        assertEquals(VpnState.ERROR, mapped.state)
        assertTrue(mapped.message!!.contains("key renegotiation", ignoreCase = true))
        assertTrue(mapped.message!!.contains("Reconnect", ignoreCase = true))
    }

    @Test
    fun otherSessionFinishedErrorsKeepTheEngineDetail() {
        val mapped = EngineEventMapper.map("SESSION_FINISHED", "failed", true, false)
        assertEquals("failed", mapped.message)
    }

    @Test
    fun silentFatalTimeoutEventsCarryActionableMessages() {
        listOf("CONNECTION_TIMEOUT", "INACTIVE_TIMEOUT", "TLS_VERSION_MIN").forEach { name ->
            val mapped = EngineEventMapper.map(name, "", true, true)
            assertEquals(name, VpnState.ERROR, mapped.state)
            assertTrue(name, !mapped.message.isNullOrBlank())

            // The same events must still pass through any core-supplied detail.
            assertEquals(name, "core detail", EngineEventMapper.map(name, "core detail", true, true).message)
        }
        assertTrue(
            EngineEventMapper.map("CONNECTION_TIMEOUT", "", true, true).message!!
                .contains("tls-cipher", ignoreCase = true),
        )
    }

    @Test
    fun disconnectedKeepsAnExistingErrorInTheController() {
        // The mapper itself must not request clearing an error on DISCONNECTED:
        // the core sends this event right after a fatal failure.
        assertFalse(EngineEventMapper.map("DISCONNECTED", "", false, false).clearError)
    }

    @Test
    fun nonFatalErrorEventKeepsTheCurrentStateButReportsTheDetail() {
        val mapped = EngineEventMapper.map("TRANSPORT_ERROR", "NETWORK_EOF_ERROR", true, false)
        assertNull(mapped.state)
        assertEquals("NETWORK_EOF_ERROR", mapped.message)
    }
}
