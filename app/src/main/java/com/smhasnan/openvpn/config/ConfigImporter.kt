package com.smhasnan.openvpn.config

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.smhasnan.openvpn.nativevpn.NativeEngine
import com.smhasnan.openvpn.util.LogSanitizer
import com.smhasnan.openvpn.vpn.LogLevel
import com.smhasnan.openvpn.vpn.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

private const val CA_CHANGE_WARNING =
    "A profile with this name was imported earlier with a different CA certificate. " +
        "Verify the new CA before trusting this profile."

/**
 * Imports .ovpn documents selected through the Storage Access Framework.
 *
 * The selected files are copied into a private cache directory only long enough
 * for OpenVPN Core to resolve references and merge the profile. The unified
 * profile produced by Core is then encrypted and stored. Nothing is written to
 * shared storage and file contents are never logged.
 */
class ConfigImporter(
    private val context: Context,
    private val repository: ProfileRepository,
) {
    suspend fun import(uris: List<Uri>): List<ImportOutcome> = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) {
            return@withContext listOf(
                ImportOutcome.Failure("Selection", "Select at least one .ovpn profile."),
            )
        }
        val documents = uris.mapNotNull { uri ->
            val name = displayName(uri) ?: uri.lastPathSegment ?: "profile.ovpn"
            PickedDocument(uri, name)
        }
        val profiles = documents.filter { ImportFilePolicy.isProfileFile(it.name) }
        if (profiles.isEmpty()) {
            return@withContext listOf(
                ImportOutcome.Failure(
                    documents.firstOrNull()?.name ?: "Selection",
                    "No .ovpn file was selected. Pick a profile ending in .ovpn.",
                ),
            )
        }

        val companionDocuments = documents.filterNot { ImportFilePolicy.isProfileFile(it.name) }
        profiles.map { document -> importOne(document, companionDocuments) }
    }

    private suspend fun importOne(
        document: PickedDocument,
        companions: List<PickedDocument>,
    ): ImportOutcome {
        val tempDir = File(context.cacheDir, "import-${UUID.randomUUID()}")
        if (!tempDir.mkdirs()) {
            return ImportOutcome.Failure(document.name, "Could not create a private import directory.")
        }
        return try {
            val mainFile = File(tempDir, "profile.ovpn")
            copyBounded(document.uri, mainFile)

            val writtenNames = mutableSetOf<String>()
            for (companion in companions) {
                val safeName = ImportFilePolicy.safeCompanionName(companion.name)
                    ?: return ImportOutcome.Failure(
                        document.name,
                        "Referenced file “${companion.name}” has an unsupported name.",
                    )
                if (!writtenNames.add(safeName)) {
                    return ImportOutcome.Failure(
                        document.name,
                        "Two selected files have the same name: $safeName.",
                    )
                }
                copyBounded(companion.uri, File(tempDir, safeName))
            }

            val merged = NativeEngine.mergeConfig(mainFile.absolutePath)
            if (!merged.success || merged.content.isNullOrBlank()) {
                val detail = (merged.message ?: "The profile could not be parsed.")
                    .replace(tempDir.absolutePath, "")
                    .replace(tempDir.path, "")
                    .trim()
                return ImportOutcome.Failure(
                    document.name,
                    LogSanitizer.sanitize(detail).ifBlank { "The profile could not be parsed." },
                )
            }

            val eval = NativeEngine.evalConfig(merged.content)
            if (eval.error) {
                return ImportOutcome.Failure(
                    document.name,
                    LogSanitizer.sanitize(eval.message).ifBlank { "OpenVPN Core rejected this profile." },
                )
            }
            var content = merged.content
            var metadata = eval.metadata
            if (eval.metadata.externalPki) {
                if (ProfileImportPolicy.declaresExternalPki(content)) {
                    return ImportOutcome.Failure(
                        document.name,
                        "This profile requires an external PKI certificate and key provider, which this app does not have. Import a profile that contains its certificate and key.",
                    )
                }
                if (!ProfileImportPolicy.declaresUserPasswordAuth(content)) {
                    return ImportOutcome.Failure(
                        document.name,
                        "This profile has no client certificate or key and no sign-in credentials. Import a profile that contains its certificate and key or a username/password sign-in.",
                    )
                }
                // Core treats a profile without a client cert/key as external PKI,
                // but username/password-only profiles are fully supported once Core
                // is told that no client certificate is required.
                val adapted = ProfileImportPolicy.withoutClientCertificate(content)
                val reEval = NativeEngine.evalConfig(adapted)
                if (reEval.error || reEval.metadata.externalPki) {
                    return ImportOutcome.Failure(
                        document.name,
                        "This profile could not be adapted for sign-in without a client certificate. Import a profile that contains its certificate and key.",
                    )
                }
                content = adapted
                metadata = reEval.metadata.copy(clientCertNotRequired = true)
            }

            // Provider exports often pin legacy TLS 1.2 cipher suites that no
            // longer match providers serving TLS 1.3 only; the handshake then
            // stalls until the connection timeout with no authentication error.
            // Drop such a pin and re-validate the profile.
            val negotiable = ProfileImportPolicy.withNegotiableTlsCiphers(content)
            if (negotiable != content) {
                val tlsEval = NativeEngine.evalConfig(negotiable)
                if (tlsEval.error) {
                    return ImportOutcome.Failure(
                        document.name,
                        "This profile could not be prepared for modern TLS negotiation. Import a profile that contains its certificate and key or a username/password sign-in.",
                    )
                }
                VpnController.appendLog(
                    LogLevel.INFO,
                    "Profile import: removed a legacy-only tls-cipher pin so the server can negotiate TLS 1.3.",
                )
                content = negotiable
                metadata = tlsEval.metadata.copy(clientCertNotRequired = metadata.clientCertNotRequired)
            }

            // Enforce server-certificate usage for CA-based profiles that do not
            // already constrain it. Without remote-cert-tls server, any
            // certificate signed by the CA (including a client certificate) can
            // be accepted as the server.
            val contentBeforeServerUsage = content
            val serverUsage = ProfileImportPolicy.withServerCertificateUsage(content)
            if (serverUsage != content) {
                val certEval = NativeEngine.evalConfig(serverUsage)
                if (certEval.error) {
                    val detail = LogSanitizer.sanitize(certEval.message)
                    return ImportOutcome.Failure(
                        document.name,
                        detail.ifBlank {
                            "This profile could not be prepared with server-certificate verification. Import a profile that contains its certificate and key or a username/password sign-in."
                        },
                    )
                }
                VpnController.appendLog(
                    LogLevel.INFO,
                    "Profile import: added remote-cert-tls server to require a server certificate.",
                )
                content = serverUsage
                metadata = certEval.metadata.copy(clientCertNotRequired = metadata.clientCertNotRequired)
            }

            // Fail closed when the profile carries no server trust anchor (a CA or
            // an explicit legacy tls-remote policy). A peer-fingerprint pin alone is
            // not accepted because the pinned mbedTLS backend ignores it; without a
            // CA/tls-remote anchor OpenVPN cannot authenticate the server at all.
            when (val trust = ProfileTrustPolicy.validateForImport(content)) {
                is ImportDecision.Rejected ->
                    return ImportOutcome.Failure(document.name, trust.message)
                ImportDecision.Allowed -> Unit
            }

            val hash = sha256(content)
            // Profiles imported by older builds were hashed before
            // `remote-cert-tls server` was appended. Check both hashes so an
            // upgrade re-import is still recognised instead of duplicated.
            val previousHash =
                if (serverUsage != contentBeforeServerUsage) sha256(contentBeforeServerUsage) else null
            val existing = repository.findByHash(hash)
                ?: if (previousHash != null) repository.findByHash(previousHash) else null
            existing?.let { already ->
                repository.select(already.id)
                return ImportOutcome.Success(document.name, already.id, alreadyImported = true)
            }

            val suggested = ImportFilePolicy.suggestedDisplayName(document.name).ifBlank {
                metadata.friendlyName ?: metadata.profileName ?: "Imported profile"
            }.take(ProfileNameValidator.MAX_LENGTH)
            val name = ProfileNameValidator.validate(suggested).getOrElse { "Imported profile" }

            // Non-fatal security notes shown with the success result. The profile
            // is usable, but the user should know about weak ciphers or a changed
            // CA before trusting a same-named profile.
            val warnings = mutableListOf<String>()
            val legacyCiphers = ProfileSecurityPolicy.legacyCipherFindings(content)
                .map { it.value.uppercase() }
                .distinct()
            if (legacyCiphers.isNotEmpty()) {
                warnings += "This profile allows non-AEAD data ciphers " +
                    "(${legacyCiphers.joinToString(", ")}). Switch the cipher policy to the " +
                    "AEAD-only mode to refuse them."
            }
            if (ProfileTrustPolicy.hasIgnoredPeerFingerprint(content)) {
                warnings += "This profile contains a peer-fingerprint pin, but the VPN engine " +
                    "in this build cannot verify certificate fingerprints, so that pin is " +
                    "ignored. Server identity is verified through the profile's CA."
            }
            val newCaFingerprint = ProfileTrustPolicy.caFingerprint(content)
            if (newCaFingerprint != null) {
                val sameNamed = repository.profiles.value.filter { it.name == name }
                for (existingProfile in sameNamed) {
                    val stored = repository.get(existingProfile.id) ?: continue
                    val previousFingerprint = ProfileTrustPolicy.caFingerprint(stored.config) ?: continue
                    if (previousFingerprint != newCaFingerprint) {
                        warnings += CA_CHANGE_WARNING
                        break
                    }
                }
            }

            val now = System.currentTimeMillis()
            val summary = ProfileSummary(
                id = UUID.randomUUID().toString(),
                name = name,
                emoji = EmojiCatalog.defaultFor(repository.profiles.value.size),
                metadata = metadata,
                createdAt = now,
                updatedAt = now,
                contentHash = hash,
            )
            val stored = StoredProfile(summary = summary, config = content)
            repository.add(stored).getOrElse {
                return ImportOutcome.Failure(document.name, "Could not save the profile securely.")
            }
            ImportOutcome.Success(
                document.name,
                summary.id,
                alreadyImported = false,
                warning = warnings.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            )
        } catch (security: SecurityException) {
            ImportOutcome.Failure(document.name, "Access to the selected file was denied.")
        } catch (io: java.io.IOException) {
            ImportOutcome.Failure(document.name, "The selected file could not be read.")
        } catch (e: Exception) {
            ImportOutcome.Failure(
                document.name,
                LogSanitizer.sanitize(e.message ?: "Import failed."),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun copyBounded(uri: Uri, destination: File) {
        val input: InputStream = context.contentResolver.openInputStream(uri)
            ?: error("The selected file could not be opened.")
        input.use { source ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= ImportFilePolicy.MAX_FILE_BYTES) { "One of the selected files is too large." }
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    private fun displayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    } else {
                        null
                    }
                }
        }.getOrNull()
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class PickedDocument(val uri: Uri, val name: String)
}
