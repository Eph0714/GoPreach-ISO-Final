package com.emfitsolutions.gopreach.ui.screens.groupmodules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.AuditLogEntry
import com.emfitsolutions.gopreach.data.model.MonthlyReport
import com.emfitsolutions.gopreach.data.model.ReportStatus
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.GroupRepository
import com.emfitsolutions.gopreach.data.repository.MonthlyReportRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** One line of the request history: a request or a decision, from the audit log. */
data class EditRequestHistoryRow(val timestamp: Long, val what: String, val publisher: String, val month: Long, val by: String, val text: String, val status: ReportStatus)

data class EditRequestsState(
    val slot: OfficerSlot? = null,
    /** Pending requests (status Access Requested) of the publishers in scope, newest first. */
    val pending: List<MonthlyReport> = emptyList(),
    val history: List<EditRequestHistoryRow> = emptyList(),
    val names: Map<String, String> = emptyMap(),
    val loaded: Boolean = false,
)

private val HISTORY_ACTIONS = mapOf("PUBLISHER_REPORT_ACCESS_REQUESTED" to "Requested", "PUBLISHER_REPORT_ACCESS_GRANTED" to "Approved", "PUBLISHER_REPORT_ACCESS_REJECTED" to "Rejected")

/**
 * FS Group Report Edit Requests. An officer of an FS Group (Overseer, Servant or Assistant) sees and decides the requests of THEIR OWN group's publishers; the congregation-wide roles keep the
 * whole congregation. Approving only opens the report for its publisher (status Access Granted): the figures are never written here. The server checks every decision again.
 */
class EditRequestsViewModel(
    private val groups: GroupRepository,
    private val roles: RoleAssignmentRepository,
    private val people: PersonRepository,
    private val reports: MonthlyReportRepository,
    private val audit: AuditLogRepository,
) : ViewModel() {
    fun state(personId: String, congregationId: String?, readsAll: Boolean): Flow<EditRequestsState> =
        combine(groups.observeAll(), roles.observeAll(), people.observeAll(), reports.observeAll(), audit.observeAll()) { allGroups, allRoles, allPeople, allReports, entries ->
            val slot = officerSlotOf(personId, congregationId, allGroups)
            val members = if (readsAll) null else slot?.let { groupMemberIds(it.group, allRoles) }
            val inScope = { r: MonthlyReport -> (congregationId == null || r.congregationId == congregationId) && (members == null || r.publisherPersonId in members) }
            val scoped = allReports.filter(inScope)
            val names = allPeople.associate { it.id to it.fullName }
            val byId = scoped.associateBy { it.id }
            EditRequestsState(
                slot = slot,
                pending = scoped.filter { it.status == ReportStatus.ACCESS_REQUESTED }.sortedByDescending { it.accessRequestedAt ?: 0L },
                history = entries.mapNotNull { e -> historyRow(e, byId, names) }.sortedByDescending { it.timestamp }.take(200),
                names = names,
                loaded = true,
            )
        }

    private fun historyRow(e: AuditLogEntry, reports: Map<String, MonthlyReport>, names: Map<String, String>): EditRequestHistoryRow? {
        val what = HISTORY_ACTIONS[e.action] ?: return null
        val r = reports[e.targetId] ?: return null
        return EditRequestHistoryRow(e.timestamp, what, names[r.publisherPersonId].orEmpty(), r.periodMonth, names[e.actorPersonId] ?: "—", e.details.orEmpty(), r.status)
    }

    /** Approve (open the report for its publisher) or reject (it stays submitted), with optional [remarks]. Written to the server at once; only the decision is changed. */
    fun decide(report: MonthlyReport, approve: Boolean, remarks: String, byPersonId: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            if (report.status != ReportStatus.ACCESS_REQUESTED) {
                onDone("This request was already decided.")
                return@launch
            }
            if (report.publisherPersonId == byPersonId) {
                onDone("You cannot decide your own request.")
                return@launch
            }
            val now = System.currentTimeMillis()
            val note = remarks.trim().ifBlank { null }
            val decided = report.copy(
                accessDecisionNote = note, accessDecidedByPersonId = byPersonId, accessDecidedAt = now, lastEditedByPersonId = byPersonId, lastEditedAt = now,
                status = if (approve) ReportStatus.ACCESS_GRANTED else ReportStatus.SUBMITTED,
                accessGrantedByPersonId = if (approve) byPersonId else report.accessGrantedByPersonId,
                accessGrantedAt = if (approve) now else report.accessGrantedAt,
            )
            try {
                reports.pushDecision(decided)
                audit.log(
                    actorPersonId = byPersonId, action = if (approve) "PUBLISHER_REPORT_ACCESS_GRANTED" else "PUBLISHER_REPORT_ACCESS_REJECTED", targetType = "MonthlyReport",
                    targetId = report.id, congregationId = report.congregationId, details = note,
                )
                onDone(null)
            } catch (e: Exception) {
                onDone(e.message ?: "The decision could not be saved.")
            }
        }
    }
}
