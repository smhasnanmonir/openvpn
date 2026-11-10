package com.smhasnan.openvpn.util

import java.util.Locale

object DurationFormat {
    /** Formats a positive elapsed duration as H:MM:SS or MM:SS. */
    fun format(elapsedMillis: Long): String {
        val totalSeconds = (elapsedMillis.coerceAtLeast(0L) / 1_000L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
