package com.smhasnan.openvpn.config

/**
 * Pure, testable file-name policy for SAF imports. OpenVPN profiles may
 * reference companion files by name, so companion names are preserved exactly
 * while path separators, control characters, and traversal names are rejected.
 */
object ImportFilePolicy {
    const val MAX_FILE_BYTES = 4L * 1024 * 1024
    const val MAX_NAME_LENGTH = 128

    fun isProfileFile(name: String): Boolean = name.endsWith(".ovpn", ignoreCase = true)

    fun safeCompanionName(raw: String): String? {
        if (raw.isBlank() || raw.length > MAX_NAME_LENGTH) return null
        if (raw == "." || raw == ".." || raw.contains('/') || raw.contains('\\')) return null
        if (raw.any { it.isISOControl() }) return null
        return raw
    }

    fun suggestedDisplayName(fileName: String): String {
        val stripped = fileName.substringBeforeLast('.', fileName).trim()
        return if (stripped.isBlank()) "Imported profile" else stripped.take(ProfileNameValidator.MAX_LENGTH)
    }
}
