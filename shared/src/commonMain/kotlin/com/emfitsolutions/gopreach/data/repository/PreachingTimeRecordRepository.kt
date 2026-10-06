package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.PreachingTimeRecord
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val COLLECTION = "preachingTimeRecords"

/** "Preaching Time Record Module" spec §12 — Pioneer-only per-day preaching
 * time log; see [PreachingTimeRecord]'s own doc comment for why this is a
 * separate module from [MonthlyReport.hoursRendered]. Same top-level-
 * collection, offline-first, mirrored-everywhere shape as
 * [BibleStudyRepository] — no subcollection complexity, unlike
 * [VisitRepository]. */
class PreachingTimeRecordRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    fun observeForPublisher(publisherPersonId: String): Flow<List<PreachingTimeRecord>> =
        observeAll().map { list -> list.filter { it.publisherPersonId == publisherPersonId } }

    /** "Consolidated Monthly Report" — every publisher's Preaching Time
     * records at once, for the multi-publisher congregation view. */
    fun observeAll(): Flow<List<PreachingTimeRecord>> = offline.observeCollection(COLLECTION)

    suspend fun save(record: PreachingTimeRecord): PreachingTimeRecord {
        val id = record.id.ifBlank { remote.newId(COLLECTION) }
        val withId = record.copy(id = id)
        offline.save(COLLECTION, id, withId)
        return withId
    }

    /** "Move to Inactive" — see [PreachingTimeRecord.status]. */
    suspend fun setStatus(record: PreachingTimeRecord, status: com.emfitsolutions.gopreach.data.model.RecordStatus) =
        offline.save(COLLECTION, record.id, record.copy(status = status))

    suspend fun permanentlyDelete(recordId: String) = offline.delete(COLLECTION, recordId)

    fun startRemoteSync(): Flow<Unit> =
        remote.mirror(COLLECTION, PreachingTimeRecord::class) { it.id }
}
