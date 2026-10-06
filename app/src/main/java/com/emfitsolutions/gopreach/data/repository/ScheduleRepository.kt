package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.Schedule
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.mirrorFirestoreCollection
import com.emfitsolutions.gopreach.di.ApplicationScope
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

private const val COLLECTION = "schedules"

/** Backs Chat Schedule (spec §5.1/§3) and Calendar (spec §6.2) — both are
 * [Schedule] rows distinguished by [com.emfitsolutions.gopreach.data.model.ScheduleKind]. */
@Singleton
class ScheduleRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    fun observeAll(): Flow<List<Schedule>> = offline.observeCollection(COLLECTION)

    suspend fun save(schedule: Schedule): Schedule {
        val id = schedule.id.ifBlank { firestore.collection(COLLECTION).document().id }
        val withId = schedule.copy(id = id)
        offline.save(COLLECTION, id, withId)
        return withId
    }

    suspend fun delete(scheduleId: String) = offline.delete(COLLECTION, scheduleId)

    fun startRemoteSync(): Flow<Unit> =
        mirrorFirestoreCollection(firestore, offline, appScope, COLLECTION, Schedule::class.java) { it.id }
}
