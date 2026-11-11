package com.smhasnan.openvpn.vpn

/**
 * Pure fail-closed DNS policy (#8).
 *
 * Android resolves DNS through the TUN only when the tunnel advertises a
 * resolver and DNS is not otherwise reachable. If the server pushes no usable
 * resolver, the device silently falls back to the underlying network's resolver
 * (the ISP's on Wi-Fi/mobile data), which leaks every hostname the user looks
 * up. This policy decides, with no Android dependency, whether a connection may
 * proceed with the pushed resolvers, a user-configured fallback, or not at all.
 *
 * Everything here is deliberately literal-only: [normalizeServer] never performs
 * a DNS lookup, so importing or connecting cannot itself create a leak.
 */
sealed interface Decision {
    /**
     * The connection may use these resolvers through the tunnel.
     *
     * @param filteredServers resolvers that were dropped because the tunnel does
     *   not route their address family (for example an IPv6 fallback on an
     *   IPv4-only profile). They must not be installed on the TUN: Android would
     *   treat the other family as configured and unblock it, leaking traffic.
     */
    data class TunnelDns(
        val servers: List<String>,
        val usedFallback: Boolean,
        val filteredServers: List<String> = emptyList(),
    ) : Decision

    /** The connection must not proceed because it could leak DNS. */
    data class Refused(val reason: String) : Decision
}

/** User-facing explanation used when no tunnel DNS can be offered. */
private const val DNS_REFUSAL_MESSAGE =
    "The VPN server did not provide a usable DNS resolver and the \"Require tunnel DNS\" " +
        "setting is on. Add a fallback DNS server in Settings, or use a profile whose server " +
        "pushes DNS, then reconnect. The connection was stopped so DNS requests cannot leak " +
        "to the underlying network."

/**
 * Used when resolvers exist but all of them belong to an address family the
 * tunnel does not route. Installing one of those would make Android consider the
 * family configured and unblock it, which is the opposite of fail-closed.
 */
private const val DNS_FAMILY_REFUSAL_MESSAGE =
    "The VPN server or the fallback DNS setting only offered resolvers for an address family " +
        "this tunnel does not route (for example an IPv6 resolver on an IPv4-only connection). " +
        "Installing one of those would unblock that family and leak traffic. Add a resolver for " +
        "an address family the tunnel provides, or reconnect with a profile that routes it, and " +
        "the connection was stopped so DNS requests cannot leak to the underlying network."

object DnsLeakPolicy {
    /**
     * Returns the canonical form of [raw] when it is a usable unicast IPv4/IPv6
     * literal, or `null` otherwise.
     *
     * Private/link-routable ranges are allowed (split-horizon and CGNAT resolvers
     * are legitimate). Loopback, unspecified ("any"), multicast and link-local
     * addresses are rejected: they cannot be reached through a tunnel and some of
     * them (for example `127.0.0.1`) would make the client itself the resolver.
     */
    fun normalizeServer(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val ipv4 = parseIpv4(text)
        if (ipv4 != null) return if (isRoutableIpv4(ipv4)) formatIpv4(ipv4) else null
        val ipv6 = parseIpv6(text) ?: return null
        if (!isRoutableIpv6(ipv6)) return null
        return formatIpv6(ipv6)
    }

    /**
     * Parses a comma/whitespace separated fallback resolver list. Invalid entries
     * are dropped rather than failing the whole setting; the result is normalized
     * and de-duplicated while keeping the user's order.
     */
    fun parseFallback(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(FALLBACK_SEPARATORS)
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull { normalizeServer(it) }
            .distinct()
            .toList()
    }

    /**
     * Decides which DNS servers the tunnel may use.
     *
     * @param pushedServers resolvers pushed by the VPN server (raw text; entries
     *   may be invalid and are ignored).
     * @param requireTunnelDns the user's "Require tunnel DNS" leak-protection
     *   setting. When on, an empty result must refuse the connection instead of
     *   silently using the underlying network's resolver.
     * @param fallbackServers user-configured resolvers to use through the tunnel
     *   when the server pushes none (see [parseFallback]).
     * @param tunnelIpv4 whether the TUN is configured with an IPv4 address.
     * @param tunnelIpv6 whether the TUN is configured with an IPv6 address.
     *   Resolvers of a family the tunnel does not route are dropped (see
     *   [Decision.TunnelDns.filteredServers]); an Android TUN with a DNS server
     *   of a family and no address/route of it unblocks that family.
     */
    fun decide(
        pushedServers: List<String>,
        requireTunnelDns: Boolean,
        fallbackServers: List<String>,
        tunnelIpv4: Boolean = true,
        tunnelIpv6: Boolean = true,
    ): Decision {
        val pushedAll = pushedServers.asSequence()
            .mapNotNull { normalizeServer(it) }
            .distinct()
            .toList()
        val pushed = filterByTunnelFamilies(pushedAll, tunnelIpv4, tunnelIpv6)
        val pushedFiltered = pushedAll - pushed.toSet()
        if (pushed.isNotEmpty()) {
            return Decision.TunnelDns(pushed, usedFallback = false, filteredServers = pushedFiltered)
        }

        val fallbackAll = fallbackServers.asSequence()
            .mapNotNull { normalizeServer(it) }
            .distinct()
            .toList()
        val fallback = filterByTunnelFamilies(fallbackAll, tunnelIpv4, tunnelIpv6)
        if (fallback.isNotEmpty()) {
            return Decision.TunnelDns(
                fallback,
                usedFallback = true,
                filteredServers = pushedFiltered + (fallbackAll - fallback.toSet()),
            )
        }

        if (!requireTunnelDns) return Decision.TunnelDns(emptyList(), usedFallback = false)
        val refusedBecauseOfFamily = (pushedAll + fallbackAll).isNotEmpty()
        return Decision.Refused(
            if (refusedBecauseOfFamily) DNS_FAMILY_REFUSAL_MESSAGE else DNS_REFUSAL_MESSAGE,
        )
    }
}

private fun filterByTunnelFamilies(
    servers: List<String>,
    tunnelIpv4: Boolean,
    tunnelIpv6: Boolean,
): List<String> = servers.filter { server ->
    if (server.contains(':')) tunnelIpv6 else tunnelIpv4
}

private val FALLBACK_SEPARATORS = Regex("[,\\s]+")

private fun parseIpv4(text: String): ByteArray? {
    val parts = text.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    for (index in parts.indices) {
        val part = parts[index]
        if (part.isEmpty() || part.length > 3) return null
        // Reject octal/ambiguous forms such as 010.0.0.1: they are interpreted
        // differently by different resolvers and are a classic bypass trick.
        if (part.length > 1 && part[0] == '0') return null
        if (part.any { it !in '0'..'9' }) return null
        val value = part.toIntOrNull() ?: return null
        if (value !in 0..255) return null
        bytes[index] = value.toByte()
    }
    return bytes
}

/** True for addresses that can actually be reached as a DNS server. */
private fun isRoutableIpv4(bytes: ByteArray): Boolean {
    val first = bytes[0].toInt() and 0xFF
    val second = bytes[1].toInt() and 0xFF
    return when {
        first == 0 -> false // "this network" / unspecified
        first == 127 -> false // loopback
        first == 169 && second == 254 -> false // link-local
        first in 224..239 -> false // multicast
        first >= 240 -> false // reserved / broadcast
        else -> true
    }
}

private fun parseIpv6(text: String): ByteArray? {
    if (text.indexOf(':') < 0) return null
    if (!text.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
        return null
    }
    val firstDoubleColon = text.indexOf("::")
    val hasCompression = firstDoubleColon >= 0
    if (hasCompression && text.indexOf("::", firstDoubleColon + 1) >= 0) return null

    val headText = if (hasCompression) text.substring(0, firstDoubleColon) else text
    val tailText = if (hasCompression) text.substring(firstDoubleColon + 2) else ""
    val headGroups = expandGroups(splitGroups(headText) ?: return null, allowIpv4 = !hasCompression)
        ?: return null
    val tailGroups = expandGroups(splitGroups(tailText) ?: return null, allowIpv4 = hasCompression)
        ?: return null

    val explicitGroups = headGroups.size + tailGroups.size
    val groups: List<Int> = if (hasCompression) {
        // "::" must stand for at least one zero group.
        if (explicitGroups > 7) return null
        headGroups + List(8 - explicitGroups) { 0 } + tailGroups
    } else {
        if (explicitGroups != 8) return null
        headGroups + tailGroups
    }

    val bytes = ByteArray(16)
    for (index in 0 until 8) {
        val group = groups[index]
        bytes[index * 2] = (group ushr 8).toByte()
        bytes[index * 2 + 1] = (group and 0xFF).toByte()
    }
    return bytes
}

private fun splitGroups(text: String): List<String>? {
    if (text.isEmpty()) return emptyList()
    val segments = text.split(':')
    if (segments.any { it.isEmpty() }) return null
    return segments
}

/**
 * Expands colon-separated groups to 16-bit values. An embedded dotted-quad is
 * allowed only as the last segment of the whole address ([allowIpv4]).
 */
private fun expandGroups(segments: List<String>, allowIpv4: Boolean): List<Int>? {
    if (segments.isEmpty()) return emptyList()
    val groups = ArrayList<Int>(segments.size + 1)
    for (index in segments.indices) {
        val segment = segments[index]
        if (segment.isEmpty()) return null
        if (segment.contains('.')) {
            if (!allowIpv4 || index != segments.lastIndex) return null
            val ipv4 = parseIpv4(segment) ?: return null
            groups.add(((ipv4[0].toInt() and 0xFF) shl 8) or (ipv4[1].toInt() and 0xFF))
            groups.add(((ipv4[2].toInt() and 0xFF) shl 8) or (ipv4[3].toInt() and 0xFF))
        } else {
            if (segment.length > 4) return null
            if (!segment.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
            groups.add(segment.toInt(16))
        }
    }
    return groups
}

private fun isRoutableIpv6(bytes: ByteArray): Boolean {
    if (bytes.all { it == 0.toByte() }) return false // unspecified ::
    if ((0 until 15).all { bytes[it] == 0.toByte() } && bytes[15] == 1.toByte()) {
        return false // loopback ::1
    }
    val first = bytes[0].toInt() and 0xFF
    val second = bytes[1].toInt() and 0xFF
    if (first == 0xFF) return false // multicast ff00::/8
    if (first == 0xFE && (second and 0xC0) == 0x80) return false // link-local fe80::/10
    // IPv4-mapped ::ffff:0:0/96 inherits the embedded IPv4 reachability.
    if ((0 until 10).all { bytes[it] == 0.toByte() } &&
        (bytes[10].toInt() and 0xFF) == 0xFF &&
        (bytes[11].toInt() and 0xFF) == 0xFF
    ) {
        return isRoutableIpv4(byteArrayOf(bytes[12], bytes[13], bytes[14], bytes[15]))
    }
    return true
}

private fun formatIpv4(bytes: ByteArray): String =
    bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }

private fun formatIpv6(bytes: ByteArray): String {
    val groups = IntArray(8) { index ->
        ((bytes[index * 2].toInt() and 0xFF) shl 8) or (bytes[index * 2 + 1].toInt() and 0xFF)
    }
    var bestStart = -1
    var bestLength = 0
    var index = 0
    while (index < 8) {
        if (groups[index] != 0) {
            index++
            continue
        }
        var end = index
        while (end < 8 && groups[end] == 0) end++
        val runLength = end - index
        if (runLength > bestLength) {
            bestLength = runLength
            bestStart = index
        }
        index = end
    }
    // RFC 5952: only compress runs of two or more zero groups.
    if (bestLength < 2) return groups.joinToString(":") { it.toString(16) }
    val head = (0 until bestStart).joinToString(":") { groups[it].toString(16) }
    val tail = (bestStart + bestLength until 8).joinToString(":") { groups[it].toString(16) }
    return when {
        head.isEmpty() && tail.isEmpty() -> "::"
        head.isEmpty() -> "::$tail"
        tail.isEmpty() -> "$head::"
        else -> "$head::$tail"
    }
}
