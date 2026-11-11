package com.smhasnan.openvpn.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * User-chosen TUN MTU. [AUTO] keeps the server-pushed value (the historical
 * behaviour); a fixed value overrides the pushed `tun-mtu` for networks whose
 * path MTU is smaller than the tunnel MTU.
 */
enum class MtuMode(val mtu: Int?) {
    AUTO(null),
    MTU1500(1500),
    MTU1400(1400),
    MTU1280(1280),
    ;

    companion object {
        fun fromMtu(mtu: Int): MtuMode = entries.firstOrNull { it.mtu == mtu } ?: AUTO
    }
}

/** Per-app routing policy applied when the TUN is created. */
enum class SplitTunnelMode {
    /** Every app uses the tunnel (default). */
    OFF,

    /** Only the selected apps use the tunnel; everything else bypasses it. */
    INCLUDE_SELECTED,

    /** The selected apps bypass the tunnel; everything else uses it. */
    EXCLUDE_SELECTED,
    ;

    val usesAppList: Boolean
        get() = this != OFF
}

/** Compression policy. Compatibility keeps the provider-compatible LZO-ASYM mode. */
enum class CompressionPolicy {
    /**
     * Accept server-to-client asymmetric compression (ADR-005). Server-pushed
     * compression is accepted, symmetric compression stays refused.
     */
    COMPATIBILITY,

    /**
     * Refuse any server-pushed compression. A server that requires compression
     * fails the connection with an explicit error instead of silently receiving
     * compressed traffic.
     */
    STRICT,
}

/** Data-channel cipher policy applied to the profile at connect time. */
enum class CipherPolicy {
    /** Use the cipher list/directives from the profile (historical behaviour). */
    PROFILE,

    /**
     * Accept only AEAD data ciphers (AES-GCM / ChaCha20-Poly1305). A profile that
     * pins a legacy cipher, or a server that pushes one, fails visibly instead of
     * negotiating a non-AEAD cipher.
     */
    AEAD_ONLY,
}

@Immutable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val biometricLock: Boolean = false,
    val blockUnusedFamilies: Boolean = true,
    val autoCheckIp: Boolean = true,
    /**
     * Fail closed when the server pushes no usable DNS resolver. While enabled a
     * profile without tunnel DNS (and without a configured fallback) cannot
     * connect, so Android can never silently fall back to the underlying
     * network's resolver.
     */
    val requireTunnelDns: Boolean = true,
    /**
     * Optional user-configured DNS servers used through the tunnel when the
     * server pushes none. Comma/space separated IPv4 or IPv6 addresses; parsed
     * and validated by the DNS leak policy.
     */
    val fallbackDnsServers: String = "",
    /** TUN MTU override; [MtuMode.AUTO] uses the pushed value. */
    val mtuMode: MtuMode = MtuMode.AUTO,
    /** Per-app routing policy applied the next time the TUN is created. */
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.OFF,
    /** Package names selected for [splitTunnelMode]. */
    val splitTunnelPackages: Set<String> = emptySet(),
    /** Compression policy; compatibility is the provider-compatible default. */
    val compressionPolicy: CompressionPolicy = CompressionPolicy.COMPATIBILITY,
    /** Data-channel cipher policy. */
    val cipherPolicy: CipherPolicy = CipherPolicy.PROFILE,
)

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _selectedProfileId = MutableStateFlow(prefs.getString(KEY_SELECTED_PROFILE, null))
    val selectedProfileId: StateFlow<String?> = _selectedProfileId.asStateFlow()

    private val _desiredConnectedProfileId =
        MutableStateFlow(prefs.getString(KEY_DESIRED_CONNECTED, null))
    /** Profile the user last asked to connect. Survives process death; drives the sticky-service restore. */
    val desiredConnectedProfileId: StateFlow<String?> = _desiredConnectedProfileId.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        prefs.edit()
            .putString(KEY_THEME, updated.themeMode.name)
            .putBoolean(KEY_BIOMETRIC, updated.biometricLock)
            .putBoolean(KEY_BLOCK_FAMILIES, updated.blockUnusedFamilies)
            .putBoolean(KEY_AUTO_IP, updated.autoCheckIp)
            .putBoolean(KEY_REQUIRE_TUNNEL_DNS, updated.requireTunnelDns)
            .putString(KEY_FALLBACK_DNS, updated.fallbackDnsServers)
            .putString(KEY_MTU_MODE, updated.mtuMode.name)
            .putString(KEY_SPLIT_TUNNEL_MODE, updated.splitTunnelMode.name)
            .putStringSet(KEY_SPLIT_TUNNEL_PACKAGES, updated.splitTunnelPackages.toSet())
            .putString(KEY_COMPRESSION_POLICY, updated.compressionPolicy.name)
            .putString(KEY_CIPHER_POLICY, updated.cipherPolicy.name)
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

    /**
     * Records (or clears) the user's connect intent. This is deliberately
     * separate from the native session: a process death must never restore a
     * pointer, only the intent to be connected.
     */
    fun setDesiredConnectedProfile(id: String?) {
        prefs.edit().apply {
            if (id == null) remove(KEY_DESIRED_CONNECTED) else putString(KEY_DESIRED_CONNECTED, id)
        }.apply()
        _desiredConnectedProfileId.value = id
    }

    fun desiredConnectedProfileIdNow(): String? = _desiredConnectedProfileId.value

    fun notificationPermissionRequested(): Boolean = prefs.getBoolean(KEY_NOTIFICATION_PROMPTED, false)

    fun markNotificationPermissionRequested() {
        prefs.edit().putBoolean(KEY_NOTIFICATION_PROMPTED, true).apply()
    }

    fun settingsNow(): AppSettings = _settings.value

    private fun readSettings(): AppSettings = AppSettings(
        themeMode = enumOrDefault(prefs.getString(KEY_THEME, null), ThemeMode.SYSTEM),
        biometricLock = prefs.getBoolean(KEY_BIOMETRIC, false),
        blockUnusedFamilies = prefs.getBoolean(KEY_BLOCK_FAMILIES, true),
        autoCheckIp = prefs.getBoolean(KEY_AUTO_IP, true),
        requireTunnelDns = prefs.getBoolean(KEY_REQUIRE_TUNNEL_DNS, true),
        fallbackDnsServers = prefs.getString(KEY_FALLBACK_DNS, "").orEmpty(),
        mtuMode = enumOrDefault(prefs.getString(KEY_MTU_MODE, null), MtuMode.AUTO),
        splitTunnelMode = enumOrDefault(
            prefs.getString(KEY_SPLIT_TUNNEL_MODE, null),
            SplitTunnelMode.OFF,
        ),
        splitTunnelPackages = prefs.getStringSet(KEY_SPLIT_TUNNEL_PACKAGES, emptySet())
            ?.toSet()
            .orEmpty(),
        compressionPolicy = enumOrDefault(
            prefs.getString(KEY_COMPRESSION_POLICY, null),
            CompressionPolicy.COMPATIBILITY,
        ),
        cipherPolicy = enumOrDefault(
            prefs.getString(KEY_CIPHER_POLICY, null),
            CipherPolicy.PROFILE,
        ),
    )

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, fallback: T): T =
        raw?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_BIOMETRIC = "biometric_lock"
        const val KEY_BLOCK_FAMILIES = "block_unused_families"
        const val KEY_AUTO_IP = "auto_check_ip"
        const val KEY_REQUIRE_TUNNEL_DNS = "require_tunnel_dns"
        const val KEY_FALLBACK_DNS = "fallback_dns_servers"
        const val KEY_MTU_MODE = "mtu_mode"
        const val KEY_SPLIT_TUNNEL_MODE = "split_tunnel_mode"
        const val KEY_SPLIT_TUNNEL_PACKAGES = "split_tunnel_packages"
        const val KEY_COMPRESSION_POLICY = "compression_policy"
        const val KEY_CIPHER_POLICY = "cipher_policy"
        const val KEY_SELECTED_PROFILE = "selected_profile"
        const val KEY_DESIRED_CONNECTED = "desired_connected_profile"
        const val KEY_NOTIFICATION_PROMPTED = "notification_permission_requested"
    }
}
