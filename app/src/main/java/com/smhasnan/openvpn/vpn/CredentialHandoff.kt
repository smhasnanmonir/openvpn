package com.smhasnan.openvpn.vpn

import com.smhasnan.openvpn.config.ProfileCredentials
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process handoff for credentials entered by the user. Credentials are never
 * put in Intent extras, notification text, or logs. The service removes the
 * entry as soon as it starts a connection.
 */
object CredentialHandoff {
    private val pending = ConcurrentHashMap<String, ProfileCredentials>()

    fun put(profileId: String, credentials: ProfileCredentials) {
        pending[profileId] = credentials
    }

    fun take(profileId: String): ProfileCredentials? = pending.remove(profileId)

    fun clear() = pending.clear()
}
