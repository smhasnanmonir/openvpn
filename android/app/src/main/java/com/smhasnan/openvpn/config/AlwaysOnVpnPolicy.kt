package com.smhasnan.openvpn.config

/**
 * Pure Android Always-on VPN policy (#9).
 *
 * Only Always-on VPN combined with "Block connections without VPN" (lockdown) is
 * a system-level kill switch. Always-on without lockdown reconnects the VPN but
 * lets traffic use the underlying network while it is down, so the UI must not
 * call it a kill switch. Android does not expose another app's Always-on
 * configuration; the caller passes whatever the platform could read.
 */
enum class SystemVpnState {
    /** The Always-on configuration could not be read. */
    UNKNOWN,

    /** Always-on VPN is not configured for any app. */
    OFF,

    /** This app is the Always-on VPN, but lockdown is off (or unknown). */
    ALWAYS_ON,

    /** This app is the Always-on VPN and "Block connections without VPN" is on. */
    ALWAYS_ON_LOCKDOWN,

    /** Another app is the Always-on VPN. */
    OTHER_APP,
}

object AlwaysOnVpnPolicy {
    /**
     * @param appPackage this app's package name.
     * @param alwaysOnPackage package configured as Always-on VPN, or `null` when
     *   the platform could not report it.
     * @param lockdownEnabled "Block connections without VPN" state, or `null`
     *   when unknown.
     */
    fun classify(
        appPackage: String,
        alwaysOnPackage: String?,
        lockdownEnabled: Boolean?,
    ): SystemVpnState {
        if (alwaysOnPackage == null) return SystemVpnState.UNKNOWN
        val configured = alwaysOnPackage.trim()
        return when {
            configured.isEmpty() -> SystemVpnState.OFF
            configured == appPackage ->
                if (lockdownEnabled == true) {
                    SystemVpnState.ALWAYS_ON_LOCKDOWN
                } else {
                    // Lockdown false and unknown both mean "not a guaranteed kill switch".
                    SystemVpnState.ALWAYS_ON
                }
            else -> SystemVpnState.OTHER_APP
        }
    }

    /**
     * Honest, user-facing explanation. A kill switch is only claimed for
     * [SystemVpnState.ALWAYS_ON_LOCKDOWN]; every other state either says
     * protection is missing or explicitly warns that the system block is not
     * guaranteed.
     */
    fun explanation(state: SystemVpnState): String = when (state) {
        SystemVpnState.UNKNOWN ->
            "The system Always-on VPN setting could not be read. Open Android's VPN settings " +
                "to check whether Always-on VPN and \"Block connections without VPN\" are " +
                "enabled for this app."
        SystemVpnState.OFF ->
            "Always-on VPN is off. If the VPN drops, traffic can use the underlying network " +
                "until it reconnects. Enable Always-on VPN and \"Block connections without " +
                "VPN\" in Android's VPN settings for system-level protection."
        SystemVpnState.ALWAYS_ON ->
            "Always-on VPN is on, but \"Block connections without VPN\" (lockdown) is off or " +
                "unknown. Android reconnects the VPN but does not block traffic while it is " +
                "down, so this is not a guaranteed kill switch."
        SystemVpnState.ALWAYS_ON_LOCKDOWN ->
            "Always-on VPN and \"Block connections without VPN\" are both on. Android blocks " +
                "traffic that would bypass the VPN while it is down; this is the system-level " +
                "kill switch."
        SystemVpnState.OTHER_APP ->
            "Another app is configured as the Always-on VPN. This app cannot provide Always-on " +
                "protection until it is selected in Android's VPN settings."
    }
}
