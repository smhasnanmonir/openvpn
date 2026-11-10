package com.smhasnan.openvpn.config

/** Non-secret metadata obtained by OpenVPN 3 Core's eval_config(). */
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

data class StoredProfile(
    val summary: ProfileSummary,
    val config: String,
)

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
