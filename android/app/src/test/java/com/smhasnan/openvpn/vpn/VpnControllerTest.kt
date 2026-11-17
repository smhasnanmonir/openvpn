package com.smhasnan.openvpn.vpn

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VpnControllerTest {
    @Before
    fun resetState() {
        VpnController.reset()
        VpnController.clearLogs()
    }

    @After
    fun tearDown() {
        VpnController.reset()
        VpnController.clearLogs()
    }

    private fun snapshot() = VpnController.snapshot.value

    @Test
    fun timeoutFailureSurvivesTheCoreTrailingDisconnectedEvent() {
        VpnController.prepare("profile", "Provider profile", "🛡️")
        VpnController.engineEvent("CONNECTING", "", false, false)

        // Core's fatal timeout event carries no info text.
        VpnController.engineEvent("CONNECTION_TIMEOUT", "", true, true)
        assertEquals(VpnState.ERROR, snapshot().state)
        assertNotNull(snapshot().error)
        assertTrue(snapshot().error!!.contains("timed out", ignoreCase = true))

        // The core then emits a non-error DISCONNECTED event.
        VpnController.engineEvent("DISCONNECTED", "", false, false)
        assertEquals(VpnState.ERROR, snapshot().state)
        assertNotNull(snapshot().error)
        assertEquals("Provider profile", snapshot().profileName)

        // A successful SESSION_FINISHED must not erase the error either.
        VpnController.engineEvent("SESSION_FINISHED", "", false, false)
        assertEquals(VpnState.ERROR, snapshot().state)
        assertNotNull(snapshot().error)
    }

    @Test
    fun failureReasonIsWrittenToDiagnostics() {
        VpnController.prepare("profile", "Provider profile", "🛡️")
        VpnController.engineEvent("CONNECTING", "", false, false)
        VpnController.engineEvent("CONNECTION_TIMEOUT", "", true, true)

        val errorLines = VpnController.logs.value.filter { it.level == LogLevel.ERROR }
        assertEquals(1, errorLines.size)
        assertTrue(errorLines.single().message.contains("timed out", ignoreCase = true))
        assertTrue(errorLines.single().message.contains("tls-cipher", ignoreCase = true))
    }

    @Test
    fun disconnectedWhileConnectingKeepsTheConnectionAttemptAlive() {
        VpnController.prepare("profile", "Provider profile", "🛡️")
        VpnController.engineEvent("CONNECTING", "", false, false)

        VpnController.engineEvent("DISCONNECTED", "", false, false)
        assertEquals(VpnState.CONNECTING, snapshot().state)

        // Only the terminal session-finished event ends the attempt.
        VpnController.engineEvent("SESSION_FINISHED", "", false, false)
        assertEquals(VpnState.DISCONNECTED, snapshot().state)
        assertNull(snapshot().error)
    }

    @Test
    fun cleanDisconnectAfterConnectedStillMovesToDisconnected() {
        VpnController.prepare("profile", "Provider profile", "🛡️")
        VpnController.engineEvent("CONNECTING", "", false, false)
        VpnController.engineEvent("CONNECTED", "", false, false)
        assertEquals(VpnState.CONNECTED, snapshot().state)

        VpnController.engineEvent("DISCONNECTED", "", false, false)
        assertEquals(VpnState.DISCONNECTED, snapshot().state)
        assertNull(snapshot().error)
    }

    @Test
    fun nonFatalTransportErrorIsLoggedWithoutReplacingTheState() {
        VpnController.prepare("profile", "Provider profile", "🛡️")
        VpnController.engineEvent("CONNECTING", "", false, false)

        VpnController.engineEvent("TRANSPORT_ERROR", "NETWORK_EOF_ERROR", true, false)
        assertEquals(VpnState.CONNECTING, snapshot().state)
        assertTrue(
            VpnController.logs.value.any {
                it.level == LogLevel.ERROR && it.message.contains("NETWORK_EOF_ERROR")
            },
        )
    }

    @Test
    fun startingANewAttemptClearsThePreviousError() {
        VpnController.prepare("profile", "Provider profile", "🛡️")
        VpnController.engineEvent("CONNECTING", "", false, false)
        VpnController.engineEvent("CONNECTION_TIMEOUT", "", true, true)
        assertEquals(VpnState.ERROR, snapshot().state)

        VpnController.engineEvent("CONNECTING", "", false, false)
        assertEquals(VpnState.CONNECTING, snapshot().state)
        assertNull(snapshot().error)
    }

    @Test
    fun authFailureSurvivesTheTrailingDisconnectedAndSessionFinishedEvents() {
        VpnController.prepare("profile", "Provider profile", "🛡️")
        VpnController.engineEvent("CONNECTING", "", false, false)

        VpnController.engineEvent("AUTH_FAILED", "AUTH_FAILED", true, true)
        assertEquals(VpnState.AUTH_FAILED, snapshot().state)
        assertEquals("AUTH_FAILED", snapshot().error)

        VpnController.engineEvent("DISCONNECTED", "", false, false)
        assertEquals(VpnState.AUTH_FAILED, snapshot().state)
        assertEquals("AUTH_FAILED", snapshot().error)

        VpnController.engineEvent("SESSION_FINISHED", "", false, false)
        assertEquals(VpnState.AUTH_FAILED, snapshot().state)
        assertEquals("AUTH_FAILED", snapshot().error)
    }

    @Test
    fun pausedForPortalMarksADisconnectedPauseAndKeepsTheProfile() {
        VpnController.prepare("profile", "Provider profile", "🛡️")
        VpnController.engineEvent("CONNECTING", "", false, false)
        VpnController.engineEvent("CONNECTED", "", false, false)

        VpnController.pausedForPortal()

        assertEquals(VpnState.DISCONNECTED, snapshot().state)
        assertTrue(snapshot().pausedForPortal)
        assertEquals("profile", snapshot().profileId)
        assertEquals("Provider profile", snapshot().profileName)
        assertNull(snapshot().connectedAtMillis)
    }

    @Test
    fun portalPauseTeardownKeepsThePauseFlagButAnExplicitDisconnectClearsIt() {
        VpnController.prepare("profile", "Provider profile", "🛡️")
        VpnController.pausedForPortal()

        // A portal-pause teardown must keep the banner/resume affordance...
        VpnController.disconnected(keepPaused = true)
        assertEquals(VpnState.DISCONNECTED, snapshot().state)
        assertTrue(snapshot().pausedForPortal)
        assertEquals("profile", snapshot().profileId)

        // ...an explicit disconnect must clear it, otherwise the UI would offer a
        // "Resume" for a session the user deliberately ended.
        VpnController.disconnected()
        assertFalse(snapshot().pausedForPortal)
    }

    @Test
    fun connectedEventClearsThePortalPause() {
        VpnController.prepare("profile", "Provider profile", "🛡️")
        VpnController.pausedForPortal()

        VpnController.engineEvent("CONNECTED", "", false, false)

        assertEquals(VpnState.CONNECTED, snapshot().state)
        assertFalse(snapshot().pausedForPortal)
    }

    @Test
    fun errorClearsThePortalPause() {
        VpnController.prepare("profile", "Provider profile", "🛡️")
        VpnController.pausedForPortal()

        VpnController.error("DNS refused")

        assertEquals(VpnState.ERROR, snapshot().state)
        assertFalse(snapshot().pausedForPortal)
    }
}
