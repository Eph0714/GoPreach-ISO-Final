package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.platform.nowMillis
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import kotlinx.coroutines.flow.Flow

private const val COLLECTION = "interestedPeople"

/** Spec §6.3 — publisher-managed Interested People records; also feeds the
 * Admin-side "Total Interested People visited" report (spec §5.1). Visit sub-
 * records ([com.emfitsolutions.gopreach.data.model.Visit]) get their own
 * repository alongside the Interested People CRUD screens in Phase 5. */
class InterestedPersonRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    fun observeAll(): Flow<List<InterestedPerson>> = offline.observeCollection(COLLECTION)

    suspend fun save(person: InterestedPerson): InterestedPerson {
        val id = person.id.ifBlank { remote.newId(COLLECTION) }
        val withId = person.copy(id = id, updatedAt = nowMillis())
        offline.save(COLLECTION, id, withId)
        return withId
    }

    suspend fun delete(personId: String) = offline.delete(COLLECTION, personId)

    fun startRemoteSync(): Flow<Unit> =
        remote.mirror(COLLECTION, InterestedPerson::class) { it.id }

    suspend fun pullOnce() = remote.pullOnce(COLLECTION, InterestedPerson::class) { it.id }
}
