package ovpn

import (
	"math/rand"
	"time"
)

// RetryPolicy bounds automatic reconnects after a session fails or the process
// exits unexpectedly. While no tunnel is up the computer is unprotected, so
// the UI must surface the state honestly and the retries must stop instead of
// claiming "Reconnecting" forever.
type RetryPolicy struct {
	Initial     time.Duration
	MaxDelay    time.Duration
	MaxAttempts int
}

// DefaultRetryPolicy mirrors the Android client's bounded backoff.
func DefaultRetryPolicy() RetryPolicy {
	return RetryPolicy{
		Initial:     5 * time.Second,
		MaxDelay:    60 * time.Second,
		MaxAttempts: 6,
	}
}

// BaseDelay returns the deterministic backoff before attempt (1-based).
func (p RetryPolicy) BaseDelay(attempt int) time.Duration {
	if attempt < 1 {
		attempt = 1
	}
	d := p.Initial
	if d <= 0 {
		d = 5 * time.Second
	}
	max := p.MaxDelay
	if max <= 0 {
		max = 60 * time.Second
	}
	for i := 1; i < attempt && d < max; i++ {
		d *= 2
		if d > max {
			d = max
		}
	}
	return d
}

// Delay returns a jittered delay in [base/2, base].
func (p RetryPolicy) Delay(attempt int, r *rand.Rand) time.Duration {
	base := p.BaseDelay(attempt)
	lower := base / 2
	spread := base - lower
	if spread <= 0 {
		return base
	}
	if r == nil {
		return lower + time.Duration(time.Now().UnixNano())%spread
	}
	return lower + time.Duration(r.Int63n(int64(spread)+1))
}

// ShouldGiveUp reports whether the retry budget is exhausted.
func (p RetryPolicy) ShouldGiveUp(attempts int) bool {
	max := p.MaxAttempts
	if max <= 0 {
		max = 6
	}
	return attempts >= max
}
