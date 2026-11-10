package com.smhasnan.openvpn.security

/** Abstraction so storage logic can be tested without Android Keystore. */
interface SecretBox {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(payload: ByteArray): ByteArray
}
