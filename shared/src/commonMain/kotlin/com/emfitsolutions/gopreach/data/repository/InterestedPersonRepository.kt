package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.PipelineStage
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
    private val monthLock: MonthLockGuard,
) {
    fun observeAll(): Flow<List<InterestedPerson>> = offline.observeCollection(COLLECTION)

    suspend fun save(person: InterestedPerson): InterestedPerson {
        // A new Return Visit / Bible Study (or a record moved into one) is not allowed once this month's Field Service Report has been
        // sent to the Circuit Overseer. Editing an existing record is unaffected; its visit entries have their own month lock.
        if (person.pipelineStage == PipelineStage.RETURN_VISIT || person.pipelineStage == PipelineStage.BIBLE_STUDY) {
            val before = if (person.id.isBlank()) null else offline.get<InterestedPerson>(COLLECTION, person.id)
            if (before == null || before.pipelineStage != person.pipelineStage) monthLock.requireOpen(person.congregationId, nowMillis())
        }
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
