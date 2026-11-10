package com.smhasnan.openvpn.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSanitizerTest {
    @Test
    fun redactsLinesWithPasswords() {
        assertEquals("[redacted: sensitive log line]", LogSanitizer.sanitize("password=swordfish"))
    }

    @Test
    fun redactsPrivateKeyMaterial() {
        assertTrue(LogSanitizer.sanitize("-----BEGIN RSA PRIVATE KEY-----").startsWith("[redacted"))
    }

    @Test
    fun keepsNormalEngineLines() {
        assertEquals("EVENT: CONNECTED", LogSanitizer.sanitize("EVENT: CONNECTED"))
    }

    @Test
    fun truncatesVeryLongLines() {
        val sanitized = LogSanitizer.sanitize("x".repeat(3_000))
        assertTrue(sanitized.length < 3_000)
        assertTrue(sanitized.endsWith("[truncated]"))
    }
}
