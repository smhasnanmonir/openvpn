package com.smhasnan.openvpn

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smhasnan.openvpn.config.ConfigImporter
import com.smhasnan.openvpn.config.ImportOutcome
import com.smhasnan.openvpn.config.ProfileImportPolicy
import com.smhasnan.openvpn.nativevpn.NativeEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Verifies the real-world NCVPN username/password-only profile end to end on a
 * device, against the actual OpenVPN 3 Core 3.9 native library.
 *
 * `assets/ncvpn-userpass.ovpn` is a byte-for-byte copy of
 * `NCVPN-SG-Singapore-UDP.ovpn` from the provider export used for the bug
 * report. It contains a public inline CA and a `remote ... 1194` server list but
 * no private key and no credentials, so it is safe to keep in the test APK.
 */
@RunWith(AndroidJUnit4::class)
class UserPasswordProfileInstrumentedTest {
    private fun profileAsset(): String =
        InstrumentationRegistry.getInstrumentation()
            .context.assets.open("ncvpn-userpass.ovpn")
            .bufferedReader()
            .use { it.readText() }

    private fun clientCertDirectiveCount(config: String): Int =
        Regex("(?m)^setenv CLIENT_CERT 0$").findAll(config).count()

    @Test
    fun coreReportsCertificateLessProfileAsExternalPki() {
        val original = profileAsset()
        assertTrue("profile should declare auth-user-pass", original.contains("auth-user-pass"))
        val eval = NativeEngine.evalConfig(original)
        assertFalse("Core rejected the valid profile: ${eval.message}", eval.error)
        assertTrue(
            "Core 3.9 should report no-cert/key profiles as externalPki",
            eval.metadata.externalPki,
        )
        assertFalse("username/password profile must not be autologin", eval.metadata.autologin)
    }

    @Test
    fun policyRecognizesUserPasswordProfile() {
        val original = profileAsset()
        assertFalse(ProfileImportPolicy.declaresExternalPki(original))
        assertTrue(ProfileImportPolicy.declaresUserPasswordAuth(original))

        val adapted = ProfileImportPolicy.withoutClientCertificate(original)
        assertNotEquals(original, adapted)
        assertEquals(1, clientCertDirectiveCount(adapted))
        assertTrue(
            "original directives must be preserved verbatim",
            adapted.startsWith(original.trimEnd('\n', '\r')),
        )
        assertEquals(
            "adaptation must be idempotent",
            adapted,
            ProfileImportPolicy.withoutClientCertificate(adapted),
        )
    }

    @Test
    fun adaptedProfileIsNoLongerExternalPki() {
        val adapted = ProfileImportPolicy.withoutClientCertificate(profileAsset())
        val eval = NativeEngine.evalConfig(adapted)
        assertFalse("Core rejected the adapted profile: ${eval.message}", eval.error)
        assertFalse(
            "setenv CLIENT_CERT 0 must clear externalPki in Core 3.9",
            eval.metadata.externalPki,
        )
    }

    @Test
    fun importerAcceptsUserPasswordProfileAndStoresAdaptedContent() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "NCVPN-SG-Singapore-UDP.ovpn")
        file.writeText(profileAsset())
        val repository = context.appContainer.profiles
        var importedId: String? = null
        try {
            val outcome = ConfigImporter(context, repository)
                .import(listOf(Uri.fromFile(file)))
                .filterIsInstance<ImportOutcome.Success>()
                .firstOrNull()
            assertNotNull("Import should succeed: $outcome", outcome)
            val success = outcome!!
            importedId = success.profileId

            repository.refresh()
            val stored = repository.get(success.profileId)
            assertNotNull("Stored profile should be readable", stored)
            stored!!

            assertTrue(
                "metadata should record that the app added setenv CLIENT_CERT 0",
                stored.summary.metadata.clientCertNotRequired,
            )
            assertFalse(
                "stored metadata must not claim external PKI",
                stored.summary.metadata.externalPki,
            )
            assertEquals(1, clientCertDirectiveCount(stored.config))
            assertTrue(stored.config.contains("auth-user-pass"))

            // Re-importing the same source must dedupe on the rewritten content.
            val second = ConfigImporter(context, repository)
                .import(listOf(Uri.fromFile(file)))
                .filterIsInstance<ImportOutcome.Success>()
                .firstOrNull()
            assertNotNull("Second import should succeed: $second", second)
            assertTrue("Re-import should dedupe", second!!.alreadyImported)
            assertEquals(success.profileId, second.profileId)
        } finally {
            importedId?.let { id ->
                runCatching { repository.delete(id) }
            }
            repository.refresh()
            file.delete()
        }
    }
}
