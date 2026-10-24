package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.Favorite
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val COLLECTION = "favorites"

/** Favorites of Searching / Return Visit / Bible Study records: saved on the device at once and synced, so the same person sees them on every device. */
class FavoriteRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    fun idFor(personId: String, recordId: String) = "${personId}_$recordId"

    /** The ids of the records [personId] has starred. The server shows a person only their own Favorites; this filter is a convenience. */
    fun observeRecordIds(personId: String): Flow<Set<String>> =
        offline.observeCollection<Favorite>(COLLECTION).map { all -> all.filter { it.publisherPersonId == personId }.map { it.recordId }.toSet() }

    /** Stars or un-stars [recordId]. Starring twice writes the same document, so there is never a duplicate. */
    suspend fun setFavorite(personId: String, recordId: String, stage: String, favorite: Boolean, nowMillis: Long) {
        val id = idFor(personId, recordId)
        if (favorite) offline.save(COLLECTION, id, Favorite(id, personId, recordId, stage, nowMillis)) else offline.delete(COLLECTION, id)
    }

    fun startRemoteSync(): Flow<Unit> = remote.mirror(COLLECTION, Favorite::class) { it.id }
}
