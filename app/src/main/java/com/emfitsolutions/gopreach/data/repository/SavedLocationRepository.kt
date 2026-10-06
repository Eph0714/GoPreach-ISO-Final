package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.SavedLocation
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.mirrorFirestoreCollection
import com.emfitsolutions.gopreach.di.ApplicationScope
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val COLLECTION = "savedLocations"

/** Backs "Find Location"'s save-a-coordinate-with-remarks feature — see
 * [SavedLocation]'s doc comment. */
@Singleton
class SavedLocationRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    fun observeForPublisher(publisherPersonId: String): Flow<List<SavedLocation>> =
        offline.observeCollection<SavedLocation>(COLLECTION)
            .map { list -> list.filter { it.publisherPersonId == publisherPersonId }.sortedByDescending { it.createdAt } }

    suspend fun save(location: SavedLocation): SavedLocation {
        val id = location.id.ifBlank { firestore.collection(COLLECTION).document().id }
        val withId = location.copy(id = id)
        offline.save(COLLECTION, id, withId)
        return withId
    }

    suspend fun delete(id: String) = offline.delete(COLLECTION, id)

    fun startRemoteSync(): Flow<Unit> =
        mirrorFirestoreCollection(firestore, offline, appScope, COLLECTION, SavedLocation::class.java) { it.id }
}
