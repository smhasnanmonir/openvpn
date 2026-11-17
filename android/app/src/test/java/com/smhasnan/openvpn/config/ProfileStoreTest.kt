package com.smhasnan.openvpn.config

import com.smhasnan.openvpn.security.SecretBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val secretBox = FakeSecretBox()

    @Test
    fun savesLoadsAndDeletesProfiles() {
        val store = ProfileStore(temporaryFolder.newFolder("profiles"), secretBox)
        val profile = sampleProfile("id-one", "Work")

        store.save(profile)
        val loaded = store.loadAll()

        assertTrue(loaded.errors.isEmpty())
        assertEquals(1, loaded.profiles.size)
        assertEquals("Work", loaded.profiles.first().summary.name)
        assertEquals("client.ovpn-content", loaded.profiles.first().config)

        store.delete("id-one")
        assertTrue(store.loadAll().profiles.isEmpty())
    }

    @Test
    fun corruptBlobIsReportedWithoutDroppingOtherProfiles() {
        val directory = temporaryFolder.newFolder("profiles-corrupt")
        val store = ProfileStore(directory, secretBox)
        store.save(sampleProfile("good", "Good"))
        directory.resolve("broken.profile").writeText("not ciphertext")

        val loaded = store.loadAll()

        assertEquals(1, loaded.profiles.size)
        assertEquals(1, loaded.errors.size)
    }

    @Test
    fun credentialsNeverPersistChallengeResponse() {
        val directory = temporaryFolder.newFolder("credentials")
        val store = CredentialStore(directory, secretBox)
        store.save(
            "id",
            ProfileCredentials(
                username = "user",
                password = "secret",
                privateKeyPassword = "keypass",
                response = "one-time-code",
            ),
        )

        val loaded = store.load("id")

        assertNotNull(loaded)
        assertEquals("user", loaded!!.username)
        assertEquals("secret", loaded.password)
        assertEquals("keypass", loaded.privateKeyPassword)
        assertNull(loaded.response)

        store.delete("id")
        assertNull(store.load("id"))
    }

    @Test
    fun emptyCredentialsDeleteOnSaveThroughRepositoryContract() {
        val directory = temporaryFolder.newFolder("credentials-empty")
        val store = CredentialStore(directory, secretBox)
        store.save("id", ProfileCredentials())
        assertFalse(store.load("id")?.isEmpty == false)
    }

    private fun sampleProfile(id: String, name: String) = StoredProfile(
        summary = ProfileSummary(
            id = id,
            name = name,
            emoji = "🛡️",
            metadata = ProfileMetadata(
                serverHost = "vpn.example.com",
                serverPort = "1194",
                protocol = "udp",
                autologin = false,
            ),
            createdAt = 1L,
            updatedAt = 2L,
            contentHash = "abc",
        ),
        config = "client.ovpn-content",
    )

    private class FakeSecretBox : SecretBox {
        override fun encrypt(plaintext: ByteArray): ByteArray = byteArrayOf(7) + plaintext
        override fun decrypt(payload: ByteArray): ByteArray {
            require(payload.firstOrNull() == 7.toByte()) { "bad envelope" }
            return payload.copyOfRange(1, payload.size)
        }
    }
}
