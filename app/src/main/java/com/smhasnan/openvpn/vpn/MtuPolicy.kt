package com.smhasnan.openvpn.vpn

import com.smhasnan.openvpn.settings.MtuMode

/**
 * Pure TUN MTU override policy (#10).
 *
 * A server-pushed MTU of 1500 can exceed the real path MTU on some Wi-Fi,
 * cellular or IPv6 paths; large UDP packets are then dropped silently ("path MTU
 * black hole") and downloads stall even though the tunnel is up. The user can
 * pick a smaller MTU, which is applied to the profile text before it is handed
 * to OpenVPN Core:
 *
 * - `pull-filter ignore tun-mtu` stops the server from overriding the local
 *   value when the server pushes its own `tun-mtu`;
 * - `tun-mtu <n>` is the local override, appended last so it wins during option
 *   parsing.
 *
 * Both functions are pure and idempotent so a reconnect can re-apply them
 * safely.
 */
object MtuPolicy {
    fun withTunMtuOverride(config: String, mode: MtuMode): String {
        val mtu = mode.mtu ?: return config
        val trailingNewline = trailingNewlineOf(config)
        val body = config.trimEnd('\n', '\r')
        val existingLines = body.lineSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        val additions = buildList {
            if (IGNORE_PUSHED_TUN_MTU !in existingLines) add(IGNORE_PUSHED_TUN_MTU)
            val mtuLine = "tun-mtu $mtu"
            if (mtuLine !in existingLines) add(mtuLine)
        }
        if (additions.isEmpty()) return config
        val merged = if (body.isEmpty()) {
            additions.joinToString("\n")
        } else {
            body + "\n" + additions.joinToString("\n")
        }
        return if (trailingNewline == null) merged else merged + trailingNewline
    }

    /** The MTU that will be used for [mode], or `null` when the pushed value wins. */
    fun effectiveMtu(mode: MtuMode): Int? = mode.mtu

    /** Human-readable description for the settings/diagnostics UI. */
    fun describe(mode: MtuMode): String = when (mode) {
        MtuMode.AUTO ->
            "Automatic: use the MTU pushed by the VPN server (normally 1500 bytes)."
        else ->
            "Override the server-pushed MTU with ${mode.mtu} bytes. Use a lower value " +
                "when large downloads stall on networks with a smaller path MTU."
    }
}

private const val IGNORE_PUSHED_TUN_MTU = "pull-filter ignore tun-mtu"

private fun trailingNewlineOf(config: String): String? = when {
    config.endsWith("\r\n") -> "\r\n"
    config.endsWith("\n") -> "\n"
    config.endsWith("\r") -> "\r"
    else -> null
}
