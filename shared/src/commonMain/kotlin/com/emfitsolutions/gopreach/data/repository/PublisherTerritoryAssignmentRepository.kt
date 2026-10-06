package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.PublisherTerritoryAssignment
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.platform.nowMillis
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import kotlinx.coroutines.flow.Flow

private const val COLLECTION = "publisherTerritoryAssignments"

/** Territory Assignment → Per Publisher Assignment. Goes through the normal
 * offline-first queue like most repositories here — unlike the FS Group
 * assignments, nothing requires an atomic uniqueness check across admins. */
class PublisherTerritoryAssignmentRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    fun observeAll(): Flow<List<PublisherTerritoryAssignment>> = offline.observeCollection(COLLECTION)

    suspend fun save(assignment: PublisherTerritoryAssignment): PublisherTerritoryAssignment {
        val now = nowMillis()
        val id = assignment.id.ifBlank { remote.newId(COLLECTION) }
        val withId = assignment.copy(
            id = id,
            createdAt = if (assignment.createdAt == 0L) now else assignment.createdAt,
            updatedAt = now,
        )
        offline.save(COLLECTION, id, withId)
        return withId
    }

    suspend fun delete(assignmentId: String) = offline.delete(COLLECTION, assignmentId)

    fun startRemoteSync(): Flow<Unit> =
        remote.mirror(COLLECTION, PublisherTerritoryAssignment::class) { it.id }
}
