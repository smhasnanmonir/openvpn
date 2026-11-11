package com.smhasnan.openvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsLeakPolicyTest {
    @Test
    fun acceptsPublicAndPrivateIpv4Resolvers() {
        assertEquals("1.1.1.1", DnsLeakPolicy.normalizeServer("1.1.1.1"))
        assertEquals("8.8.8.8", DnsLeakPolicy.normalizeServer("8.8.8.8"))
        assertEquals("10.0.0.53", DnsLeakPolicy.normalizeServer("10.0.0.53"))
        assertEquals("172.16.0.1", DnsLeakPolicy.normalizeServer("172.16.0.1"))
        assertEquals("192.168.1.1", DnsLeakPolicy.normalizeServer("192.168.1.1"))
        assertEquals("100.64.0.1", DnsLeakPolicy.normalizeServer("100.64.0.1"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals("1.1.1.1", DnsLeakPolicy.normalizeServer("  1.1.1.1\t"))
        assertEquals("2001:db8::1", DnsLeakPolicy.normalizeServer("\n2001:db8::1 "))
    }

    @Test
    fun rejectsInvalidAndNonLiteralInput() {
        listOf(
            "",
            "   ",
            "example.com",
            "localhost",
            "1.2.3",
            "1.2.3.4.5",
            "1.2.3.256",
            "01.2.3.4",
            "-1.2.3.4",
            "1.2.3.4:53",
            "[1.1.1.1]",
            "::1",
            "1.2.3.4:",
        ).forEach { raw ->
            assertNull("should reject \"$raw\"", DnsLeakPolicy.normalizeServer(raw))
        }
    }

    @Test
    fun rejectsIpv4AddressesThatCannotBeTunnelResolvers() {
        assertNull(DnsLeakPolicy.normalizeServer("0.0.0.0"))
        assertNull(DnsLeakPolicy.normalizeServer("0.1.2.3"))
        assertNull(DnsLeakPolicy.normalizeServer("127.0.0.1"))
        assertNull(DnsLeakPolicy.normalizeServer("127.255.255.254"))
        assertNull(DnsLeakPolicy.normalizeServer("169.254.1.1"))
        assertNull(DnsLeakPolicy.normalizeServer("224.0.0.1"))
        assertNull(DnsLeakPolicy.normalizeServer("239.255.255.250"))
        assertNull(DnsLeakPolicy.normalizeServer("255.255.255.255"))
        assertNull(DnsLeakPolicy.normalizeServer("240.0.0.1"))
    }

    @Test
    fun acceptsAndCanonicalizesIpv6Resolvers() {
        assertEquals("2001:db8::1", DnsLeakPolicy.normalizeServer("2001:0db8:0000:0000:0000:0000:0000:0001"))
        assertEquals("2001:db8::1", DnsLeakPolicy.normalizeServer("2001:DB8::1"))
        assertEquals("2001:db8::", DnsLeakPolicy.normalizeServer("2001:db8::"))
        assertEquals("fc00::1", DnsLeakPolicy.normalizeServer("fc00::1"))
        assertEquals("::1:2:3", DnsLeakPolicy.normalizeServer("0:0:0:0:0:1:2:3"))
        assertEquals("2001:4860:4860::8888", DnsLeakPolicy.normalizeServer("2001:4860:4860:0000:0000:0000:0000:8888"))
    }

    @Test
    fun acceptsIpv4MappedIpv6() {
        assertEquals("::ffff:c0a8:101", DnsLeakPolicy.normalizeServer("::ffff:192.168.1.1"))
    }

    @Test
    fun acceptsIpv6WithEmbeddedIpv4Tail() {
        assertEquals("64:ff9b::102:304", DnsLeakPolicy.normalizeServer("64:ff9b::1.2.3.4"))
        assertNull(DnsLeakPolicy.normalizeServer("::ffff:127.0.0.1"))
        assertNull(DnsLeakPolicy.normalizeServer("::ffff:169.254.1.1"))
    }

    @Test
    fun rejectsIpv6AddressesThatCannotBeTunnelResolvers() {
        listOf(
            "::",
            "0:0:0:0:0:0:0:0",
            "::1",
            "0:0:0:0:0:0:0:1",
            "fe80::1",
            "febf::1",
            "ff02::1",
            "ff00::",
        ).forEach { raw ->
            assertNull("should reject \"$raw\"", DnsLeakPolicy.normalizeServer(raw))
        }
    }

    @Test
    fun rejectsMalformedIpv6Syntax() {
        listOf(
            "1::2::3",
            ":::",
            "12345::1",
            "gggg::1",
            "1:2:3:4:5:6:7",
            "1:2:3:4:5:6:7:8:9",
            "1:2:3:4:5:6:7::8",
            "fe80::1%wlan0",
            "::ffff:1.2.3",
        ).forEach { raw ->
            assertNull("should reject \"$raw\"", DnsLeakPolicy.normalizeServer(raw))
        }
    }

    @Test
    fun parsesCommaAndWhitespaceSeparatedFallbackLists() {
        assertEquals(
            listOf("1.1.1.1", "8.8.8.8"),
            DnsLeakPolicy.parseFallback("1.1.1.1, 8.8.8.8"),
        )
        assertEquals(
            listOf("1.1.1.1", "2001:4860:4860::8888"),
            DnsLeakPolicy.parseFallback("1.1.1.1\n\t 2001:4860:4860:0000:0000:0000:0000:8888 "),
        )
    }

    @Test
    fun fallbackParsingDropsInvalidEntriesAndDuplicates() {
        assertEquals(
            listOf("1.1.1.1", "9.9.9.9"),
            DnsLeakPolicy.parseFallback("1.1.1.1, not-an-ip, 127.0.0.1, 1.1.1.1, 9.9.9.9"),
        )
        assertEquals(emptyList<String>(), DnsLeakPolicy.parseFallback(""))
        assertEquals(emptyList<String>(), DnsLeakPolicy.parseFallback("  , , "))
    }

    @Test
    fun validPushedServersWinAndAreNormalized() {
        val decision = DnsLeakPolicy.decide(
            pushedServers = listOf("10.8.0.1", "bogus", "2001:db8:0:0:0:0:0:1"),
            requireTunnelDns = true,
            fallbackServers = listOf("1.1.1.1"),
        )
        assertEquals(
            Decision.TunnelDns(listOf("10.8.0.1", "2001:db8::1"), usedFallback = false),
            decision,
        )
    }

    @Test
    fun fallbackIsUsedWhenNoUsablePushedServerExists() {
        val decision = DnsLeakPolicy.decide(
            pushedServers = listOf("0.0.0.0", "127.0.0.1", "not-an-ip"),
            requireTunnelDns = true,
            fallbackServers = listOf("9.9.9.9", "9.9.9.9", "bad"),
        )
        assertEquals(
            Decision.TunnelDns(listOf("9.9.9.9"), usedFallback = true),
            decision,
        )
    }

    @Test
    fun fallbackIsUsedEvenWhenTheSettingIsOff() {
        val decision = DnsLeakPolicy.decide(
            pushedServers = emptyList(),
            requireTunnelDns = false,
            fallbackServers = listOf("1.1.1.1"),
        )
        assertEquals(Decision.TunnelDns(listOf("1.1.1.1"), usedFallback = true), decision)
    }

    @Test
    fun refusingWhenNoDnsIsAvailableAndTheSettingIsOn() {
        val decision = DnsLeakPolicy.decide(
            pushedServers = emptyList(),
            requireTunnelDns = true,
            fallbackServers = listOf("invalid"),
        )
        assertTrue(decision is Decision.Refused)
        val reason = (decision as Decision.Refused).reason
        assertTrue(reason.contains("Require tunnel DNS"))
        assertTrue(reason.contains("fallback", ignoreCase = true))
        assertTrue(reason.contains("profile", ignoreCase = true))
        assertTrue(reason.contains("DNS", ignoreCase = true))
        assertTrue(reason.contains("leak", ignoreCase = true))
    }

    @Test
    fun emptyResolverListWhenTheSettingIsOffAndNoFallbackExists() {
        assertEquals(
            Decision.TunnelDns(emptyList(), usedFallback = false),
            DnsLeakPolicy.decide(emptyList(), requireTunnelDns = false, fallbackServers = emptyList()),
        )
    }

    @Test
    fun refusedDecisionIsNotEqualToOneWithResolvers() {
        assertFalse(DnsLeakPolicy.decide(emptyList(), true, emptyList()) is Decision.TunnelDns)
    }

    @Test
    fun dropsResolversOfFamiliesTheTunnelDoesNotRoute() {
        val decision = DnsLeakPolicy.decide(
            pushedServers = listOf("10.8.0.1", "2001:db8::1"),
            requireTunnelDns = true,
            fallbackServers = emptyList(),
            tunnelIpv4 = true,
            tunnelIpv6 = false,
        )
        assertEquals(
            Decision.TunnelDns(
                servers = listOf("10.8.0.1"),
                usedFallback = false,
                filteredServers = listOf("2001:db8::1"),
            ),
            decision,
        )
    }

    @Test
    fun usesFallbackOfARoutedFamilyWhenThePushOnlyHasTheForeignFamily() {
        val decision = DnsLeakPolicy.decide(
            pushedServers = listOf("2001:db8::1"),
            requireTunnelDns = true,
            fallbackServers = listOf("9.9.9.9"),
            tunnelIpv4 = true,
            tunnelIpv6 = false,
        )
        assertEquals(
            Decision.TunnelDns(
                servers = listOf("9.9.9.9"),
                usedFallback = true,
                filteredServers = listOf("2001:db8::1"),
            ),
            decision,
        )
    }

    @Test
    fun refusesWhenOnlyForeignFamilyResolversExist() {
        val decision = DnsLeakPolicy.decide(
            pushedServers = listOf("2001:db8::1"),
            requireTunnelDns = true,
            fallbackServers = listOf("2001:db8::2"),
            tunnelIpv4 = true,
            tunnelIpv6 = false,
        )
        assertTrue(decision is Decision.Refused)
        assertTrue((decision as Decision.Refused).reason.contains("address family", ignoreCase = true))
    }

    @Test
    fun ipv6OnlyTunnelDropsIpv4Resolvers() {
        val decision = DnsLeakPolicy.decide(
            pushedServers = listOf("10.8.0.1"),
            requireTunnelDns = true,
            fallbackServers = emptyList(),
            tunnelIpv4 = false,
            tunnelIpv6 = true,
        )
        assertTrue(decision is Decision.Refused)
    }
}
