package com.smhasnan.openvpn.vpn

import com.smhasnan.openvpn.settings.SplitTunnelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTunnelPolicyTest {
    @Test
    fun effectivePackagesAreTheIntersectionSorted() {
        assertEquals(
            listOf("com.example.b", "com.example.c"),
            SplitTunnelPolicy.effectivePackages(
                requested = setOf("com.example.c", "com.example.a", "com.example.b"),
                installed = setOf("com.example.b", "com.example.c", "com.example.z"),
            ),
        )
    }

    @Test
    fun effectivePackagesIsEmptyWhenNothingMatches() {
        assertEquals(emptyList<String>(), SplitTunnelPolicy.effectivePackages(setOf("gone"), setOf("kept")))
        assertEquals(emptyList<String>(), SplitTunnelPolicy.effectivePackages(emptySet(), setOf("kept")))
    }

    @Test
    fun uninstalledListsMissingPackagesSorted() {
        assertEquals(
            listOf("com.example.a", "com.example.m"),
            SplitTunnelPolicy.uninstalled(
                requested = setOf("com.example.m", "com.example.a", "com.example.b"),
                installed = setOf("com.example.b"),
            ),
        )
    }

    @Test
    fun offModeIsAlwaysValid() {
        assertEquals(Validation.Ok, SplitTunnelPolicy.validate(SplitTunnelMode.OFF, emptySet(), emptySet()))
        assertEquals(
            Validation.Ok,
            SplitTunnelPolicy.validate(SplitTunnelMode.OFF, setOf("a"), setOf("not-installed")),
        )
    }

    @Test
    fun includeModeRequiresAtLeastOneEffectivePackage() {
        assertEquals(
            Validation.Ok,
            SplitTunnelPolicy.validate(SplitTunnelMode.INCLUDE_SELECTED, setOf("a", "b"), setOf("a")),
        )
        assertTrue(
            SplitTunnelPolicy.validate(SplitTunnelMode.INCLUDE_SELECTED, setOf("a", "b"), setOf("gone")) is Validation.Rejected,
        )
        assertTrue(
            SplitTunnelPolicy.validate(SplitTunnelMode.INCLUDE_SELECTED, setOf("a"), emptySet()) is Validation.Rejected,
        )
    }

    @Test
    fun includeRejectionExplainsWhy() {
        val rejected = SplitTunnelPolicy.validate(
            SplitTunnelMode.INCLUDE_SELECTED,
            setOf("a"),
            setOf("gone"),
        ) as Validation.Rejected
        assertTrue(rejected.reason.contains("installed app", ignoreCase = true))
        assertTrue(rejected.reason.contains("outside", ignoreCase = true))
    }

    @Test
    fun excludeModeMustLeaveAnAppInsideTheTunnel() {
        assertEquals(
            Validation.Ok,
            SplitTunnelPolicy.validate(SplitTunnelMode.EXCLUDE_SELECTED, setOf("a", "b"), setOf("a")),
        )
        assertEquals(
            Validation.Ok,
            SplitTunnelPolicy.validate(SplitTunnelMode.EXCLUDE_SELECTED, setOf("a", "b"), emptySet()),
        )
        assertTrue(
            SplitTunnelPolicy.validate(SplitTunnelMode.EXCLUDE_SELECTED, setOf("a", "b"), setOf("a", "b")) is Validation.Rejected,
        )
    }

    @Test
    fun excludeRejectionExplainsWhy() {
        val rejected = SplitTunnelPolicy.validate(
            SplitTunnelMode.EXCLUDE_SELECTED,
            setOf("a"),
            setOf("a"),
        ) as Validation.Rejected
        assertTrue(rejected.reason.contains("inside the VPN", ignoreCase = true))
    }

    @Test
    fun planKnowsWhenItApplies() {
        assertFalse(Plan(SplitTunnelMode.OFF, emptyList()).isActive)
        assertTrue(Plan(SplitTunnelMode.INCLUDE_SELECTED, listOf("a")).isActive)
        assertTrue(Plan(SplitTunnelMode.EXCLUDE_SELECTED, listOf("a")).isActive)
        assertFalse(Plan(SplitTunnelMode.INCLUDE_SELECTED, emptyList()).isActive)
    }

    @Test
    fun offModeHasNoWarning() {
        assertNull(SplitTunnelPolicy.warning(SplitTunnelMode.OFF, 0))
        assertNull(SplitTunnelPolicy.warning(SplitTunnelMode.OFF, 3))
    }

    @Test
    fun includeWarningSaysOtherAppsBypassTheVpn() {
        val text = SplitTunnelPolicy.warning(SplitTunnelMode.INCLUDE_SELECTED, 2)
        assertNotNull(text)
        assertTrue(text!!.contains("Only 2 selected apps"))
        assertTrue(text.contains("bypass", ignoreCase = true))
        assertTrue(text.contains("IP address", ignoreCase = true))
    }

    @Test
    fun includeWarningUsesSingularForOneApp() {
        assertTrue(SplitTunnelPolicy.warning(SplitTunnelMode.INCLUDE_SELECTED, 1)!!.contains("Only 1 selected app "))
    }

    @Test
    fun excludeWarningSaysSelectedAppsBypassTheVpn() {
        val text = SplitTunnelPolicy.warning(SplitTunnelMode.EXCLUDE_SELECTED, 1)
        assertNotNull(text)
        assertTrue(text!!.contains("1 selected app"))
        assertTrue(text.contains("bypass", ignoreCase = true))
        assertTrue(text.contains("not protected", ignoreCase = true))
        assertTrue(text.contains("IP address", ignoreCase = true))
    }
}
