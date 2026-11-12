package com.smhasnan.openvpn

import android.content.Context
import com.smhasnan.openvpn.config.ConfigImporter
import com.smhasnan.openvpn.config.CredentialStore
import com.smhasnan.openvpn.config.ProfileRepository
import com.smhasnan.openvpn.config.ProfileStore
import com.smhasnan.openvpn.net.PublicIpRepository
import com.smhasnan.openvpn.security.KeystoreSecretBox
import com.smhasnan.openvpn.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Sensitive setting values (split-tunnel apps, fallback DNS) share the
    // same Keystore-protected at-rest story as profiles and credentials.
    val settings = SettingsRepository(appContext, KeystoreSecretBox("settings_store_v1"))

    private val profileDirectory = File(appContext.noBackupFilesDir, "profiles")
    private val credentialDirectory = File(appContext.noBackupFilesDir, "credentials")

    val profiles = ProfileRepository(
        store = ProfileStore(profileDirectory, KeystoreSecretBox("profile_store_v1")),
        credentialStore = CredentialStore(credentialDirectory, KeystoreSecretBox("credential_store_v1")),
        settings = settings,
    )

    val importer = ConfigImporter(appContext, profiles)
    val publicIp = PublicIpRepository()

    init {
        scope.launch { profiles.refresh() }
    }
}
