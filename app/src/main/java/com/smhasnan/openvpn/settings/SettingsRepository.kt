package com.smhasnan.openvpn.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val biometricLock: Boolean = false,
    val blockUnusedFamilies: Boolean = true,
    val autoCheckIp: Boolean = true,
)

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _selectedProfileId = MutableStateFlow(prefs.getString(KEY_SELECTED_PROFILE, null))
    val selectedProfileId: StateFlow<String?> = _selectedProfileId.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        prefs.edit()
            .putString(KEY_THEME, updated.themeMode.name)
            .putBoolean(KEY_BIOMETRIC, updated.biometricLock)
            .putBoolean(KEY_BLOCK_FAMILIES, updated.blockUnusedFamilies)
            .putBoolean(KEY_AUTO_IP, updated.autoCheckIp)
            .apply()
        _settings.value = updated
    }

    fun setSelectedProfile(id: String?) {
        prefs.edit().apply {
            if (id == null) remove(KEY_SELECTED_PROFILE) else putString(KEY_SELECTED_PROFILE, id)
        }.apply()
        _selectedProfileId.value = id
    }

    fun selectedProfileIdNow(): String? = _selectedProfileId.value

    fun notificationPermissionRequested(): Boolean = prefs.getBoolean(KEY_NOTIFICATION_PROMPTED, false)

    fun markNotificationPermissionRequested() {
        prefs.edit().putBoolean(KEY_NOTIFICATION_PROMPTED, true).apply()
    }

    fun settingsNow(): AppSettings = _settings.value

    private fun readSettings(): AppSettings = AppSettings(
        themeMode = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name)!!)
        }.getOrDefault(ThemeMode.SYSTEM),
        biometricLock = prefs.getBoolean(KEY_BIOMETRIC, false),
        blockUnusedFamilies = prefs.getBoolean(KEY_BLOCK_FAMILIES, true),
        autoCheckIp = prefs.getBoolean(KEY_AUTO_IP, true),
    )

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_BIOMETRIC = "biometric_lock"
        const val KEY_BLOCK_FAMILIES = "block_unused_families"
        const val KEY_AUTO_IP = "auto_check_ip"
        const val KEY_SELECTED_PROFILE = "selected_profile"
        const val KEY_NOTIFICATION_PROMPTED = "notification_permission_requested"
    }
}
