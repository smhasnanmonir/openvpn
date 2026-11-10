package com.smhasnan.openvpn.nativevpn

/**
 * Callback surface consumed by the native bridge. Method names and signatures
 * are part of the JNI contract and are kept by R8 (see proguard-rules.pro).
 */
interface NativeCallbacks {
    fun onEngineEvent(name: String, info: String, error: Boolean, fatal: Boolean)
    fun onEngineLog(line: String)
    fun onTunEstablish(configJson: String): Int
    fun onProtectSocket(fd: Int): Boolean
    fun onChallenge(challenge: String, echo: Boolean, responseRequired: Boolean, stateId: String)
}

class NativeEngineUnavailableException(message: String) : IllegalStateException(message)
