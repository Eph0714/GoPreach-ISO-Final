package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.CartAssignmentRow
import com.emfitsolutions.gopreach.data.model.MidweekMeetingSchedule
import com.emfitsolutions.gopreach.data.model.PublicTalkScheduleRow
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.mirrorFirestoreCollection
import com.emfitsolutions.gopreach.di.ApplicationScope
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

private const val MIDWEEK_COLLECTION = "midweekMeetingSchedules"

/** "Meeting Assignments" module — Midweek Meeting Schedule half. */
@Singleton
class MidweekMeetingScheduleRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    fun observeAll(): Flow<List<MidweekMeetingSchedule>> = offline.observeCollection(MIDWEEK_COLLECTION)

    suspend fun save(schedule: MidweekMeetingSchedule): MidweekMeetingSchedule {
        val id = schedule.id.ifBlank { firestore.collection(MIDWEEK_COLLECTION).document().id }
        val withId = schedule.copy(id = id)
        offline.save(MIDWEEK_COLLECTION, id, withId)
        return withId
    }

    fun startRemoteSync(): Flow<Unit> =
        mirrorFirestoreCollection(firestore, offline, appScope, MIDWEEK_COLLECTION, MidweekMeetingSchedule::class.java) { it.id }
}

private const val PUBLIC_TALK_COLLECTION = "publicTalkSchedules"

/** "Meeting Assignments" module — Public Talk and Watchtower Study Schedule
 * half. */
@Singleton
class PublicTalkScheduleRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    fun observeAll(): Flow<List<PublicTalkScheduleRow>> = offline.observeCollection(PUBLIC_TALK_COLLECTION)

    suspend fun save(row: PublicTalkScheduleRow): PublicTalkScheduleRow {
        val id = row.id.ifBlank { firestore.collection(PUBLIC_TALK_COLLECTION).document().id }
        val withId = row.copy(id = id)
        offline.save(PUBLIC_TALK_COLLECTION, id, withId)
        return withId
    }

    suspend fun delete(rowId: String) = offline.delete(PUBLIC_TALK_COLLECTION, rowId)

    fun startRemoteSync(): Flow<Unit> =
        mirrorFirestoreCollection(firestore, offline, appScope, PUBLIC_TALK_COLLECTION, PublicTalkScheduleRow::class.java) { it.id }
}

private const val CART_ASSIGNMENT_COLLECTION = "cartAssignments"

/** "Meeting and Cart Assignment" module — Cart Assignment half (Date/
 * Location/Publishers, multiple rows per date allowed — see
 * [CartAssignmentRow]'s own doc comment). Same shape as [PublicTalkScheduleRepository],
 * minus the duplicate-date rule, which lives in the ViewModel layer, not
 * here, for both of these row types. */
@Singleton
class CartAssignmentRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    fun observeAll(): Flow<List<CartAssignmentRow>> = offline.observeCollection(CART_ASSIGNMENT_COLLECTION)

    suspend fun save(row: CartAssignmentRow): CartAssignmentRow {
        val id = row.id.ifBlank { firestore.collection(CART_ASSIGNMENT_COLLECTION).document().id }
        val withId = row.copy(id = id)
        offline.save(CART_ASSIGNMENT_COLLECTION, id, withId)
        return withId
    }

    suspend fun delete(rowId: String) = offline.delete(CART_ASSIGNMENT_COLLECTION, rowId)

    fun startRemoteSync(): Flow<Unit> =
        mirrorFirestoreCollection(firestore, offline, appScope, CART_ASSIGNMENT_COLLECTION, CartAssignmentRow::class.java) { it.id }
}
