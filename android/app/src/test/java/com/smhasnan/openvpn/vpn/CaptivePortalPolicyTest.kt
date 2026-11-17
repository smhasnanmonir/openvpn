package com.smhasnan.openvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptivePortalPolicyTest {
    @Test
    fun validatedNetworkDoesNotNeedAPortal() {
        assertEquals(
            PortalState.NOT_REQUIRED,
            CaptivePortalPolicy.classify(NetworkState(hasInternet = true, validated = true, captivePortal = false)),
        )
    }

    @Test
    fun explicitPortalCapabilityRequiresSignIn() {
        assertEquals(
            PortalState.SIGN_IN_REQUIRED,
            CaptivePortalPolicy.classify(NetworkState(hasInternet = true, validated = false, captivePortal = true)),
        )
        // Even a validated network reports a sign-in while Android still flags
        // the portal capability.
        assertEquals(
            PortalState.SIGN_IN_REQUIRED,
            CaptivePortalPolicy.classify(NetworkState(hasInternet = true, validated = true, captivePortal = true)),
        )
    }

    @Test
    fun internetWithoutValidationIsUnknown() {
        assertEquals(
            PortalState.UNKNOWN,
            CaptivePortalPolicy.classify(NetworkState(hasInternet = true, validated = false, captivePortal = false)),
        )
    }

    @Test
    fun noInternetIsUnknown() {
        assertEquals(
            PortalState.UNKNOWN,
            CaptivePortalPolicy.classify(NetworkState(hasInternet = false, validated = false, captivePortal = false)),
        )
        assertEquals(
            PortalState.UNKNOWN,
            CaptivePortalPolicy.classify(NetworkState(hasInternet = false, validated = true, captivePortal = false)),
        )
    }

    @Test
    fun autoResumeOnlyAfterAPortalPauseAndValidation() {
        assertTrue(CaptivePortalPolicy.shouldAutoResume(pausedForPortal = true, state = PortalState.NOT_REQUIRED))
        assertFalse(CaptivePortalPolicy.shouldAutoResume(pausedForPortal = false, state = PortalState.NOT_REQUIRED))
        assertFalse(CaptivePortalPolicy.shouldAutoResume(pausedForPortal = true, state = PortalState.SIGN_IN_REQUIRED))
        assertFalse(CaptivePortalPolicy.shouldAutoResume(pausedForPortal = true, state = PortalState.UNKNOWN))
        assertFalse(CaptivePortalPolicy.shouldAutoResume(pausedForPortal = false, state = PortalState.UNKNOWN))
    }
}
