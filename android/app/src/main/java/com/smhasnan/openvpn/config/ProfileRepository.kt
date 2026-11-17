package com.smhasnan.openvpn.config

import com.smhasnan.openvpn.settings.SettingsRepository
import com.smhasnan.openvpn.vpn.LogLevel
import com.smhasnan.openvpn.vpn.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single owner of profile/credential persistence and profile selection.
 * UI receives immutable summaries only; secret profile content never enters UI
 * state.
 *
 * Memory: only [ProfileSummary] instances stay resident. At most one decrypted
 * [StoredProfile] (the most recently loaded body) is cached; every other config
 * string is decrypted from encrypted storage on demand, so the repository never
 * holds the full decrypted config of every stored profile.
 */
class ProfileRepository(
    private val store: ProfileStore,
    private val credentialStore: CredentialStore,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()

    private val _profiles = MutableStateFlow<List<ProfileSummary>>(emptyList())
    val profiles: StateFlow<List<ProfileSummary>> = _profiles.asStateFlow()

    private val _selectedId = MutableStateFlow(settings.selectedProfileId.value)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    /** Known summaries by id. Guarded by [mutex]; replaced wholesale, so a
     *  volatile read of the immutable map is safe outside the lock. */
    @Volatile
    private var summaries: Map<String, ProfileSummary> = emptyMap()

    /** At most one decrypted profile body. Guarded by [mutex]. */
    private var cachedProfile: StoredProfile? = null

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val result = store.loadAll()
            result.errors.forEach { VpnController.appendLog(LogLevel.WARN, it) }
            // Keep summaries only; the decrypted configs produced by loadAll are
            // released as soon as this scope exits.
            summaries = result.profiles.associate { it.summary.id to it.summary }
            cachedProfile = null
            _profiles.value = summaries.values.sortedByDescending { it.updatedAt }
            reconcileSelection()
        }
    }

    fun selectedProfile(): ProfileSummary? =
        _profiles.value.firstOrNull { it.id == _selectedId.value }

    fun summary(id: String): ProfileSummary? = summaries[id]

    suspend fun get(id: String): StoredProfile? = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked(id) }
    }

    suspend fun add(profile: StoredProfile): Result<ProfileSummary> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                store.save(profile)
                summaries = summaries + (profile.summary.id to profile.summary)
                cachedProfile = profile
                _profiles.value = summaries.values.sortedByDescending { it.updatedAt }
                if (_selectedId.value == null || summaries[_selectedId.value] == null) {
                    selectLocked(profile.summary.id)
                }
                profile.summary
            }
        }
    }

    suspend fun findByHash(hash: String): ProfileSummary? = withContext(Dispatchers.IO) {
        mutex.withLock { findByHashLocked(hash) }
    }

    suspend fun rename(id: String, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val existing = loadLocked(id) ?: error("Profile not found")
                val updated = existing.copy(
                    summary = existing.summary.copy(
                        name = newName,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                store.save(updated)
                cachedProfile = updated
                summaries = summaries + (id to updated.summary)
                _profiles.value = _profiles.value.map {
                    if (it.id == id) updated.summary else it
                }.sortedByDescending { it.updatedAt }
            }
        }
    }

    suspend fun setEmoji(id: String, emoji: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val existing = loadLocked(id) ?: error("Profile not found")
                val updated = existing.copy(
                    summary = existing.summary.copy(
                        emoji = EmojiCatalog.normalized(emoji),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                store.save(updated)
                cachedProfile = updated
                summaries = summaries + (id to updated.summary)
                _profiles.value = _profiles.value.map { if (it.id == id) updated.summary else it }
            }
        }
    }

    suspend fun delete(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                store.delete(id)
                credentialStore.delete(id)
                summaries = summaries - id
                if (cachedProfile?.summary?.id == id) cachedProfile = null
                _profiles.value = _profiles.value.filterNot { it.id == id }
                if (_selectedId.value == id) {
                    selectLocked(_profiles.value.firstOrNull()?.id)
                }
            }
        }
    }

    suspend fun select(id: String?) = withContext(Dispatchers.IO) {
        mutex.withLock { selectLocked(id) }
    }

    suspend fun saveCredentials(id: String, credentials: ProfileCredentials): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (credentials.isEmpty) credentialStore.delete(id) else credentialStore.save(id, credentials)
            }
        }

    suspend fun loadCredentials(id: String): ProfileCredentials? = withContext(Dispatchers.IO) {
        credentialStore.load(id)
    }

    suspend fun deleteCredentials(id: String) = withContext(Dispatchers.IO) {
        credentialStore.delete(id)
    }

    /**
     * Returns the decrypted body for [id], reusing the single cached profile when
     * it matches, otherwise decrypting that one file. Caller holds [mutex].
     */
    private fun loadLocked(id: String): StoredProfile? {
        val cached = cachedProfile
        if (cached?.summary?.id == id) return cached
        return store.load(id)?.also { cachedProfile = it }
    }

    private fun findByHashLocked(hash: String): ProfileSummary? {
        summaries.values.firstOrNull { it.contentHash == hash }?.let { return it }
        // Before the first refresh the summary index is empty even though blobs
        // may exist; scan once in that cold case so a duplicate import is still
        // detected. In steady state this never decrypts the whole store.
        if (summaries.isNotEmpty()) return null
        return store.loadAll().profiles.firstOrNull { it.summary.contentHash == hash }?.summary
    }

    private fun selectLocked(id: String?) {
        val valid = id?.takeIf { candidate -> summaries.containsKey(candidate) }
            ?: _profiles.value.firstOrNull()?.id
        _selectedId.value = valid
        settings.setSelectedProfile(valid)
    }

    private fun reconcileSelection() {
        val selected = _selectedId.value
        if (selected != null && !summaries.containsKey(selected)) {
            selectLocked(_profiles.value.firstOrNull()?.id)
        } else if (selected == null && _profiles.value.isNotEmpty()) {
            selectLocked(_profiles.value.first().id)
        }
    }
}
