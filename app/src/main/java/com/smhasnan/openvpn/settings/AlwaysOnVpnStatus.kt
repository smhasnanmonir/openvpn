package com.smhasnan.openvpn.settings

import android.content.Context
import android.provider.Settings

/**
 * Best-effort reader for Android's hidden system "always-on VPN" settings.
 *
 * There is no public Android API that reports whether this app is configured as
 * the always-on VPN or whether lockdown ("Block connections without VPN") is
 * enabled. The platform stores both values in [Settings.Secure] under keys that
 * are not part of the public SDK surface, so this reader is deliberately
 * defensive:
 *
 *  - every read is wrapped in [runCatching] and a failed read returns `null`
 *    instead of throwing;
 *  - `always_on_vpn_app` is `null`/blank when no app is configured, and
 *    `always_on_vpn_lockdown` is an `Int` (`0`/`1`) on stock Android but may be
 *    absent (or stored differently) on OEM builds — `null` means "unknown";
 *  - callers must treat unknown values as unknown and must never turn them into
 *    a promise of protection.
 *
 * This class only detects configuration. The app navigates the user to the
 * system VPN screen; it cannot enable, verify, or guarantee a kill switch. Even
 * with lockdown enabled, the system owns enforcement, so UI text must not claim
 * a guaranteed kill switch in any state.
 */
object AlwaysOnVpnStatus {
    /** Raw platform values. `null` means the value could not be read. */
    data class Reading(
        val alwaysOnPackage: String?,
        val lockdownEnabled: Boolean?,
    )

    fun read(context: Context): Reading {
        val resolver = context.applicationContext.contentResolver
        val alwaysOnPackage = runCatching {
            Settings.Secure.getString(resolver, SETTING_ALWAYS_ON_VPN_APP)
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val lockdownEnabled = runCatching {
            // Default to 0 only for a successful read: an exception, not a
            // missing row, is what returns null below.
            Settings.Secure.getInt(resolver, SETTING_ALWAYS_ON_VPN_LOCKDOWN, 0) != 0
        }.getOrNull()
        return Reading(alwaysOnPackage = alwaysOnPackage, lockdownEnabled = lockdownEnabled)
    }

    /**
     * Hidden platform setting: package name of the configured always-on VPN.
     * Not public API; may be missing or unreadable on some OEM builds.
     */
    private const val SETTING_ALWAYS_ON_VPN_APP = "always_on_vpn_app"

    /**
     * Hidden platform setting: whether lockdown ("Block connections without
     * VPN") is enabled for the always-on VPN.
     */
    private const val SETTING_ALWAYS_ON_VPN_LOCKDOWN = "always_on_vpn_lockdown"
}
