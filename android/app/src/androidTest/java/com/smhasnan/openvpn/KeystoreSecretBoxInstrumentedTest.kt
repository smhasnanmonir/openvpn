package com.smhasnan.openvpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smhasnan.openvpn.security.KeystoreSecretBox
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreSecretBoxInstrumentedTest {
    @Test
    fun encryptsAndDecryptsWithKeystoreKey() {
        val box = KeystoreSecretBox("instrumented_test_${System.nanoTime()}")
        val plaintext = "profile-content-and-secret".toByteArray()
        val ciphertext = box.encrypt(plaintext)

        assertFalse(plaintext.contentEquals(ciphertext))
        assertArrayEquals(plaintext, box.decrypt(ciphertext))
    }
}
