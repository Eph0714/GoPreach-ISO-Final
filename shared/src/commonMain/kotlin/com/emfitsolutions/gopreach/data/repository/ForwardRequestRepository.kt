package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.ForwardRequest
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import kotlinx.coroutines.flow.Flow

private const val COLLECTION = "forwardRequests"

/** "Forward to Other Congregation" spec flow — top-level, app-wide mirrored
 * (like [InterestedPersonRepository]) since a pending request must be visible
 * to a Service Overseer in a *different* congregation than the one that
 * created it, and the sending publisher needs to see its status update from
 * their own device too. */
class ForwardRequestRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    fun observeAll(): Flow<List<ForwardRequest>> = offline.observeCollection(COLLECTION)

    suspend fun save(request: ForwardRequest): ForwardRequest {
        val id = request.id.ifBlank { remote.newId(COLLECTION) }
        val withId = request.copy(id = id)
        offline.save(COLLECTION, id, withId)
        return withId
    }

    /** "Forward Request Module" — Super-Admin cleanup/correction tool (see
     * [com.emfitsolutions.gopreach.ui.screens.pipeline.ForwardRequestModuleScreen]).
     * A hard delete, not a status change — for permanently removing a
     * request record (typically stale test data or a genuine mistake), not
     * part of the normal Accept/Decline/Cancel lifecycle. */
    suspend fun delete(id: String) {
        offline.delete(COLLECTION, id)
    }

    fun startRemoteSync(): Flow<Unit> =
        remote.mirror(COLLECTION, ForwardRequest::class) { it.id }
}
