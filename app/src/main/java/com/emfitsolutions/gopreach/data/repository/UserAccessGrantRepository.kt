package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.UserAccessGrant
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.mirrorFirestoreCollection
import com.emfitsolutions.gopreach.di.ApplicationScope
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val COLLECTION = "userAccessGrants"

/**
 * Source of truth for [UserAccessGrant] rows — what [com.emfitsolutions.gopreach.domain.PermissionChecker.hasPermission]
 * runs against for any restricted (Circuit Overseer / custom) user, and what
 * firestore.rules reads directly (by personId) to enforce the same thing
 * server-side. **Always save with `grant.personId` as the document id** — both
 * the app and the rules depend on that 1:1 mapping to avoid a query.
 */
@Singleton
class UserAccessGrantRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    fun observeAll(): Flow<List<UserAccessGrant>> = offline.observeCollection(COLLECTION)

    fun observeForPerson(personId: String): Flow<UserAccessGrant?> =
        observeAll().map { list -> list.firstOrNull { it.personId == personId } }

    suspend fun get(personId: String): UserAccessGrant? = offline.get(COLLECTION, personId)

    suspend fun save(grant: UserAccessGrant) {
        require(grant.personId.isNotBlank()) { "UserAccessGrant.personId is required — it's also the document id." }
        offline.save(COLLECTION, grant.personId, grant)
    }

    suspend fun delete(personId: String) = offline.delete(COLLECTION, personId)

    fun startRemoteSync(): Flow<Unit> =
        mirrorFirestoreCollection(firestore, offline, appScope, COLLECTION, UserAccessGrant::class.java) { it.personId }
}
