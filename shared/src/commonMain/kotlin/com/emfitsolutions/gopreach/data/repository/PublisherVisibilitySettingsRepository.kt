package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.PublisherVisibilitySettings
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.platform.nowMillis
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val COLLECTION = "publisherVisibilitySettings"

/** One document per congregation; a congregation without one uses [PublisherVisibilitySettings.defaultsFor]. */
class PublisherVisibilitySettingsRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    fun observeAll(): Flow<List<PublisherVisibilitySettings>> = offline.observeCollection(COLLECTION)

    fun observeFor(congregationId: String): Flow<PublisherVisibilitySettings> =
        observeAll().map { list -> list.firstOrNull { it.id == congregationId } ?: PublisherVisibilitySettings.defaultsFor(congregationId) }

    suspend fun save(settings: PublisherVisibilitySettings) =
        offline.save(COLLECTION, settings.id, settings.copy(updatedAt = nowMillis()))

    fun startRemoteSync(): Flow<Unit> =
        remote.mirror(COLLECTION, PublisherVisibilitySettings::class) { it.id }
}
