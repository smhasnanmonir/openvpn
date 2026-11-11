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
        )
    }

    fun respondToChallenge(response: String): Boolean {
        val current = handle.get()
        return current != 0L && OpenVpnNative.respondToChallenge(current, response)
    }

    fun reconnect(seconds: Int = 0): Boolean {
        val current = handle.get()
        return current != 0L && OpenVpnNative.reconnect(current, seconds)
    }

    /**
     * Guards the native handle against [close] freeing it between the read and
     * the JNI call. Only [resume] takes this lock: it is called from the service
     * retry coroutine, never from a native engine callback, so [close] can hold
     * it across `destroySession` (which joins the engine worker) without risking
     * a callback deadlock.
     */
    private val resumeCloseLock = Any()

    fun resume(): Boolean = synchronized(resumeCloseLock) {
        val current = handle.get()
        current != 0L && OpenVpnNative.resume(current)
    }

    fun disconnect() {
        val current = handle.get()
        if (current != 0L) OpenVpnNative.disconnect(current)
    }

    fun statsJson(): String? {
        val current = handle.get()
        return if (current == 0L) null else OpenVpnNative.getStats(current)
    }

    fun connectionInfoJson(): String? {
        val current = handle.get()
        return if (current == 0L) null else OpenVpnNative.getConnectionInfo(current)
    }

    fun isRunning(): Boolean {
        val current = handle.get()
        return current != 0L && OpenVpnNative.isRunning(current)
    }

    override fun close() {
        if (closed.compareAndSet(0L, 1L)) {
            synchronized(resumeCloseLock) {
                val current = handle.getAndSet(0L)
                if (current != 0L) OpenVpnNative.destroySession(current)
            }
        }
    }
}
