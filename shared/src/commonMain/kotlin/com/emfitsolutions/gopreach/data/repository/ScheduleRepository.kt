package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.Schedule
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import kotlinx.coroutines.flow.Flow

private const val COLLECTION = "schedules"

/** Backs Chat Schedule (spec §5.1/§3) and Calendar (spec §6.2) — both are
 * [Schedule] rows distinguished by [com.emfitsolutions.gopreach.data.model.ScheduleKind]. */
class ScheduleRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    fun observeAll(): Flow<List<Schedule>> = offline.observeCollection(COLLECTION)

    suspend fun save(schedule: Schedule): Schedule {
        val id = schedule.id.ifBlank { remote.newId(COLLECTION) }
        val withId = schedule.copy(id = id)
        offline.save(COLLECTION, id, withId)
        return withId
    }

    suspend fun delete(scheduleId: String) = offline.delete(COLLECTION, scheduleId)

    fun startRemoteSync(): Flow<Unit> =
        remote.mirror(COLLECTION, Schedule::class) { it.id }
}
