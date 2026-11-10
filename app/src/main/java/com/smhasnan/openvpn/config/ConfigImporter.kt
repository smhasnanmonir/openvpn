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

            val hash = sha256(content)
            repository.findByHash(hash)?.let { existing ->
                repository.select(existing.id)
                return ImportOutcome.Success(document.name, existing.id, alreadyImported = true)
            }

            val suggested = ImportFilePolicy.suggestedDisplayName(document.name).ifBlank {
                metadata.friendlyName ?: metadata.profileName ?: "Imported profile"
            }.take(ProfileNameValidator.MAX_LENGTH)
            val name = ProfileNameValidator.validate(suggested).getOrElse { "Imported profile" }
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
            ImportOutcome.Success(document.name, summary.id, alreadyImported = false)
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
