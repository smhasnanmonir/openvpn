package com.smhasnan.openvpn.config

import com.smhasnan.openvpn.security.SecretBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Covers [ProfileStore.load] (single-profile read path) so the repository can
 * decrypt one profile body on demand instead of keeping every config resident.
 */
class ProfileStoreLoadTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val decrypts = AtomicInteger(0)
    private val secretBox = CountingSecretBox(decrypts)

    @Test
    fun loadsOneProfileByIdWithoutDecryptingTheRest() {
        val store = ProfileStore(temporaryFolder.newFolder("profiles-load"), secretBox)
        store.save(profile("one", "One"))
        store.save(profile("two", "Two"))
        decrypts.set(0)

        val loaded = store.load("one")

        assertNotNull(loaded)
        assertEquals("one", loaded!!.summary.id)
        assertEquals("One", loaded.summary.name)
        assertEquals("client.ovpn-content", loaded.config)
        assertEquals(
            "load(id) must decrypt exactly the requested blob",
            1,
            decrypts.get(),
        )
    }

    @Test
    fun returnsNullWhenTheIdIsAbsent() {
        val store = ProfileStore(temporaryFolder.newFolder("profiles-missing"), secretBox)
        store.save(profile("one", "One"))
        decrypts.set(0)

        assertNull(store.load("missing"))
        assertEquals(0, decrypts.get())
    }

    @Test
    fun returnsNullForACorruptBlob() {
        val directory = temporaryFolder.newFolder("profiles-corrupt-load")
        val store = ProfileStore(directory, secretBox)
        directory.resolve("broken.profile").writeText("not ciphertext")

        assertNull(store.load("broken"))
    }

    @Test
    fun returnsNullWhenThePayloadBelongsToAnotherId() {
        val directory = temporaryFolder.newFolder("profiles-mismatch")
        val store = ProfileStore(directory, secretBox)
        store.save(profile("alpha", "Alpha"))
        directory.resolve("beta.profile").writeBytes(directory.resolve("alpha.profile").readBytes())

        assertNull(store.load("beta"))
    }

    @Test
    fun loadingOneProfileLeavesTheOthersReadable() {
        val store = ProfileStore(temporaryFolder.newFolder("profiles-independent"), secretBox)
        store.save(profile("one", "One"))
        store.save(profile("two", "Two"))

        assertEquals("One", store.load("one")?.summary?.name)
        assertEquals("Two", store.load("two")?.summary?.name)

        val all = store.loadAll()
        assertTrue(all.errors.isEmpty())
        assertEquals(2, all.profiles.size)
    }

    private fun profile(id: String, name: String) = StoredProfile(
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
            contentHash = "hash-$id",
        ),
        config = "client.ovpn-content",
    )

    private class CountingSecretBox(
        private val decrypts: AtomicInteger,
    ) : SecretBox {
        override fun encrypt(plaintext: ByteArray): ByteArray = byteArrayOf(ENVELOPE) + plaintext

        override fun decrypt(payload: ByteArray): ByteArray {
            decrypts.incrementAndGet()
            require(payload.firstOrNull() == ENVELOPE) { "bad envelope" }
            return payload.copyOfRange(1, payload.size)
        }

        private companion object {
            const val ENVELOPE = 7.toByte()
        }
    }
}
