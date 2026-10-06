package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.SavedLocation
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val COLLECTION = "savedLocations"

/** Backs "Find Location"'s save-a-coordinate-with-remarks feature — see
 * [SavedLocation]'s doc comment. */
class SavedLocationRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    fun observeForPublisher(publisherPersonId: String): Flow<List<SavedLocation>> =
        offline.observeCollection<SavedLocation>(COLLECTION)
            .map { list -> list.filter { it.publisherPersonId == publisherPersonId }.sortedByDescending { it.createdAt } }

    suspend fun save(location: SavedLocation): SavedLocation {
        val id = location.id.ifBlank { remote.newId(COLLECTION) }
        val withId = location.copy(id = id)
        offline.save(COLLECTION, id, withId)
        return withId
    }

    suspend fun delete(id: String) = offline.delete(COLLECTION, id)

    fun startRemoteSync(): Flow<Unit> =
        remote.mirror(COLLECTION, SavedLocation::class) { it.id }
}
