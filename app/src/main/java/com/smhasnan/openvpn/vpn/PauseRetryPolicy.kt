package com.smhasnan.openvpn.vpn

/**
 * Bounded retry policy for a session that OpenVPN Core put in its `PAUSE` state
 * after a post-connect connection timeout.
 *
 * While Core is paused the tunnel is closed, so the device is no longer
 * protected by the VPN. Core emits `RESUME` and starts a fresh connection
 * attempt when it is asked to; if that attempt keeps timing out, retrying
 * forever would leave the UI claiming "Reconnecting" with unprotected traffic
 * for an unbounded period. Retries therefore use exponential backoff and are
 * capped: after [MAX_ATTEMPTS] the service surfaces an explicit error instead
 * of pretending the tunnel is coming back.
 *
 * The attempt counter is reset whenever the session reaches `CONNECTED`, so a
 * later outage gets its own retry budget.
 */
object PauseRetryPolicy {
    /** First wait before asking Core to resume. */
    const val INITIAL_DELAY_MS = 5_000L

    /** Upper bound for the exponential backoff. */
    const val MAX_DELAY_MS = 60_000L

    /** Resume attempts allowed after a successful connection before giving up. */
    const val MAX_ATTEMPTS = 6

    /**
     * Delay before resume attempt [attempt] (1-based). Doubles from
     * [INITIAL_DELAY_MS] and never exceeds [MAX_DELAY_MS].
     */
    fun delayMsForAttempt(attempt: Int): Long {
        var delay = INITIAL_DELAY_MS
        var doublings = attempt.coerceAtLeast(1) - 1
        while (doublings-- > 0 && delay < MAX_DELAY_MS) {
            delay = (delay * 2).coerceAtMost(MAX_DELAY_MS)
        }
        return delay
    }

    /** True once the retry budget is exhausted and the session must fail visibly. */
    fun shouldGiveUp(attempts: Int): Boolean = attempts >= MAX_ATTEMPTS
}
