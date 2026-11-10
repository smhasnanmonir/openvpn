package com.smhasnan.openvpn

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smhasnan.openvpn.config.ConfigImporter
import com.smhasnan.openvpn.config.ImportOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Uses a throwaway self-contained profile generated only for tests. It proves
 * the SAF-independent import pipeline (private temp copy -> Core merge/eval ->
 * encrypted store) works on-device.
 */
@RunWith(AndroidJUnit4::class)
class ProfileImportInstrumentedTest {
    @Test
    fun importsSelfContainedProfileAndPersistsIt() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val content = instrumentation.context.assets.open("test-profile.ovpn").bufferedReader().use { it.readText() }
        val file = File(context.cacheDir, "Lab Server.ovpn")
        file.writeText(content)
        try {
            val repository = context.appContainer.profiles
            val outcomes = ConfigImporter(context, repository).import(listOf(Uri.fromFile(file)))
            val success = outcomes.filterIsInstance<ImportOutcome.Success>().firstOrNull()
            assertNotNull("Import should succeed: $outcomes", success)
            val imported = success!!
            repository.refresh()
            assertTrue(repository.profiles.value.any { it.id == imported.profileId })
            repository.select(imported.profileId)
        } finally {
            file.delete()
        }
    }
}
