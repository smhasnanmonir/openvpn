package com.smhasnan.openvpn.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlwaysOnVpnPolicyTest {
    private val app = "com.smhasnan.openvpn"

    @Test
    fun nullConfigurationIsUnknown() {
        assertEquals(SystemVpnState.UNKNOWN, AlwaysOnVpnPolicy.classify(app, null, null))
        assertEquals(SystemVpnState.UNKNOWN, AlwaysOnVpnPolicy.classify(app, null, false))
        assertEquals(SystemVpnState.UNKNOWN, AlwaysOnVpnPolicy.classify(app, null, true))
    }

    @Test
    fun blankConfigurationIsOff() {
        assertEquals(SystemVpnState.OFF, AlwaysOnVpnPolicy.classify(app, "", false))
        assertEquals(SystemVpnState.OFF, AlwaysOnVpnPolicy.classify(app, "   ", true))
    }

    @Test
    fun samePackageWithLockdownIsTheKillSwitchState() {
        assertEquals(SystemVpnState.ALWAYS_ON_LOCKDOWN, AlwaysOnVpnPolicy.classify(app, app, true))
        assertEquals(SystemVpnState.ALWAYS_ON_LOCKDOWN, AlwaysOnVpnPolicy.classify(app, " $app ", true))
    }

    @Test
    fun samePackageWithoutKnownLockdownIsAlwaysOn() {
        assertEquals(SystemVpnState.ALWAYS_ON, AlwaysOnVpnPolicy.classify(app, app, false))
        assertEquals(SystemVpnState.ALWAYS_ON, AlwaysOnVpnPolicy.classify(app, app, null))
    }

    @Test
    fun anotherPackageIsOtherApp() {
        assertEquals(SystemVpnState.OTHER_APP, AlwaysOnVpnPolicy.classify(app, "com.example.other", true))
        assertEquals(SystemVpnState.OTHER_APP, AlwaysOnVpnPolicy.classify(app, "com.example.other", false))
    }

    @Test
    fun everyStateHasANonBlankHonestExplanation() {
        val explanations = SystemVpnState.entries.map { AlwaysOnVpnPolicy.explanation(it) }
        explanations.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(SystemVpnState.entries.size, explanations.distinct().size)
    }

    @Test
    fun onlyLockdownClaimsTheKillSwitch() {
        assertTrue(AlwaysOnVpnPolicy.explanation(SystemVpnState.ALWAYS_ON_LOCKDOWN).contains("kill switch", ignoreCase = true))
        assertTrue(
            AlwaysOnVpnPolicy.explanation(SystemVpnState.ALWAYS_ON_LOCKDOWN)
                .contains("Block connections without VPN", ignoreCase = true),
        )

        val alwaysOn = AlwaysOnVpnPolicy.explanation(SystemVpnState.ALWAYS_ON)
        assertFalse(alwaysOn.contains("this is the system-level kill switch"))
        assertTrue(alwaysOn.contains("not a guaranteed kill switch", ignoreCase = true))
        assertTrue(alwaysOn.contains("lockdown", ignoreCase = true))
    }

    @Test
    fun offExplanationExplainsWhySystemProtectionIsMissing() {
        val text = AlwaysOnVpnPolicy.explanation(SystemVpnState.OFF)
        assertTrue(text.contains("Always-on VPN is off", ignoreCase = true))
        assertTrue(text.contains("Block connections without VPN", ignoreCase = true))
    }

    @Test
    fun otherAppExplanationDoesNotClaimProtectionForThisApp() {
        val text = AlwaysOnVpnPolicy.explanation(SystemVpnState.OTHER_APP)
        assertTrue(text.contains("Another app", ignoreCase = true))
        assertTrue(text.contains("cannot", ignoreCase = true))
    }

    @Test
    fun unknownExplanationDirectsTheUserToAndroidSettings() {
        val text = AlwaysOnVpnPolicy.explanation(SystemVpnState.UNKNOWN)
        assertTrue(text.contains("could not be read", ignoreCase = true))
        assertTrue(text.contains("VPN settings", ignoreCase = true))
    }
}
