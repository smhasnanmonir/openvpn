package com.smhasnan.openvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the bounded post-connect retry policy. While Core is paused there is no
 * tunnel, so the retry budget must grow slowly, stay capped and eventually fail
 * the session visibly instead of claiming "Reconnecting" forever.
 */
class PauseRetryPolicyTest {
    @Test
    fun delayDoublesFromTheInitialValueUpToTheCap() {
        assertEquals(PauseRetryPolicy.INITIAL_DELAY_MS, PauseRetryPolicy.delayMsForAttempt(1))
        assertEquals(2 * PauseRetryPolicy.INITIAL_DELAY_MS, PauseRetryPolicy.delayMsForAttempt(2))
        assertEquals(4 * PauseRetryPolicy.INITIAL_DELAY_MS, PauseRetryPolicy.delayMsForAttempt(3))
        assertEquals(8 * PauseRetryPolicy.INITIAL_DELAY_MS, PauseRetryPolicy.delayMsForAttempt(4))
        assertEquals(PauseRetryPolicy.MAX_DELAY_MS, PauseRetryPolicy.delayMsForAttempt(5))
        assertEquals(PauseRetryPolicy.MAX_DELAY_MS, PauseRetryPolicy.delayMsForAttempt(6))
        assertEquals(PauseRetryPolicy.MAX_DELAY_MS, PauseRetryPolicy.delayMsForAttempt(50))
    }

    @Test
    fun delayIsDefinedForNonPositiveAttempts() {
        assertEquals(PauseRetryPolicy.INITIAL_DELAY_MS, PauseRetryPolicy.delayMsForAttempt(0))
        assertEquals(PauseRetryPolicy.INITIAL_DELAY_MS, PauseRetryPolicy.delayMsForAttempt(-3))
    }

    @Test
    fun retryBudgetIsExhaustedAtTheConfiguredAttemptCount() {
        assertFalse(PauseRetryPolicy.shouldGiveUp(0))
        assertFalse(PauseRetryPolicy.shouldGiveUp(PauseRetryPolicy.MAX_ATTEMPTS - 1))
        assertTrue(PauseRetryPolicy.shouldGiveUp(PauseRetryPolicy.MAX_ATTEMPTS))
        assertTrue(PauseRetryPolicy.shouldGiveUp(PauseRetryPolicy.MAX_ATTEMPTS + 1))
    }

    @Test
    fun aBoundedEpisodeLastsLongEnoughForASleepingDeviceToWakeUp() {
        var total = 0L
        for (attempt in 1..PauseRetryPolicy.MAX_ATTEMPTS) {
            total += PauseRetryPolicy.delayMsForAttempt(attempt)
        }
        assertTrue(
            "retry budget should span more than two minutes of delays",
            total >= 120_000L,
        )
    }
}
