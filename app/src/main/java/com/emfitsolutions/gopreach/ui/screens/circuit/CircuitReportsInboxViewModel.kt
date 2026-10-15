package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.model.CoMonthStatus
import com.emfitsolutions.gopreach.data.model.CoReceivedReport
import com.emfitsolutions.gopreach.data.model.CoReportEvent
import com.emfitsolutions.gopreach.data.repository.CircuitResult
import com.emfitsolutions.gopreach.data.repository.CoFieldServiceReportRepository
import com.emfitsolutions.gopreach.data.repository.CoFieldServiceReportService
import com.emfitsolutions.gopreach.data.repository.CoReceivedReportRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.SubmissionActor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** One congregation's month in the Circuit Reports inbox: its status, the snapshot of the CURRENT version, and whether the overseer has opened it. */
data class InboxItem(val status: CoMonthStatus, val report: CoReceivedReport?, val unread: Boolean) {
    /** The latest thing that happened to this month (sent, received, returned, cancelled, remarks). */
    val lastActionAt: Long get() = listOfNotNull(status.updatedAt, status.submittedAt, status.receivedAt, status.returnedAt, status.cancelledAt).max()
}

/**
 * The Circuit Overseer's inbox of submitted Circuit Reports and the actions on them. Every action is made against the exact snapshot
 * VERSION the overseer is looking at ([InboxItem.status] version): if the congregation has sent a newer version meanwhile, the action
 * is refused rather than applied to the wrong snapshot.
 */
class CircuitReportsInboxViewModel(
    private val reports: CoReceivedReportRepository,
    private val statuses: CoFieldServiceReportRepository,
    private val service: CoFieldServiceReportService,
    private val persons: PersonRepository,
) : ViewModel() {

    fun items(congregationIds: Set<String>, coPersonId: String): Flow<List<InboxItem>> =
        combine(statuses.observeStatuses(), reports.observeReports(), reports.observeReadIds(coPersonId)) { st, copies, read ->
            st.filter { it.congregationId in congregationIds && it.status.visibleToCircuitOverseer }.map { s ->
                val sends = copies.filter { it.congregationId == s.congregationId && it.periodMonth == s.periodMonth }
                val current = sends.firstOrNull { it.version == s.version } ?: sends.maxByOrNull { it.version }
                InboxItem(s, current, unread = sends.any { it.id !in read })
            }.sortedByDescending { it.lastActionAt }
        }

    /** The status of one month (for the snapshot viewer). */
    fun status(congregationId: String, month: Long): Flow<CoMonthStatus?> =
        statuses.observeStatuses().map { list -> list.firstOrNull { it.id == com.emfitsolutions.gopreach.data.model.coReportId(congregationId, month) } }

    /** Every recorded action on one month (sent, updated, received, returned, cancelled, with who/when/why), oldest first. */
    fun history(congregationId: String, month: Long): Flow<List<CoReportEvent>> =
        statuses.observeEvents().map { list -> list.filter { it.reportId == com.emfitsolutions.gopreach.data.model.coReportId(congregationId, month) } }

    private suspend fun actorFor(personId: String): SubmissionActor {
        val person = persons.get(personId)
        return SubmissionActor(personId, person?.fullName ?: personId, if (person?.isSuperAdmin == true) "SUPER_ADMIN" else person?.activeAdminRole.orEmpty())
    }

    suspend fun receive(status: CoMonthStatus, remarks: String?, actorPersonId: String): CircuitResult =
        service.receive(status.id, remarks, actorFor(actorPersonId), status.version)

    suspend fun returnForEditing(status: CoMonthStatus, reason: String, actorPersonId: String): CircuitResult =
        service.returnForCorrection(status.id, reason, actorFor(actorPersonId), status.version)

    suspend fun cancel(status: CoMonthStatus, reason: String, actorPersonId: String): CircuitResult =
        service.cancelSubmission(status.id, reason, actorFor(actorPersonId), status.version)

    suspend fun markRead(coPersonId: String, report: CoReceivedReport) = reports.markRead(coPersonId, report)
}
