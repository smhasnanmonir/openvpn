package com.smhasnan.openvpn.settings

import com.smhasnan.openvpn.security.SecretBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EncryptedSettingCodecTest {
    /** XOR-with-a-fixed-byte "cipher": deterministic, enough to prove the bytes pass through the box. */
    private val fakeBox = object : SecretBox {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

        override fun decrypt(payload: ByteArray): ByteArray {
            require(payload.isNotEmpty()) { "corrupt" }
            return payload.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    @Test
    fun roundTripsSensitiveValues() {
        val stored = EncryptedSettingCodec.encode(fakeBox, "1.1.1.1,2606:4700:4700::1111")
        assertEquals("1.1.1.1,2606:4700:4700::1111", EncryptedSettingCodec.decode(fakeBox, stored))
    }

    @Test
    fun encodedValueIsNotPlaintext() {
        val stored = EncryptedSettingCodec.encode(fakeBox, "com.example.app")
        assert(!stored.contains("com.example.app"))
    }

    @Test
    fun decodeIsFailSoftOnCorruptInput() {
        assertNull(EncryptedSettingCodec.decode(fakeBox, "not-base64-!!!"))
        assertNull(EncryptedSettingCodec.decode(fakeBox, null))
        assertNull(EncryptedSettingCodec.decode(fakeBox, " "))
    }
}
