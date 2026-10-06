package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.PublisherForwardRequest
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.mirrorFirestoreCollection
import com.emfitsolutions.gopreach.di.ApplicationScope
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

private const val COLLECTION = "publisherForwardRequests"

/** "FORWARD TO OTHER PUBLISHER" spec flow — top-level, app-wide mirrored
 * (same reasoning as [ForwardRequestRepository]): both the receiving
 * publisher's incoming queue and the sending publisher's own status view need
 * to see this regardless of which device/session created it. */
@Singleton
class PublisherForwardRequestRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    fun observeAll(): Flow<List<PublisherForwardRequest>> = offline.observeCollection(COLLECTION)

    suspend fun save(request: PublisherForwardRequest): PublisherForwardRequest {
        val id = request.id.ifBlank { firestore.collection(COLLECTION).document().id }
        val withId = request.copy(id = id)
        offline.save(COLLECTION, id, withId)
        return withId
    }

    /** Same Super-Admin cleanup/correction tool as [ForwardRequestRepository
     * .delete] — a hard delete, not a status change. */
    suspend fun delete(id: String) {
        offline.delete(COLLECTION, id)
    }

    fun startRemoteSync(): Flow<Unit> =
        mirrorFirestoreCollection(firestore, offline, appScope, COLLECTION, PublisherForwardRequest::class.java) { it.id }
}
