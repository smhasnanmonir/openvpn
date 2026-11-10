package com.smhasnan.openvpn.config

import com.smhasnan.openvpn.security.SecretBox
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class ProfileLoadResult(
    val profiles: List<StoredProfile>,
    val errors: List<String>,
)

/**
 * Encrypted, file-backed profile store. Every profile is a single AES-GCM blob;
 * file names are random identifiers and contain no profile metadata.
 */
class ProfileStore(
    private val directory: File,
    private val secretBox: SecretBox,
) {
    private companion object {
        const val EXTENSION = ".profile"
        const val FORMAT_VERSION = 1
    }

    fun loadAll(): ProfileLoadResult {
        directory.mkdirs()
        val errors = mutableListOf<String>()
        val profiles = mutableListOf<StoredProfile>()
        directory.listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }
            ?.sortedBy { it.lastModified() }
            ?.forEach { file ->
                runCatching {
                    val plain = secretBox.decrypt(file.readBytes())
                    decode(plain.toString(Charsets.UTF_8))
                }.onSuccess(profiles::add).onFailure {
                    errors += "One saved profile could not be read (${file.name.take(8)}…)."
                }
            }
        return ProfileLoadResult(profiles.sortedByDescending { it.summary.updatedAt }, errors)
    }

    fun save(profile: StoredProfile) {
        directory.mkdirs()
        val payload = secretBox.encrypt(encode(profile).toString().toByteArray(Charsets.UTF_8))
        val target = File(directory, "${profile.summary.id}$EXTENSION")
        val temp = File(directory, "${profile.summary.id}$EXTENSION.tmp")
        FileOutputStream(temp).use { output ->
            output.write(payload)
            output.flush()
            output.fd.sync()
        }
        check(temp.renameTo(target)) { "Could not replace profile file" }
    }

    /**
     * Decrypts and decodes exactly one stored profile. Returns null when the id
     * has no blob, the blob cannot be decoded, or its payload belongs to a
     * different id. Read paths that need a single profile body use this instead
     * of [loadAll] so callers never hold every decrypted config at once.
     */
    fun load(id: String): StoredProfile? {
        val file = File(directory, "$id$EXTENSION")
        if (!file.exists()) return null
        return runCatching {
            val plain = secretBox.decrypt(file.readBytes())
            decode(plain.toString(Charsets.UTF_8))
        }.getOrNull()?.takeIf { it.summary.id == id }
    }

    fun delete(id: String) {
        val file = File(directory, "$id$EXTENSION")
        if (file.exists() && !file.delete()) {
            error("Could not delete profile file")
        }
    }

    private fun encode(profile: StoredProfile): JSONObject {
        val summary = profile.summary
        return JSONObject()
            .put("version", FORMAT_VERSION)
            .put("id", summary.id)
            .put("name", summary.name)
            .put("emoji", summary.emoji)
            .put("createdAt", summary.createdAt)
            .put("updatedAt", summary.updatedAt)
            .put("contentHash", summary.contentHash ?: JSONObject.NULL)
            .put("config", profile.config)
            .put("metadata", encodeMetadata(summary.metadata))
    }

    private fun decode(json: String): StoredProfile {
        val root = JSONObject(json)
        val version = root.optInt("version", 0)
        require(version == FORMAT_VERSION) { "Unsupported profile version" }
        val id = root.getString("id")
        require(id.isNotBlank()) { "Missing profile id" }
        val summary = ProfileSummary(
            id = id,
            name = root.getString("name"),
            emoji = EmojiCatalog.normalized(root.optString("emoji")),
            metadata = decodeMetadata(root.optJSONObject("metadata")),
            createdAt = root.optLong("createdAt"),
            updatedAt = root.optLong("updatedAt"),
            contentHash = root.optString("contentHash").takeIf { it.isNotBlank() && it != "null" },
        )
        return StoredProfile(summary = summary, config = root.getString("config"))
    }

    private fun encodeMetadata(metadata: ProfileMetadata): JSONObject = JSONObject()
        .put("serverHost", metadata.serverHost ?: JSONObject.NULL)
        .put("serverPort", metadata.serverPort ?: JSONObject.NULL)
        .put("protocol", metadata.protocol ?: JSONObject.NULL)
        .put("autologin", metadata.autologin)
        .put("needsPassword", metadata.needsPassword)
        .put("userlockedUsername", metadata.userlockedUsername ?: JSONObject.NULL)
        .put("staticChallenge", metadata.staticChallenge ?: JSONObject.NULL)
        .put("staticChallengeEcho", metadata.staticChallengeEcho)
        .put("privateKeyPasswordRequired", metadata.privateKeyPasswordRequired)
        .put("allowPasswordSave", metadata.allowPasswordSave)
        .put("externalPki", metadata.externalPki)
        .put("clientCertNotRequired", metadata.clientCertNotRequired)
        .put("profileName", metadata.profileName ?: JSONObject.NULL)
        .put("friendlyName", metadata.friendlyName ?: JSONObject.NULL)

    private fun decodeMetadata(json: JSONObject?): ProfileMetadata {
        if (json == null) return ProfileMetadata()
        return ProfileMetadata(
            serverHost = json.nullableString("serverHost"),
            serverPort = json.nullableString("serverPort"),
            protocol = json.nullableString("protocol"),
            autologin = json.optBoolean("autologin"),
            needsPassword = json.optBoolean("needsPassword"),
            userlockedUsername = json.nullableString("userlockedUsername"),
            staticChallenge = json.nullableString("staticChallenge"),
            staticChallengeEcho = json.optBoolean("staticChallengeEcho"),
            privateKeyPasswordRequired = json.optBoolean("privateKeyPasswordRequired"),
            allowPasswordSave = json.optBoolean("allowPasswordSave", true),
            externalPki = json.optBoolean("externalPki"),
            clientCertNotRequired = json.optBoolean("clientCertNotRequired", false),
            profileName = json.nullableString("profileName"),
            friendlyName = json.nullableString("friendlyName"),
        )
    }

    private fun JSONObject.nullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }
}

/** Encrypted credential store, kept separate from profile blobs. */
class CredentialStore(
    private val directory: File,
    private val secretBox: SecretBox,
) {
    private companion object {
        const val EXTENSION = ".credentials"
        const val FORMAT_VERSION = 1
    }

    fun save(profileId: String, credentials: ProfileCredentials) {
        directory.mkdirs()
        val json = JSONObject()
            .put("version", FORMAT_VERSION)
            .put("username", credentials.username ?: JSONObject.NULL)
            .put("password", credentials.password ?: JSONObject.NULL)
            .put("privateKeyPassword", credentials.privateKeyPassword ?: JSONObject.NULL)
        val payload = secretBox.encrypt(json.toString().toByteArray(Charsets.UTF_8))
        val target = File(directory, "$profileId$EXTENSION")
        val temp = File(directory, "$profileId$EXTENSION.tmp")
        FileOutputStream(temp).use { output ->
            output.write(payload)
            output.flush()
            output.fd.sync()
        }
        check(temp.renameTo(target)) { "Could not replace credential file" }
    }

    fun load(profileId: String): ProfileCredentials? {
        val file = File(directory, "$profileId$EXTENSION")
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(secretBox.decrypt(file.readBytes()).toString(Charsets.UTF_8))
            if (json.optInt("version") != FORMAT_VERSION) return null
            ProfileCredentials(
                username = json.nullableString("username"),
                password = json.nullableString("password"),
                privateKeyPassword = json.nullableString("privateKeyPassword"),
            )
        }.getOrNull()
    }

    fun delete(profileId: String) {
        val file = File(directory, "$profileId$EXTENSION")
        if (file.exists() && !file.delete()) {
            error("Could not delete credential file")
        }
    }

    fun deleteAll() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun JSONObject.nullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }
}
