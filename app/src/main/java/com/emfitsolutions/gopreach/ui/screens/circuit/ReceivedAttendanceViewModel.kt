package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.model.CoAttendanceSnapshot
import com.emfitsolutions.gopreach.data.model.CoMonthStatus
import com.emfitsolutions.gopreach.data.model.CoReportEvent
import com.emfitsolutions.gopreach.data.model.coReportId
import com.emfitsolutions.gopreach.data.repository.CircuitResult
import com.emfitsolutions.gopreach.data.repository.CoAttendanceRepository
import com.emfitsolutions.gopreach.data.repository.CoAttendanceService
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.SubmissionActor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** One service month of the selected congregation in the Received Attendance list: its status and the snapshot of the CURRENT version. */
data class ReceivedAttendanceMonth(val status: CoMonthStatus, val snapshot: CoAttendanceSnapshot?, val unread: Int = 0) {
    val periodMonth: Long get() = status.periodMonth
}

/**
 * The Circuit Overseer's Received Reports -> Circuit Attendance Report: the attendance months the congregations sent, read only from the
 * frozen snapshots (never the congregation's working records), and the moves on them. Every action is made against the exact version the
 * overseer reviewed; a newer send makes it refuse. A month the congregation took back (undo) is not listed.
 */
class ReceivedAttendanceViewModel(
    private val workflow: CoAttendanceRepository,
    private val service: CoAttendanceService,
    private val persons: PersonRepository,
) : ViewModel() {

    fun months(congregationId: String, coPersonId: String): Flow<List<ReceivedAttendanceMonth>> =
        combine(workflow.observeStatuses(), workflow.observeSnapshots(), workflow.observeReadIds(coPersonId)) { st, copies, read ->
            st.filter { it.congregationId == congregationId && it.status.visibleToCircuitOverseer }.sortedByDescending { it.periodMonth }.map { s ->
                val sends = copies.filter { it.congregationId == congregationId && it.periodMonth == s.periodMonth }
                ReceivedAttendanceMonth(s, sends.firstOrNull { it.version == s.version } ?: sends.maxByOrNull { it.version }, unread = sends.count { it.id !in read })
            }
        }

    /** Every unopened attendance snapshot of a month that is currently sent (a month the congregation took back is not counted), newest first. */
    fun unread(coPersonId: String): Flow<List<CoAttendanceSnapshot>> =
        combine(workflow.observeSnapshots(), workflow.observeReadIds(coPersonId), workflow.observeStatuses()) { all, read, st ->
            val live = st.filter { it.status.visibleToCircuitOverseer }.map { it.congregationId to it.periodMonth }.toSet()
            all.filter { it.id !in read && (it.congregationId to it.periodMonth) in live }.sortedByDescending { it.submittedAt }
        }

    suspend fun markRead(coPersonId: String, snapshot: CoAttendanceSnapshot) = workflow.markRead(coPersonId, snapshot)

    /** Every send of [month] (the newest first). */
    fun versions(congregationId: String, month: Long): Flow<List<CoAttendanceSnapshot>> =
        workflow.observeSnapshots().map { all -> all.filter { it.congregationId == congregationId && it.periodMonth == month }.sortedByDescending { it.version } }

    fun status(congregationId: String, month: Long): Flow<CoMonthStatus?> =
        workflow.observeStatuses().map { list -> list.firstOrNull { it.id == coReportId(congregationId, month) } }

    fun history(congregationId: String, month: Long): Flow<List<CoReportEvent>> =
        workflow.observeEvents().map { list -> list.filter { it.reportId == coReportId(congregationId, month) } }

    private suspend fun actorFor(personId: String): SubmissionActor {
        val person = persons.get(personId)
        return SubmissionActor(personId, person?.fullName ?: personId, if (person?.isSuperAdmin == true) "SUPER_ADMIN" else person?.activeAdminRole.orEmpty())
    }

    suspend fun receive(status: CoMonthStatus, remarks: String?, actorPersonId: String): CircuitResult =
        service.receive(status.id, remarks, actorFor(actorPersonId), status.version)

    suspend fun returnToCongregation(status: CoMonthStatus, reason: String, actorPersonId: String): CircuitResult =
        service.returnForCorrection(status.id, reason, actorFor(actorPersonId), status.version)

    suspend fun cancel(status: CoMonthStatus, reason: String, actorPersonId: String): CircuitResult =
        service.cancelSubmission(status.id, reason, actorFor(actorPersonId), status.version)
}
