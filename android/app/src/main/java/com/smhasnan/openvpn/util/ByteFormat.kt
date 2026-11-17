package com.smhasnan.openvpn.util

import java.util.Locale

object ByteFormat {
    private val units = arrayOf("B", "KB", "MB", "GB", "TB")

    fun format(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) "${bytes} B" else String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    fun formatRate(bytesPerSecond: Long): String = "${format(bytesPerSecond)}/s"
}
