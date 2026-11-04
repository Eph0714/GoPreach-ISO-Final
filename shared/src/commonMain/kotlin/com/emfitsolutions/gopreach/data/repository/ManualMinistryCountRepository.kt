package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.ManualMinistryCount
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val COLLECTION = "manualMinistryCounts"

/** Typed Return Visit / Bible Study quantities of one Publisher (see [ManualMinistryCount]); same offline-first path and month lock as Credit Hours. */
class ManualMinistryCountRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
    private val monthLock: MonthLockGuard,
) {
    fun observeForPublisher(publisherPersonId: String): Flow<List<ManualMinistryCount>> =
        offline.observeCollection<ManualMinistryCount>(COLLECTION).map { list -> list.filter { it.publisherPersonId == publisherPersonId } }

    suspend fun save(record: ManualMinistryCount): ManualMinistryCount {
        monthLock.requireOpenForPublisher(record.publisherPersonId, record.dayStart) // a month submitted to the Circuit Overseer is locked
        val id = record.id.ifBlank { RecentCreates.idFor(COLLECTION, record) { remote.newId(COLLECTION) } }
        val withId = record.copy(id = id)
        offline.save(COLLECTION, id, withId)
        return withId
    }

    suspend fun delete(recordId: String) {
        offline.observeCollection<ManualMinistryCount>(COLLECTION).first().firstOrNull { it.id == recordId }
            ?.let { monthLock.requireOpenForPublisher(it.publisherPersonId, it.dayStart) }
        offline.delete(COLLECTION, recordId)
    }

    suspend fun pullOnce(publisherPersonId: String) =
        remote.pullOnce(COLLECTION, ManualMinistryCount::class, equalTo = "publisherPersonId" to publisherPersonId) { it.id }
}
