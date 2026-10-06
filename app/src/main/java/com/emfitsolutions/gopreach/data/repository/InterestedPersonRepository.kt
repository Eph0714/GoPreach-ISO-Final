package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.mirrorFirestoreCollection
import com.emfitsolutions.gopreach.data.sync.pullFirestoreCollectionOnce
import com.emfitsolutions.gopreach.di.ApplicationScope
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

private const val COLLECTION = "interestedPeople"

/** Spec §6.3 — publisher-managed Interested People records; also feeds the
 * Admin-side "Total Interested People visited" report (spec §5.1). Visit sub-
 * records ([com.emfitsolutions.gopreach.data.model.Visit]) get their own
 * repository alongside the Interested People CRUD screens in Phase 5. */
@Singleton
class InterestedPersonRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    fun observeAll(): Flow<List<InterestedPerson>> = offline.observeCollection(COLLECTION)

    suspend fun save(person: InterestedPerson): InterestedPerson {
        val id = person.id.ifBlank { firestore.collection(COLLECTION).document().id }
        val withId = person.copy(id = id, updatedAt = System.currentTimeMillis())
        offline.save(COLLECTION, id, withId)
        return withId
    }

    suspend fun delete(personId: String) = offline.delete(COLLECTION, personId)

    fun startRemoteSync(): Flow<Unit> =
        mirrorFirestoreCollection(firestore, offline, appScope, COLLECTION, InterestedPerson::class.java) { it.id }

    suspend fun pullOnce() = pullFirestoreCollectionOnce(firestore, offline, COLLECTION, InterestedPerson::class.java) { it.id }
}
