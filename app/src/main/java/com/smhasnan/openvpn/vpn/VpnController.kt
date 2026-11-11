package com.smhasnan.openvpn.vpn

import com.smhasnan.openvpn.util.LogSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide source of truth for VPN state. Only MyVpnService and the native
 * callbacks write to it; UI code only observes it.
 */
object VpnController {
    private const val MAX_LOG_LINES = 500

    /**
     * Native callbacks append diagnostics from arbitrary threads and often in
     * bursts (a connect attempt easily emits hundreds of core lines). Appending
     * a line therefore only touches a small synchronized ring buffer; a single
     * lightweight coroutine publishes one coalesced immutable snapshot per
     * interval. This keeps the observability semantics (ordering, 500-line cap,
     * consecutive-duplicate suppression) while avoiding a full list copy per
     * line. Errors are published synchronously because they are rare and the UI
     * needs the failure reason immediately.
     */
    private const val LOG_PUBLISH_INTERVAL_MS = 250L
    private const val DUPLICATE_WINDOW_MS = 1_000L

    private val _snapshot = MutableStateFlow(VpnSnapshot())
    val snapshot: StateFlow<VpnSnapshot> = _snapshot.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val sequence = AtomicLong(0L)

    /**
     * Guards [pendingLines], [lastPublishedEntry] and all writes to [_logs].
     * A plain lock (not a coroutine Mutex) is required because native engine
     * callbacks append from non-coroutine threads.
     */
    private val logLock = Any()
    private val pendingLines = ArrayDeque<LogEntry>()
    private var lastPublishedEntry: LogEntry? = null

    /** Single conflated "there are buffered lines" signal. */
    private val publishSignal = Channel<Unit>(Channel.CONFLATED)
    private val publishScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val publisherStarted = AtomicBoolean(false)

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

    fun engineEvent(
        name: String,
        info: String,
        error: Boolean,
        fatal: Boolean,
        rekeyHint: Boolean = false,
    ) {
        val mapped = EngineEventMapper.map(name, info, error, fatal, rekeyHint)
        // Always record the engine's failure reason. Fatal core events such as
        // CONNECTION_TIMEOUT carry no info text, so the mapped message is the
        // only usable explanation and must reach the diagnostics list.
        when {
            error || fatal -> {
                val message = mapped.message ?: name
                appendLog(LogLevel.ERROR, message)
                // Keep the engine's own detail in Diagnostics even when the
                // mapper replaced it with a user-facing explanation.
                if (info.isNotBlank() && info != message) {
                    appendLog(LogLevel.INFO, "Engine detail: $info")
                }
            }
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
        clearLogs()
    }

    fun appendLog(level: LogLevel, rawLine: String) {
        val message = LogSanitizer.sanitize(rawLine)
        if (message.isBlank()) return
        val entry = LogEntry(System.currentTimeMillis(), level, message)
        synchronized(logLock) {
            if (isConsecutiveDuplicateLocked(message, entry.timestampMillis)) return
            pendingLines.addLast(entry)
            while (pendingLines.size > MAX_LOG_LINES) {
                pendingLines.removeFirst()
            }
            if (level == LogLevel.ERROR) {
                // Failure reasons must be visible immediately; error lines are
                // rare compared with the engine's informational bursts.
                publishLocked()
                return
            }
        }
        ensurePublisher()
        publishSignal.trySend(Unit)
    }

    fun clearLogs() {
        synchronized(logLock) {
            pendingLines.clear()
            lastPublishedEntry = null
            _logs.value = emptyList()
        }
    }

    fun nextSequence(): Long = sequence.incrementAndGet()

    private fun isConsecutiveDuplicateLocked(message: String, timestampMillis: Long): Boolean {
        val previous = pendingLines.lastOrNull() ?: lastPublishedEntry ?: return false
        return previous.message == message &&
            timestampMillis - previous.timestampMillis < DUPLICATE_WINDOW_MS
    }

    /** Merges buffered lines into the published snapshot. Caller holds [logLock]. */
    private fun publishLocked() {
        if (pendingLines.isEmpty()) return
        val existing = _logs.value
        val pendingCount = pendingLines.size
        // Only the part of the previous snapshot that still fits is retained;
        // untouched entries are not copied when a burst fills the buffer.
        val retainedCount = (MAX_LOG_LINES - pendingCount).coerceAtLeast(0)
        val firstRetained = (existing.size - retainedCount).coerceAtLeast(0)
        val merged = ArrayList<LogEntry>(existing.size - firstRetained + pendingCount)
        for (index in firstRetained until existing.size) {
            merged.add(existing[index])
        }
        merged.addAll(pendingLines)
        pendingLines.clear()
        _logs.value = Collections.unmodifiableList(merged)
        lastPublishedEntry = merged[merged.size - 1]
    }

    private fun ensurePublisher() {
        if (!publisherStarted.compareAndSet(false, true)) return
        publishScope.launch {
            while (true) {
                publishSignal.receive()
                delay(LOG_PUBLISH_INTERVAL_MS)
                synchronized(logLock) { publishLocked() }
            }
        }
    }
}
