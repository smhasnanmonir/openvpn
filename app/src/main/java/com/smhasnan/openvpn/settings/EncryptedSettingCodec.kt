package com.smhasnan.openvpn.settings

import com.smhasnan.openvpn.security.SecretBox
import java.util.Base64

/**
 * Encodes privacy-sensitive setting values (split-tunnel package names,
 * fallback resolvers) as Keystore-encrypted, Base64-wrapped blobs so they do
 * not sit in plaintext SharedPreferences (audit 2026-11-12, finding 4).
 * Decode is fail-soft: corrupt or undecryptable values return null and the
 * caller falls back to the empty default.
 */
object EncryptedSettingCodec {
    fun encode(secretBox: SecretBox, value: String): String =
        Base64.getEncoder().encodeToString(secretBox.encrypt(value.toByteArray(Charsets.UTF_8)))

    fun decode(secretBox: SecretBox, stored: String?): String? {
        if (stored.isNullOrBlank()) return null
        return runCatching {
            secretBox.decrypt(Base64.getDecoder().decode(stored)).toString(Charsets.UTF_8)
        }.getOrNull()
    }
}
