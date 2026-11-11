package com.smhasnan.openvpn.nativevpn

import java.util.concurrent.atomic.AtomicLong

/**
 * Owns one native OpenVPN 3 Core session. All methods are safe to call from a
 * background thread; native code serializes start/stop and joins the engine
 * worker before the session is destroyed.
 */
class NativeSession(private val callbacks: NativeCallbacks) : AutoCloseable {
    private val handle = AtomicLong(0L)
    private val closed = AtomicLong(0L)

    init {
        if (!OpenVpnNative.isAvailable) {
            throw NativeEngineUnavailableException(
                OpenVpnNative.loadError ?: "Native OpenVPN engine is unavailable for this device ABI.",
            )
        }
        val created = OpenVpnNative.createSession(callbacks)
        if (created == 0L) {
            throw NativeEngineUnavailableException("Native engine could not create a session.")
        }
        handle.set(created)
    }

    fun connect(
        config: String,
        username: String?,
        password: String?,
        response: String?,
        keyPassword: String?,
        blockUnusedFamilies: Boolean,
        strictCompression: Boolean = false,
    ): Boolean {
        val current = handle.get()
        if (current == 0L) return false
        return OpenVpnNative.connect(
            current,
            config,
            username.orEmpty(),
            password.orEmpty(),
            response.orEmpty(),
            keyPassword.orEmpty(),
            blockUnusedFamilies,
            strictCompression,
        )
    }

    fun respondToChallenge(response: String): Boolean = synchronized(handleLock) {
        val current = handle.get()
        current != 0L && OpenVpnNative.respondToChallenge(current, response)
    }

    fun reconnect(seconds: Int = 0): Boolean = synchronized(handleLock) {
        val current = handle.get()
        current != 0L && OpenVpnNative.reconnect(current, seconds)
    }

    /**
     * Guards the native handle against [close] freeing it between the read and
     * the JNI call. All short handle calls take this lock, so [close] waits for an
     * in-flight stats/challenge/disconnect call before `destroySession` frees the
     * native session. Engine callbacks never call back into NativeSession, so
     * holding the lock across the destroy join cannot deadlock.
     *
     * `connect()` is intentionally not locked: it blocks for the duration of the
     * session attempt and must not block `disconnect()`/`close()`. Destroying the
     * session while `connect()` runs is handled by the native bridge, which stops
     * and joins the engine worker before deleting the session.
     */
    private val handleLock = Any()

    fun resume(): Boolean = synchronized(handleLock) {
        val current = handle.get()
        current != 0L && OpenVpnNative.resume(current)
    }

    fun disconnect() = synchronized(handleLock) {
        val current = handle.get()
        if (current != 0L) {
            runCatching { OpenVpnNative.disconnect(current) }
        }
    }

    fun statsJson(): String? = synchronized(handleLock) {
        val current = handle.get()
        if (current == 0L) null else runCatching { OpenVpnNative.getStats(current) }.getOrNull()
    }

    fun connectionInfoJson(): String? = synchronized(handleLock) {
        val current = handle.get()
        if (current == 0L) null else runCatching { OpenVpnNative.getConnectionInfo(current) }.getOrNull()
    }

    fun isRunning(): Boolean = synchronized(handleLock) {
        val current = handle.get()
        current != 0L && runCatching { OpenVpnNative.isRunning(current) }.getOrDefault(false)
    }

    override fun close() {
        if (closed.compareAndSet(0L, 1L)) {
            synchronized(handleLock) {
                val current = handle.getAndSet(0L)
                if (current != 0L) OpenVpnNative.destroySession(current)
            }
        }
    }
}
