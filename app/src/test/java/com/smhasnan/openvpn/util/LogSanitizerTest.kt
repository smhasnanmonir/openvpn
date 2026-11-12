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
    fun redactsInlineAuthUserPassCredentials() {
        // merge/eval failures quote profile text verbatim; "pass" alone is not
        // a marker, so the directive itself must be one.
        assertEquals("[redacted: sensitive log line]", LogSanitizer.sanitize("auth-user-pass alice secret123"))
    }

    @Test
    fun redactsInlineProxyCredentials() {
        assertEquals("[redacted: sensitive log line]", LogSanitizer.sanitize("http-proxy-user-pass bob hunter2"))
        assertEquals("[redacted: sensitive log line]", LogSanitizer.sanitize("Options error: proxy-password set"))
    }

    @Test
    fun truncatesVeryLongLines() {
        val sanitized = LogSanitizer.sanitize("x".repeat(3_000))
        assertTrue(sanitized.length < 3_000)
        assertTrue(sanitized.endsWith("[truncated]"))
    }
}
