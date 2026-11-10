package com.smhasnan.openvpn.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {
    @Test
    fun formatsBytes() {
        assertEquals("0 B", ByteFormat.format(0))
        assertEquals("512 B", ByteFormat.format(512))
        assertEquals("1.0 KB", ByteFormat.format(1024))
        assertEquals("1.5 MB", ByteFormat.format(1_572_864))
    }

    @Test
    fun formatsDurations() {
        assertEquals("00:00", DurationFormat.format(0))
        assertEquals("01:05", DurationFormat.format(65_000))
        assertEquals("1:01:01", DurationFormat.format(3_661_000))
    }
}
