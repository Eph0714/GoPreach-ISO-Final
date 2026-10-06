package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.mirrorFirestoreCollection
import com.emfitsolutions.gopreach.di.ApplicationScope
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val COLLECTION = "roleAssignments"

/**
 * Source of truth for [RoleAssignment] rows — what [com.emfitsolutions.gopreach.domain.PermissionChecker]
 * runs against. Reads come from the local cache (offline-safe); a live Firestore
 * listener keeps that cache current whenever the app is online.
 */
@Singleton
class RoleAssignmentRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    /** Every RoleAssignment in the cache, any person, any status — for admin-facing
     * list screens (Manage Admins, Manage Elders, ...) that need to join across
     * people rather than look up a single one. */
    fun observeAll(): Flow<List<RoleAssignment>> = offline.observeCollection(COLLECTION)

    /** All RoleAssignments (any status) for one person, offline-first. */
    fun observeForPerson(personId: String): Flow<List<RoleAssignment>> =
        observeAll().map { list -> list.filter { it.personId == personId } }

    suspend fun save(assignment: RoleAssignment) {
        val id = assignment.id.ifBlank { firestore.collection(COLLECTION).document().id }
        offline.save(COLLECTION, id, assignment.copy(id = id))
    }

    /** Same as [save], but also pushes straight to Firestore immediately —
     * see [OfflineFirestoreRepository.saveNow] for why this exists
     * ([com.emfitsolutions.gopreach.data.repository.AuthRepository
     * .createAccountWithTempCredentials] is its only caller). Returns the
     * resolved assignment (with its generated id, if it didn't already have
     * one) since the caller needs it right away, unlike [save]'s callers. */
    suspend fun saveNow(assignment: RoleAssignment): RoleAssignment {
        val id = assignment.id.ifBlank { firestore.collection(COLLECTION).document().id }
        val withId = assignment.copy(id = id)
        offline.saveNow(firestore, COLLECTION, id, withId)
        return withId
    }

    suspend fun delete(assignmentId: String) {
        offline.delete(COLLECTION, assignmentId)
    }

    /** Attaches a live Firestore listener that mirrors server-side RoleAssignment
     * changes into the local cache. Call once per app session (e.g. from a
     * long-lived scope tied to the signed-in user). */
    fun startRemoteSync(): Flow<Unit> =
        mirrorFirestoreCollection(firestore, offline, appScope, COLLECTION, RoleAssignment::class.java) { it.id }
}
