package com.smhasnan.openvpn.vpn

import com.smhasnan.openvpn.settings.MtuMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MtuPolicyTest {
    @Test
    fun automaticModeLeavesTheProfileUntouched() {
        val config = "client\nremote example.com 1194\n"
        assertEquals(config, MtuPolicy.withTunMtuOverride(config, MtuMode.AUTO))
        assertNull(MtuPolicy.effectiveMtu(MtuMode.AUTO))
    }

    @Test
    fun appendsPullFilterAndOverride() {
        val result = MtuPolicy.withTunMtuOverride("client\nremote example.com 1194\n", MtuMode.MTU1400)
        assertEquals(
            "client\nremote example.com 1194\npull-filter ignore tun-mtu\ntun-mtu 1400\n",
            result,
        )
        assertEquals(1400, MtuPolicy.effectiveMtu(MtuMode.MTU1400))
    }

    @Test
    fun preservesTheAbsenceOfATrailingNewline() {
        val result = MtuPolicy.withTunMtuOverride("client", MtuMode.MTU1280)
        assertEquals("client\npull-filter ignore tun-mtu\ntun-mtu 1280", result)
        assertFalse(result.endsWith("\n"))
    }

    @Test
    fun applyingTheOverrideIsIdempotent() {
        for (mode in listOf(MtuMode.MTU1500, MtuMode.MTU1400, MtuMode.MTU1280)) {
            val once = MtuPolicy.withTunMtuOverride("client\nremote example.com 1194\n", mode)
            val twice = MtuPolicy.withTunMtuOverride(once, mode)
            assertEquals("mode $mode", once, twice)
            assertEquals("mode $mode", 1, twice.lines().count { it.trim() == "pull-filter ignore tun-mtu" })
            assertEquals("mode $mode", 1, twice.lines().count { it.trim() == "tun-mtu ${mode.mtu}" })
        }
    }

    @Test
    fun leavesAnExistingOverrideUntouched() {
        val config = "client\npull-filter ignore tun-mtu\ntun-mtu 1400\n"
        assertEquals(config, MtuPolicy.withTunMtuOverride(config, MtuMode.MTU1400))
    }

    @Test
    fun differentModeReplacesTheEffectiveValueLast() {
        val config = "client\ntun-mtu 1500\n"
        val result = MtuPolicy.withTunMtuOverride(config, MtuMode.MTU1280)
        val lines = result.lines().filter { it.isNotBlank() }
        assertEquals("tun-mtu 1280", lines.last())
        assertTrue(result.contains("pull-filter ignore tun-mtu"))
        // The appended value is last, so OpenVPN's sequential option parsing keeps it.
        assertTrue(result.lines().filter { it.trim().startsWith("tun-mtu ") }.size >= 2)
    }

    @Test
    fun supportsEveryFixedMode() {
        assertEquals(MtuMode.MTU1500.mtu, MtuPolicy.effectiveMtu(MtuMode.MTU1500))
        assertEquals(MtuMode.MTU1400.mtu, MtuPolicy.effectiveMtu(MtuMode.MTU1400))
        assertEquals(MtuMode.MTU1280.mtu, MtuPolicy.effectiveMtu(MtuMode.MTU1280))
    }

    @Test
    fun descriptionsAreHumanReadableAndNameTheValue() {
        assertTrue(MtuPolicy.describe(MtuMode.AUTO).contains("Automatic", ignoreCase = true))
        assertTrue(MtuPolicy.describe(MtuMode.MTU1400).contains("1400"))
        assertTrue(MtuPolicy.describe(MtuMode.MTU1280).contains("1280"))
    }
}
