package com.smhasnan.openvpn.vpn

import com.smhasnan.openvpn.util.LogSanitizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide source of truth for VPN state. Only MyVpnService and the native
 * callbacks write to it; UI code only observes it.
 */
object VpnController {
    private const val MAX_LOG_LINES = 500

    private val _snapshot = MutableStateFlow(VpnSnapshot())
    val snapshot: StateFlow<VpnSnapshot> = _snapshot.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val sequence = AtomicLong(0L)

    fun prepare(profileId: String, name: String, emoji: String) {
        _snapshot.value = VpnSnapshot(
            state = VpnState.PREPARING,
            profileId = profileId,
            profileName = name,
            profileEmoji = emoji,
        )
    }

    fun disconnecting() {
        _snapshot.update {
            it.copy(state = VpnState.DISCONNECTING, error = null, challenge = null)
        }
    }

    fun engineEvent(name: String, info: String, error: Boolean, fatal: Boolean) {
        val mapped = EngineEventMapper.map(name, info, error, fatal)
        // Always record the engine's failure reason. Fatal core events such as
        // CONNECTION_TIMEOUT carry no info text, so the mapped message is the
        // only usable explanation and must reach the diagnostics list.
        when {
            error || fatal -> appendLog(LogLevel.ERROR, mapped.message ?: name)
            name != "SESSION_FINISHED" ->
                appendLog(LogLevel.INFO, "$name${if (info.isBlank()) "" else ": $info"}")
        }

        _snapshot.update { current ->
            // A fatal failure is followed by a non-error DISCONNECTED event; do
            // not downgrade an error to a clean "inactive" state.
            if (name == "DISCONNECTED") {
                when (current.state) {
                    VpnState.ERROR, VpnState.AUTH_FAILED,
                    VpnState.PREPARING, VpnState.CONNECTING, VpnState.RECONNECTING ->
                        return@update current
                    else -> Unit
                }
            }
            // A successful SESSION_FINISHED frequently follows the core's final
            // DISCONNECTED event. Do not erase a real error that preceded it.
            if (name == "SESSION_FINISHED" && !error &&
                (current.state == VpnState.ERROR || current.state == VpnState.AUTH_FAILED)
            ) {
                return@update current
            }
            val next = mapped.state ?: return@update current
            if (name == "SESSION_FINISHED" && !error && current.state == VpnState.DISCONNECTING) {
                return@update current.copy(state = VpnState.DISCONNECTED)
            }
            current.copy(
                state = next,
                error = when {
                    next == VpnState.ERROR || next == VpnState.AUTH_FAILED ->
                        mapped.message?.let(LogSanitizer::sanitize)
                    mapped.clearError -> null
                    else -> current.error
                },
                challenge = when {
                    next == VpnState.NEEDS_CHALLENGE -> mapped.message
                    next == VpnState.CONNECTED || next == VpnState.DISCONNECTED ||
                        next == VpnState.ERROR || next == VpnState.AUTH_FAILED ||
                        next == VpnState.CONNECTING || next == VpnState.RECONNECTING -> null
                    else -> current.challenge
                },
                connectedAtMillis = when {
                    next == VpnState.CONNECTED -> current.connectedAtMillis ?: System.currentTimeMillis()
                    next == VpnState.DISCONNECTED || next == VpnState.ERROR ||
                        next == VpnState.AUTH_FAILED -> null
                    else -> current.connectedAtMillis
                },
                stats = if (next == VpnState.CONNECTED) current.stats else TrafficStats(),
                details = if (next == VpnState.CONNECTED) current.details else ConnectionDetails(),
            )
        }
    }

    fun updateDetails(details: ConnectionDetails) {
        _snapshot.update { it.copy(details = details) }
    }

    fun updateStats(stats: TrafficStats) {
        _snapshot.update { it.copy(stats = stats) }
    }

    fun setChallenge(challenge: String) {
        _snapshot.update {
            it.copy(state = VpnState.NEEDS_CHALLENGE, challenge = challenge)
        }
    }

    fun error(message: String) {
        val safe = LogSanitizer.sanitize(message)
        appendLog(LogLevel.ERROR, safe)
        _snapshot.update {
            it.copy(state = VpnState.ERROR, error = safe, connectedAtMillis = null)
        }
    }

    fun disconnected() {
        _snapshot.update {
            it.copy(
                state = VpnState.DISCONNECTED,
                connectedAtMillis = null,
                error = null,
                challenge = null,
                stats = TrafficStats(),
                details = ConnectionDetails(),
            )
        }
    }

    fun reset() {
        _snapshot.value = VpnSnapshot()
    }

    fun appendLog(level: LogLevel, rawLine: String) {
        val line = LogSanitizer.sanitize(rawLine)
        if (line.isBlank()) return
        _logs.update { existing ->
            if (existing.lastOrNull()?.message == line &&
                System.currentTimeMillis() - (existing.lastOrNull()?.timestampMillis ?: 0L) < 1_000L
            ) {
                existing
            } else {
                (existing + LogEntry(System.currentTimeMillis(), level, line)).takeLast(MAX_LOG_LINES)
            }
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun nextSequence(): Long = sequence.incrementAndGet()
}
