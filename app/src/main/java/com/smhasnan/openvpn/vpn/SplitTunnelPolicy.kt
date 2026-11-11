package com.smhasnan.openvpn.vpn

import com.smhasnan.openvpn.settings.SplitTunnelMode

/**
 * Pure per-app split-tunnel policy (#17).
 *
 * Split tunnelling is a leak source: users often believe "the VPN is on" means
 * every app is protected, while in include mode every other app uses the
 * underlying network and in exclude mode the selected apps do. This policy
 * computes the concrete package list for `VpnService.Builder`, refuses policies
 * that would protect nothing, and produces explicit warning text the UI must
 * show whenever apps bypass the tunnel.
 */
data class Plan(
    val mode: SplitTunnelMode,
    val packages: List<String>,
) {
    /** True when the TUN should be built with an app allow/deny list. */
    val isActive: Boolean
        get() = mode.usesAppList && packages.isNotEmpty()
}

sealed interface Validation {
    data object Ok : Validation
    data class Rejected(val reason: String) : Validation
}

object SplitTunnelPolicy {
    /** Requested packages that are currently installed, sorted. */
    fun effectivePackages(requested: Set<String>, installed: Set<String>): List<String> =
        requested.filter { it in installed }.sorted()

    /** Requested packages that are no longer installed, sorted (shown in the UI). */
    fun uninstalled(requested: Set<String>, installed: Set<String>): List<String> =
        requested.filterNot { it in installed }.sorted()

    fun validate(mode: SplitTunnelMode, installed: Set<String>, requested: Set<String>): Validation {
        if (!mode.usesAppList) return Validation.Ok
        return when (mode) {
            SplitTunnelMode.OFF -> Validation.Ok
            SplitTunnelMode.INCLUDE_SELECTED -> {
                if (effectivePackages(requested, installed).isEmpty()) {
                    Validation.Rejected(
                        "Select at least one installed app to use the VPN. None of the selected " +
                            "apps is installed, so enabling this mode would send all traffic " +
                            "outside the tunnel.",
                    )
                } else {
                    Validation.Ok
                }
            }
            SplitTunnelMode.EXCLUDE_SELECTED -> {
                val appsInsideTunnel = installed.filterNot { it in requested }
                if (appsInsideTunnel.isEmpty()) {
                    Validation.Rejected(
                        "Excluding every installed app would leave no app inside the VPN. Select " +
                            "fewer apps, or switch to \"All apps use the VPN\".",
                    )
                } else {
                    Validation.Ok
                }
            }
        }
    }

    /**
     * Explicit leak warning for the active policy, or `null` when every app uses
     * the tunnel. [effectiveCount] is the number of selected apps that will
     * actually be applied (the size of [Plan.packages]).
     */
    fun warning(mode: SplitTunnelMode, effectiveCount: Int): String? = when (mode) {
        SplitTunnelMode.OFF -> null
        SplitTunnelMode.INCLUDE_SELECTED ->
            "Only ${appCount(effectiveCount)} use the VPN. Traffic from every other app " +
                "bypasses the tunnel and can expose your IP address."
        SplitTunnelMode.EXCLUDE_SELECTED ->
            "${appCount(effectiveCount)} bypass the VPN. Their traffic is not protected and can " +
                "expose your IP address."
    }
}

private fun appCount(count: Int): String =
    if (count == 1) "1 selected app" else "$count selected apps"
