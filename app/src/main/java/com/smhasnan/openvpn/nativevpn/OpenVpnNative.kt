package com.smhasnan.openvpn.nativevpn

/**
 * JNI entry points. This is this app's own interface, not a stable OpenVPN 3
 * Core API. The native implementation lives in app/src/main/cpp/native_bridge.cpp.
 */
object OpenVpnNative {
    private val loaded: Result<Unit> by lazy {
        runCatching { System.loadLibrary("nativevpn") }
    }

    val isAvailable: Boolean
        get() = loaded.isSuccess

    val loadError: String?
        get() = loaded.exceptionOrNull()?.message

    external fun createSession(callbacks: NativeCallbacks): Long
    external fun destroySession(handle: Long)
    external fun evalConfig(config: String): String
    external fun mergeConfig(path: String): String
    external fun connect(
        handle: Long,
        config: String,
        username: String,
        password: String,
        response: String,
        keyPassword: String,
        blockUnusedFamilies: Boolean,
        /**
         * Refuse every server-pushed compression method instead of the
         * provider-compatible asymmetric LZO mode (ADR-005 / issue #19).
         */
        strictCompression: Boolean,
    ): Boolean
    external fun disconnect(handle: Long)
    external fun respondToChallenge(handle: Long, response: String): Boolean
    external fun reconnect(handle: Long, seconds: Int): Boolean
    external fun resume(handle: Long): Boolean
    external fun getStats(handle: Long): String
    external fun getConnectionInfo(handle: Long): String
    external fun isRunning(handle: Long): Boolean
    external fun engineInfo(): String
    external fun selfTest(): String
}
