package com.smhasnan.openvpn.nativevpn

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Core names TUN counters from the tunnel device's perspective, which is the
 * opposite of the app's download/upload labels. These tests pin the mapping so
 * a future stats change cannot silently flip the Home screen again.
 */
class NativeEngineStatsTest {
    @Test
    fun tunCountersMapDownloadToTunBytesOutAndUploadToTunBytesIn() {
        val stats = NativeEngine.stats(
            """{"TUN_BYTES_IN":100,"TUN_BYTES_OUT":900,"TUN_PACKETS_IN":2,"TUN_PACKETS_OUT":8}""",
        )

        assertEquals("download is data written to the TUN", 900L, stats.bytesIn)
        assertEquals("upload is data read from the TUN", 100L, stats.bytesOut)
        assertEquals(8L, stats.packetsIn)
        assertEquals(2L, stats.packetsOut)
    }

    @Test
    fun transportCountersAreUsedWhenTunCountersAreAbsent() {
        val stats = NativeEngine.stats(
            """{"BYTES_IN":900,"BYTES_OUT":100,"PACKETS_IN":8,"PACKETS_OUT":2}""",
        )

        assertEquals(900L, stats.bytesIn)
        assertEquals(100L, stats.bytesOut)
        assertEquals(8L, stats.packetsIn)
        assertEquals(2L, stats.packetsOut)
    }

    @Test
    fun partiallyPopulatedTunCountersDoNotMixDirections() {
        val stats = NativeEngine.stats(
            """{"TUN_BYTES_IN":100,"TUN_BYTES_OUT":0,"BYTES_IN":900,"BYTES_OUT":1000}""",
        )

        assertEquals("download must stay on the TUN counter", 0L, stats.bytesIn)
        assertEquals("upload must stay on the TUN counter", 100L, stats.bytesOut)
    }
}
