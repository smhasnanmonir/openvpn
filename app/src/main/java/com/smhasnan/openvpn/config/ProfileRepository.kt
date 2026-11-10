package com.smhasnan.openvpn.config

import com.smhasnan.openvpn.settings.SettingsRepository
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
 */
class ProfileRepository(
    private val store: ProfileStore,
    private val credentialStore: CredentialStore,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()
    private var cache: Map<String, StoredProfile> = emptyMap()

    private val _profiles = MutableStateFlow<List<ProfileSummary>>(emptyList())
    val profiles: StateFlow<List<ProfileSummary>> = _profiles.asStateFlow()

    private val _selectedId = MutableStateFlow(settings.selectedProfileId.value)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val result = store.loadAll()
            result.errors.forEach { VpnController.appendLog(com.smhasnan.openvpn.vpn.LogLevel.WARN, it) }
            cache = result.profiles.associateBy { it.summary.id }
            _profiles.value = result.profiles.map { it.summary }
            reconcileSelection()
        }
    }

    fun selectedProfile(): ProfileSummary? =
        _profiles.value.firstOrNull { it.id == _selectedId.value }

    fun summary(id: String): ProfileSummary? = cache[id]?.summary

    suspend fun get(id: String): StoredProfile? = withContext(Dispatchers.IO) {
        mutex.withLock {
            cache[id] ?: store.loadAll().profiles.firstOrNull { it.summary.id == id }?.also { found ->
                cache = cache + (id to found)
            }
        }
    }

    suspend fun add(profile: StoredProfile): Result<ProfileSummary> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                store.save(profile)
                cache = cache + (profile.summary.id to profile)
                _profiles.value = (cache.values.map { it.summary })
                    .sortedByDescending { it.updatedAt }
                if (_selectedId.value == null || cache[_selectedId.value] == null) {
                    selectLocked(profile.summary.id)
                }
                profile.summary
            }
        }
    }

    suspend fun findByHash(hash: String): ProfileSummary? = withContext(Dispatchers.IO) {
        mutex.withLock {
            cache.values.firstOrNull { it.summary.contentHash == hash }?.summary
                ?: store.loadAll().profiles.firstOrNull { it.summary.contentHash == hash }?.summary
        }
    }

    suspend fun rename(id: String, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val existing = cache[id] ?: store.loadAll().profiles.firstOrNull { it.summary.id == id }
                    ?: error("Profile not found")
                val updated = existing.copy(
                    summary = existing.summary.copy(
                        name = newName,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                store.save(updated)
                cache = cache + (id to updated)
                _profiles.value = _profiles.value.map {
                    if (it.id == id) updated.summary else it
                }.sortedByDescending { it.updatedAt }
            }
        }
    }

    suspend fun setEmoji(id: String, emoji: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val existing = cache[id] ?: store.loadAll().profiles.firstOrNull { it.summary.id == id }
                    ?: error("Profile not found")
                val updated = existing.copy(
                    summary = existing.summary.copy(
                        emoji = EmojiCatalog.normalized(emoji),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                store.save(updated)
                cache = cache + (id to updated)
                _profiles.value = _profiles.value.map { if (it.id == id) updated.summary else it }
            }
        }
    }

    suspend fun delete(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                store.delete(id)
                credentialStore.delete(id)
                cache = cache - id
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

    private fun selectLocked(id: String?) {
        val valid = id?.takeIf { candidate -> cache.containsKey(candidate) }
            ?: _profiles.value.firstOrNull()?.id
        _selectedId.value = valid
        settings.setSelectedProfile(valid)
    }

    private fun reconcileSelection() {
        val selected = _selectedId.value
        if (selected != null && !cache.containsKey(selected)) {
            selectLocked(_profiles.value.firstOrNull()?.id)
        } else if (selected == null && _profiles.value.isNotEmpty()) {
            selectLocked(_profiles.value.first().id)
        }
    }
}
