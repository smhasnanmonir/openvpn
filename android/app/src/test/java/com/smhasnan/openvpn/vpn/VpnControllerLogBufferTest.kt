package com.smhasnan.openvpn.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Covers the coalesced diagnostics publisher added for issue #1/requirement 4:
 * native callbacks push bursts of lines, which are published as one bounded
 * snapshot per window, while errors (and clear/reset) still surface promptly.
 */
class VpnControllerLogBufferTest {
    private companion object {
        const val MAX_LOG_LINES = 500
        const val BURST_SIZE = 32

        /** Longer than the publisher's coalesce window, with CI slack. */
        const val SETTLE_MS = 400L
        const val AWAIT_TIMEOUT_MS = 5_000L
    }

    @Before
    fun resetState() = runBlocking {
        VpnController.reset()
        VpnController.clearLogs()
        // Let any publication scheduled by a previous test drain so every test
        // starts from an idle, empty publisher.
        delay(SETTLE_MS)
    }

    @After
    fun tearDown() = runBlocking {
        VpnController.reset()
        VpnController.clearLogs()
    }

    @Test
    fun burstIsPublishedAfterTheCoalesceWindowAsOneSnapshot() = runBlocking {
        val publications = AtomicInteger(0)
        val collector = launch(Dispatchers.Default) {
            VpnController.logs.collect { snapshot ->
                if (snapshot.isNotEmpty()) publications.incrementAndGet()
            }
        }
        try {
            delay(100) // let the collector subscribe to the empty snapshot
            val startedAt = System.currentTimeMillis()
            repeat(BURST_SIZE) { index ->
                VpnController.appendLog(LogLevel.INFO, "burst line $index")
            }
            awaitCondition { publications.get() > 0 }
            val elapsed = System.currentTimeMillis() - startedAt
            delay(SETTLE_MS) // a second publication would have surfaced by now

            assertEquals("burst should be coalesced into one publication", 1, publications.get())
            val snapshot = VpnController.logs.value
            assertEquals(BURST_SIZE, snapshot.size)
            assertEquals("burst line 0", snapshot.first().message)
            assertEquals("burst line ${BURST_SIZE - 1}", snapshot.last().message)
            assertTrue(
                "published after ${elapsed}ms; expected the coalesce window",
                elapsed >= 150L,
            )
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun keepsOnlyTheMostRecentFiveHundredLines() = runBlocking {
        repeat(600) { index -> VpnController.appendLog(LogLevel.INFO, "line-$index") }
        val snapshot = awaitLogs { logs ->
            logs.size == MAX_LOG_LINES && logs.last().message == "line-599"
        }

        assertEquals(MAX_LOG_LINES, snapshot.size)
        assertEquals("line-100", snapshot.first().message)
        assertEquals("line-599", snapshot.last().message)
    }

    @Test
    fun consecutiveDuplicateLinesAreSuppressed() = runBlocking {
        repeat(3) { VpnController.appendLog(LogLevel.INFO, "duplicate diagnostic") }
        val snapshot = awaitLogs { it.isNotEmpty() }

        assertEquals(1, snapshot.size)
        assertEquals("duplicate diagnostic", snapshot.single().message)
    }

    @Test
    fun duplicateSuppressionSpansThePublishBoundary() = runBlocking {
        VpnController.appendLog(LogLevel.INFO, "repeat me")
        awaitLogs { logs -> logs.any { it.message == "repeat me" } }

        VpnController.appendLog(LogLevel.INFO, "repeat me")
        delay(SETTLE_MS)

        assertEquals(1, VpnController.logs.value.size)
    }

    @Test
    fun nonConsecutiveDuplicatesAreKept() = runBlocking {
        VpnController.appendLog(LogLevel.INFO, "alpha")
        VpnController.appendLog(LogLevel.INFO, "beta")
        VpnController.appendLog(LogLevel.INFO, "alpha")

        val snapshot = awaitLogs { it.size == 3 }
        assertEquals(listOf("alpha", "beta", "alpha"), snapshot.map { it.message })
    }

    @Test
    fun clearLogsDropsBufferedLinesBeforeTheyPublish() = runBlocking {
        repeat(10) { index -> VpnController.appendLog(LogLevel.INFO, "buffered-$index") }
        VpnController.clearLogs()

        delay(SETTLE_MS)
        assertTrue("buffered lines must not resurface", VpnController.logs.value.isEmpty())
    }

    @Test
    fun clearLogsEmptiesThePublishedSnapshotImmediately() = runBlocking {
        VpnController.appendLog(LogLevel.INFO, "visible")
        awaitLogs { logs -> logs.any { it.message == "visible" } }

        VpnController.clearLogs()

        assertTrue(VpnController.logs.value.isEmpty())
    }

    @Test
    fun resetClearsBufferedLogsAndEmitsAnEmptySnapshot() = runBlocking {
        repeat(5) { index -> VpnController.appendLog(LogLevel.INFO, "pending-$index") }
        VpnController.reset()

        assertTrue(VpnController.logs.value.isEmpty())
        assertEquals(VpnState.DISCONNECTED, VpnController.snapshot.value.state)

        delay(SETTLE_MS)
        assertTrue("reset must not let buffered lines resurface", VpnController.logs.value.isEmpty())
    }

    @Test
    fun errorIsPublishedWithoutWaitingForTheCoalesceWindow() = runBlocking {
        VpnController.appendLog(LogLevel.INFO, "before the failure")
        VpnController.error("engine failed")

        val snapshot = VpnController.logs.value
        assertEquals(2, snapshot.size)
        assertEquals("before the failure", snapshot.first().message)
        assertEquals(LogLevel.ERROR, snapshot.last().level)
        assertEquals("engine failed", snapshot.last().message)
    }

    @Test
    fun mappedFailureKeepsRawEngineDetailInDiagnostics() = runBlocking {
        VpnController.prepare("profile", "Provider profile", "🛡️")
        VpnController.engineEvent("CONNECTING", "", false, false)
        VpnController.engineEvent(
            "SESSION_FINISHED",
            "client_connect_unhandled_exception",
            error = true,
            fatal = false,
        )

        val snapshot = awaitLogs { logs ->
            logs.any { it.message == "Engine detail: client_connect_unhandled_exception" }
        }
        assertTrue(
            snapshot.any {
                it.level == LogLevel.ERROR && it.message.contains("unexpectedly")
            },
        )
        assertTrue(
            snapshot.any { it.message == "Engine detail: client_connect_unhandled_exception" },
        )
        assertEquals(VpnState.ERROR, VpnController.snapshot.value.state)
        assertFalse(
            VpnController.snapshot.value.error!!.contains("client_connect_unhandled_exception"),
        )
    }

    @Test
    fun rekeyHintExplainsTheRekeyAndKeepsTheRawEngineDetail() = runBlocking {
        VpnController.prepare("profile", "Provider profile", "🛡️")
        VpnController.engineEvent("CONNECTING", "", false, false)
        VpnController.engineEvent(
            "SESSION_FINISHED",
            "client_connect_unhandled_exception",
            error = true,
            fatal = false,
            rekeyHint = true,
        )

        val snapshot = awaitLogs { logs ->
            logs.any { it.message == "Engine detail: client_connect_unhandled_exception" }
        }
        assertTrue(
            snapshot.any {
                it.level == LogLevel.ERROR && it.message.contains("key renegotiation", ignoreCase = true)
            },
        )
        assertTrue(
            snapshot.any { it.message == "Engine detail: client_connect_unhandled_exception" },
        )
        assertFalse(
            VpnController.snapshot.value.error!!.contains("client_connect_unhandled_exception"),
        )
    }

    @Test
    fun concurrentAppendsFromNativeThreadsAreThreadSafe() = runBlocking {
        val threads = 8
        val perThread = 100
        val workers = (0 until threads).map { threadIndex ->
            Thread {
                repeat(perThread) { lineIndex ->
                    VpnController.appendLog(LogLevel.INFO, "t$threadIndex-$lineIndex")
                }
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }

        val snapshot = awaitLogs { it.size == MAX_LOG_LINES }
        assertEquals(MAX_LOG_LINES, snapshot.size)
        assertEquals(
            "concurrent appends must not duplicate or corrupt lines",
            MAX_LOG_LINES,
            snapshot.map { it.message }.toSet().size,
        )
    }

    @Test
    fun blankLinesAreSkipped() = runBlocking {
        VpnController.appendLog(LogLevel.INFO, "")
        VpnController.appendLog(LogLevel.INFO, "   ")

        delay(SETTLE_MS)
        assertTrue(VpnController.logs.value.isEmpty())
    }

    private suspend fun awaitLogs(
        timeoutMs: Long = AWAIT_TIMEOUT_MS,
        predicate: (List<LogEntry>) -> Boolean,
    ): List<LogEntry> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val current = VpnController.logs.value
            if (predicate(current)) return current
            delay(10)
        }
        return VpnController.logs.value
    }

    private suspend fun awaitCondition(
        timeoutMs: Long = AWAIT_TIMEOUT_MS,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            delay(10)
        }
    }
}
