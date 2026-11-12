package com.smhasnan.openvpn.util

/**
 * Defense-in-depth redaction for logs that originate in Kotlin. Native logs are
 * also redacted in C++ before crossing JNI.
 */
object LogSanitizer {
    // Must stay in sync with sanitize_log() in app/src/main/cpp/native_bridge.cpp
    // (audit 2026-11-12, finding 2: both lists change together).
    private val markers = listOf(
        "password",
        "passphrase",
        "private key",
        "begin certificate",
        "begin rsa private",
        "auth-token",
        "auth_token",
        "session_id",
        "session token",
        // Providers sometimes inline credentials directly after these directives,
        // and merge/eval errors quote profile text verbatim.
        "auth-user-pass",
        "proxy-user-pass",
        "proxy-password",
    )

    fun sanitize(line: String): String {
        val lower = line.lowercase()
        if (markers.any(lower::contains)) {
            return "[redacted: sensitive log line]"
        }
        return if (line.length > 2_048) line.take(2_048) + "...[truncated]" else line
    }
}
