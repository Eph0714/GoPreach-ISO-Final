package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.PublisherTerritoryAssignment
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.mirrorFirestoreCollection
import com.emfitsolutions.gopreach.di.ApplicationScope
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

private const val COLLECTION = "publisherTerritoryAssignments"

/** Territory Assignment → Per Publisher Assignment. Goes through the normal
 * offline-first queue like most repositories here — unlike the FS Group
 * assignments, nothing requires an atomic uniqueness check across admins. */
@Singleton
class PublisherTerritoryAssignmentRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    fun observeAll(): Flow<List<PublisherTerritoryAssignment>> = offline.observeCollection(COLLECTION)

    suspend fun save(assignment: PublisherTerritoryAssignment): PublisherTerritoryAssignment {
        val now = System.currentTimeMillis()
        val id = assignment.id.ifBlank { firestore.collection(COLLECTION).document().id }
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
        mirrorFirestoreCollection(firestore, offline, appScope, COLLECTION, PublisherTerritoryAssignment::class.java) { it.id }
}
