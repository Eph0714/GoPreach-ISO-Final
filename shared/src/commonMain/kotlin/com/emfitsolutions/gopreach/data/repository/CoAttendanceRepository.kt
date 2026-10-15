package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.CoAttendanceSnapshot
import com.emfitsolutions.gopreach.data.model.CoMonthStatus
import com.emfitsolutions.gopreach.data.model.CoReportEvent
import com.emfitsolutions.gopreach.data.model.CoReportRead
import com.emfitsolutions.gopreach.platform.nowMillis
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Meeting Attendance report workflow, the twin of the Field Service Report one: a status per congregation and month, the frozen
 * copy of every send, and an append-only audit trail. All three are read from the device (kept current by the pull sync) and changed
 * only through [CoAttendanceService], which the server re-checks.
 */
class CoAttendanceRepository(private val offline: OfflineFirestoreRepository) {
    fun observeStatuses(): Flow<List<CoMonthStatus>> = offline.observeCollection(STATUSES)
    fun observeSnapshots(): Flow<List<CoAttendanceSnapshot>> = offline.observeCollection(SNAPSHOTS)
    fun observeEvents(): Flow<List<CoReportEvent>> = offline.observeCollection<CoReportEvent>(EVENTS).map { it.sortedBy { e -> e.at } }

    /** The ids of the attendance snapshots [coPersonId] has opened (saved on the server, so it stays read after sign-out or on another phone). */
    fun observeReadIds(coPersonId: String): Flow<Set<String>> =
        offline.observeCollection<CoReportRead>(READS).map { list -> list.filter { it.coPersonId == coPersonId }.map { it.receivedReportId }.toSet() }

    /** Marks [snapshot] as read by [coPersonId] - idempotent, synchronized like any other change. */
    suspend fun markRead(coPersonId: String, snapshot: CoAttendanceSnapshot) {
        val id = "${coPersonId}_${snapshot.id}"
        offline.save(READS, id, CoReportRead(id = id, coPersonId = coPersonId, receivedReportId = snapshot.id, congregationId = snapshot.congregationId, periodMonth = snapshot.periodMonth, readAt = nowMillis()))
    }

    companion object {
        const val STATUSES = "coAttendanceMonthStatus"
        const val SNAPSHOTS = "coAttendanceReports"
        const val EVENTS = "coAttendanceReportEvents"
        const val READS = "coAttendanceReads"
    }
}

/** Every move of a congregation's Meeting Attendance month; the server checks each one against the month's current state and role. */
interface CoAttendanceService {
    /** NOT_SUBMITTED / RETURNED / CANCELLED / SUBMITTED (an update) -> SUBMITTED, with the frozen [snapshot] of that send. */
    suspend fun submit(congregationId: String, periodMonth: Long, actor: SubmissionActor, snapshot: CoAttendanceSnapshot, expectedVersion: Int? = null): CircuitResult

    /** SUBMITTED -> NOT_SUBMITTED, before the overseer has received it. */
    suspend fun undoSubmission(congregationId: String, periodMonth: Long, actor: SubmissionActor): CircuitResult

    /** Circuit Overseer: SUBMITTED -> RECEIVED (locks the month's attendance). */
    suspend fun receive(reportId: String, remarks: String?, actor: SubmissionActor, expectedVersion: Int? = null): CircuitResult

    /** Circuit Overseer: SUBMITTED / RECEIVED -> RETURNED, with a required reason (unlocks the month). */
    suspend fun returnForCorrection(reportId: String, reason: String, actor: SubmissionActor, expectedVersion: Int? = null): CircuitResult

    /** Circuit Overseer: SUBMITTED / RECEIVED -> CANCELLED, with a required reason; a received month stays locked. */
    suspend fun cancelSubmission(reportId: String, reason: String, actor: SubmissionActor, expectedVersion: Int? = null): CircuitResult
}
