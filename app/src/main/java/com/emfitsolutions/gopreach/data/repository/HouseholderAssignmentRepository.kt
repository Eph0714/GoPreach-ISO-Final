package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.HouseholderAssignment
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.mirrorFirestoreCollection
import com.emfitsolutions.gopreach.di.ApplicationScope
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

private const val COLLECTION = "houseHolderAssignments"

/** "House Holder Assignment" module — top-level, app-wide mirrored (same
 * convention [ForwardRequestRepository] uses, for the same reason): a Super-
 * Admin's own cross-congregation visibility and the receiving Publisher's
 * device both need to see the exact same assignment regardless of which
 * device created it. */
@Singleton
class HouseholderAssignmentRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    fun observeAll(): Flow<List<HouseholderAssignment>> = offline.observeCollection(COLLECTION)

    suspend fun save(assignment: HouseholderAssignment): HouseholderAssignment {
        val id = assignment.id.ifBlank { firestore.collection(COLLECTION).document().id }
        val withId = assignment.copy(id = id)
        offline.save(COLLECTION, id, withId)
        return withId
    }

    /** Super-Admin cleanup/correction tool, mirroring
     * [ForwardRequestRepository.delete] — a hard delete, never part of the
     * normal Pending -> Accepted/Rejected/Cancelled/Completed lifecycle. */
    suspend fun delete(id: String) {
        offline.delete(COLLECTION, id)
    }

    fun startRemoteSync(): Flow<Unit> =
        mirrorFirestoreCollection(firestore, offline, appScope, COLLECTION, HouseholderAssignment::class.java) { it.id }
}
