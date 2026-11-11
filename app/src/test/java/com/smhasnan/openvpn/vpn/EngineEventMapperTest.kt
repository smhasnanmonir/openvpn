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
        // The real payload is the generic unhandled-exception text (see
        // native_bridge.cpp); the engine log for the same session supplies the
        // keying-material hint. This is the payload the app actually receives.
        val mapped = EngineEventMapper.map(
            "SESSION_FINISHED",
            "client_connect_unhandled_exception",
            error = true,
            fatal = false,
            rekeyHint = true,
        )
        assertEquals(VpnState.ERROR, mapped.state)
        assertTrue(mapped.message!!.contains("key renegotiation", ignoreCase = true))
        assertTrue(mapped.message!!.contains("Reconnect", ignoreCase = true))
        assertFalse(mapped.message!!.contains("client_connect_unhandled_exception"))
    }

    @Test
    fun keyingMaterialTextStillExplainsTheRekeyWithoutAHint() {
        val mapped = EngineEventMapper.map(
            "SESSION_FINISHED",
            "TLS Keying material export error",
            error = true,
            fatal = false,
        )
        assertEquals(VpnState.ERROR, mapped.state)
        assertTrue(mapped.message!!.contains("key renegotiation", ignoreCase = true))
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
    fun pauseAfterAConnectedSessionKeepsTheSessionAlive() {
        val mapped = EngineEventMapper.map("PAUSE", "", false, false)
        assertEquals(VpnState.RECONNECTING, mapped.state)
        assertTrue(mapped.clearError)
        assertTrue(mapped.message!!.contains("timed out", ignoreCase = true))
        assertTrue(mapped.message!!.contains("retry", ignoreCase = true))
    }

    @Test
    fun resumeReturnsToTheReconnectingStateAndClearsTheError() {
        val mapped = EngineEventMapper.map("RESUME", "", false, false)
        assertEquals(VpnState.RECONNECTING, mapped.state)
        assertTrue(mapped.clearError)
    }

    @Test
    fun certificateVerificationFailureIsActionable() {
        val mapped = EngineEventMapper.map("CERT_VERIFY_FAIL", "", true, true)
        assertEquals(VpnState.ERROR, mapped.state)
        assertTrue(mapped.message!!.contains("certificate", ignoreCase = true))
        assertTrue(mapped.message!!.contains("serverAuth", ignoreCase = true))
        assertTrue(mapped.message!!.contains("clock", ignoreCase = true))
    }

    @Test
    fun networkUnavailableSessionFailureIsActionable() {
        val mapped = EngineEventMapper.map(
            "SESSION_FINISHED",
            "Network Unavailable",
            error = true,
            fatal = false,
        )
        assertEquals(VpnState.ERROR, mapped.state)
        assertTrue(mapped.message!!.contains("No usable network", ignoreCase = true))
    }

    @Test
    fun nonFatalErrorEventKeepsTheCurrentStateButReportsTheDetail() {
        val mapped = EngineEventMapper.map("TRANSPORT_ERROR", "NETWORK_EOF_ERROR", true, false)
        assertNull(mapped.state)
        assertEquals("NETWORK_EOF_ERROR", mapped.message)
    }

    @Test
    fun appTunHintOverridesGenericTunTerminalFailures() {
        listOf(
            "SESSION_FINISHED",
            "TUN_SETUP_FAILED",
            "TUN_ERROR",
            "TUN_IFACE_CREATE",
            "TUN_IFACE_DISABLED",
            "CLIENT_HALT",
        ).forEach { name ->
            val mapped = EngineEventMapper.map(
                name,
                "tun_error",
                error = true,
                fatal = false,
                tunHint = "DNS refused: no tunnel resolver is available.",
            )
            assertEquals(name, VpnState.ERROR, mapped.state)
            assertEquals(name, "DNS refused: no tunnel resolver is available.", mapped.message)
        }
    }

    @Test
    fun sessionFinishedWithTunHintUsesTheAppReason() {
        // Android builder failures surface as SESSION_FINISHED with a generic
        // engine text; the app's own reason must be the one the user sees.
        val mapped = EngineEventMapper.map(
            "SESSION_FINISHED",
            "tun_error",
            error = true,
            fatal = false,
            tunHint = "DNS refused",
        )
        assertEquals(VpnState.ERROR, mapped.state)
        assertEquals("DNS refused", mapped.message)
    }

    @Test
    fun tunHintDoesNotAffectSuccessfulOrCleanEvents() {
        val connected = EngineEventMapper.map(
            "CONNECTED",
            "",
            error = false,
            fatal = false,
            tunHint = "some hint",
        )
        assertEquals(VpnState.CONNECTED, connected.state)
        assertNull(connected.message)

        val finished = EngineEventMapper.map(
            "SESSION_FINISHED",
            "clean shutdown",
            error = false,
            fatal = false,
            tunHint = "some hint",
        )
        assertEquals(VpnState.DISCONNECTED, finished.state)
        assertNull(finished.message)
    }

    @Test
    fun tunHintDoesNotAffectAuthOrChallengeEvents() {
        val auth = EngineEventMapper.map(
            "AUTH_FAILED",
            "bad password",
            error = true,
            fatal = true,
            tunHint = "some hint",
        )
        assertEquals(VpnState.AUTH_FAILED, auth.state)
        assertEquals("bad password", auth.message)

        val challenge = EngineEventMapper.map(
            "DYNAMIC_CHALLENGE",
            "Enter token",
            error = false,
            fatal = false,
            tunHint = "some hint",
        )
        assertEquals(VpnState.NEEDS_CHALLENGE, challenge.state)
        assertEquals("Enter token", challenge.message)
    }

    @Test
    fun tunHintDoesNotOverrideUnrelatedErrorEvents() {
        val mapped = EngineEventMapper.map(
            "CERT_VERIFY_FAIL",
            "",
            error = true,
            fatal = true,
            tunHint = "some hint",
        )
        assertEquals(VpnState.ERROR, mapped.state)
        assertFalse(mapped.message!!.contains("some hint"))
        assertTrue(mapped.message!!.contains("certificate", ignoreCase = true))
    }

    @Test
    fun rekeyHintStillWinsForKeyExportFailuresWithoutATunHint() {
        val mapped = EngineEventMapper.map(
            "SESSION_FINISHED",
            "client_connect_unhandled_exception",
            error = true,
            fatal = false,
            rekeyHint = true,
            tunHint = null,
        )
        assertEquals(VpnState.ERROR, mapped.state)
        assertTrue(mapped.message!!.contains("key renegotiation", ignoreCase = true))
    }
}
