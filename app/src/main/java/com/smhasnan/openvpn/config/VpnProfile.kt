package com.smhasnan.openvpn.config

import androidx.compose.runtime.Immutable

/** Non-secret metadata obtained by OpenVPN 3 Core's eval_config(). */
@Immutable
data class ProfileMetadata(
    val serverHost: String? = null,
    val serverPort: String? = null,
    val protocol: String? = null,
    val autologin: Boolean = false,
    val needsPassword: Boolean = false,
    val userlockedUsername: String? = null,
    val staticChallenge: String? = null,
    val staticChallengeEcho: Boolean = false,
    val privateKeyPasswordRequired: Boolean = false,
    val allowPasswordSave: Boolean = false,
    val externalPki: Boolean = false,
    /**
     * True when the importer added Core's `setenv CLIENT_CERT 0` directive to
     * the stored profile because the profile signs in with a username/password
     * and has no client certificate.
     */
    val clientCertNotRequired: Boolean = false,
    val profileName: String? = null,
    val friendlyName: String? = null,
) {
    val displayServer: String?
        get() = when {
            serverHost.isNullOrBlank() -> null
            serverPort.isNullOrBlank() -> serverHost
            else -> "$serverHost:$serverPort"
        }

    val protocolLabel: String?
        get() = protocol?.takeIf { it.isNotBlank() }?.uppercase()
}

@Immutable
data class ProfileSummary(
    val id: String,
    val name: String,
    val emoji: String,
    val metadata: ProfileMetadata,
    val createdAt: Long,
    val updatedAt: Long,
    val contentHash: String? = null,
) {
    val needsInteractiveCredentials: Boolean
        get() = !metadata.autologin || metadata.staticChallenge != null || metadata.privateKeyPasswordRequired
}

@Immutable
data class StoredProfile(
    val summary: ProfileSummary,
    val config: String,
)

@Immutable
data class ProfileCredentials(
    val username: String? = null,
    val password: String? = null,
    val privateKeyPassword: String? = null,
    /** Static-challenge response. Never persisted. */
    val response: String? = null,
) {
    val isEmpty: Boolean
        get() = username.isNullOrEmpty() && password.isNullOrEmpty() && privateKeyPassword.isNullOrEmpty() && response.isNullOrEmpty()
}

sealed interface ImportOutcome {
    val fileName: String

    data class Success(
        override val fileName: String,
        val profileId: String,
        val alreadyImported: Boolean,
        /**
         * Non-fatal security/compatibility note shown after a successful import
         * (legacy ciphers offered by the profile, or a same-named profile that
         * was imported earlier with a different CA certificate).
         */
        val warning: String? = null,
    ) : ImportOutcome

    data class Failure(
        override val fileName: String,
        val message: String,
    ) : ImportOutcome
}

data class NativeEvalResult(
    val error: Boolean,
    val message: String,
    val metadata: ProfileMetadata,
)

data class MergeResult(
    val success: Boolean,
    val content: String?,
    val message: String?,
    val refCount: Int,
)
