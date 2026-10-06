package com.emfitsolutions.gopreach.ui.screens.congregations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.GroupRepository
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.data.repository.MonthlyReportRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.VisitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Spec §3/§5.1 — "Manage Congregation Master File", Super-Admin only.
 *
 * "Admin Record Deletion and Inactive Status" spec: Delete no longer means
 * immediate destruction — [setStatus] is "Move to Inactive" (record and
 * everything under it stays exactly as it is, just hidden from normal
 * active lists), and [permanentlyDelete] is the harder, referential-
 * integrity-checked path (spec §4: a congregation with any Group or any
 * Admin/Elder/Publisher RoleAssignment still pointing at it — active *or*
 * inactive, since an inactive one is still a real historical record — can
 * never be permanently deleted; only an empty congregation can be). */
@HiltViewModel
class ManageCongregationsViewModel @Inject constructor(
    private val congregationRepository: CongregationRepository,
    private val groupRepository: GroupRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val auditLogRepository: AuditLogRepository,
    private val personRepository: PersonRepository,
    private val monthlyReportRepository: MonthlyReportRepository,
    private val interestedPersonRepository: InterestedPersonRepository,
    private val visitRepository: VisitRepository,
) : ViewModel() {

    val congregations: StateFlow<List<Congregation>> = congregationRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun update(congregation: Congregation, updatedByPersonId: String) {
        viewModelScope.launch {
            congregationRepository.save(congregation)
            auditLogRepository.log(
                actorPersonId = updatedByPersonId,
                action = "UPDATE_CONGREGATION",
                targetType = "Congregation",
                targetId = congregation.id,
                congregationId = congregation.id,
            )
        }
    }

    /** "Move to Inactive" / reactivate — record and every Group/Admin/Elder/
     * Publisher still under it are completely untouched; this only changes
     * whether the congregation itself shows up in the normal active list. */
    fun setStatus(congregation: Congregation, status: RecordStatus, actorPersonId: String) {
        viewModelScope.launch {
            val previous = congregation.status
            congregationRepository.save(congregation.copy(status = status))
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "CHANGE_CONGREGATION_STATUS",
                targetType = "Congregation",
                targetId = congregation.id,
                congregationId = congregation.id,
                details = "status: $previous -> $status",
            )
        }
    }

    /** Non-blocking heads-up for the confirmation dialog — Super-Admin's
     * force-delete is never refused (spec: "Allow the super admin to force
     * delete record under all modules"); [permanentlyDelete] cascades
     * through everything named here, so this just tells them what's about
     * to go with it. Null when the congregation is already empty. */
    suspend fun permanentDeleteImpactSummary(congregationId: String): String? {
        val groupCount = groupRepository.observeAll().first().count { it.congregationId == congregationId }
        val assignmentCount = roleAssignmentRepository.observeAll().first().count { it.congregationId == congregationId }
        if (groupCount == 0 && assignmentCount == 0) return null
        val parts = buildList {
            if (groupCount > 0) add("$groupCount group(s)")
            if (assignmentCount > 0) add("$assignmentCount admin/elder/publisher record(s) (including inactive ones, and each publisher's monthly reports and interested person/Bible study records)")
        }
        return "This will also permanently delete " + parts.joinToString(" and ") + " (congregations are not covered by Deleted Records yet)."
    }

    /** Super-Admin-only cascading delete (see [canPermanentlyDelete]):
     * removes every Group under this congregation, every RoleAssignment
     * scoped to it (Admin/Elder/Publisher, active or inactive), and — for
     * each Publisher assignment — that publisher's Monthly Reports and
     * Interested Person/Bible Study records (with their Visit history), the
     * same cascade [com.emfitsolutions.gopreach.ui.screens.publishers
     * .ManagePublishersViewModel.permanentlyDelete] already does for one
     * publisher at a time. A Person doc is only removed once none of their
     * RoleAssignments (in *any* congregation) remain. */
    fun permanentlyDelete(congregationId: String, actorPersonId: String) {
        viewModelScope.launch {
            val groups = groupRepository.observeAll().first().filter { it.congregationId == congregationId }
            groups.forEach { groupRepository.delete(it.id) }

            val assignments = roleAssignmentRepository.observeAll().first().filter { it.congregationId == congregationId }
            val touchedPersonIds = mutableSetOf<String>()
            assignments.forEach { assignment ->
                if (assignment.resolvedRoleTypeOrNull() is RoleType.Publisher) {
                    monthlyReportRepository.observeAll().first()
                        .filter { it.publisherPersonId == assignment.personId && it.congregationId == congregationId }
                        .forEach { monthlyReportRepository.delete(it.id) }
                    interestedPersonRepository.observeAll().first()
                        .filter { it.publisherPersonId == assignment.personId && it.congregationId == congregationId }
                        .forEach { interestedPerson ->
                            visitRepository.observeForInterestedPerson(interestedPerson.id).first()
                                .forEach { visit -> visitRepository.delete(interestedPerson.id, visit.id) }
                            interestedPersonRepository.delete(interestedPerson.id)
                        }
                }
                roleAssignmentRepository.delete(assignment.id)
                touchedPersonIds += assignment.personId
            }
            touchedPersonIds.forEach { personId ->
                val remaining = roleAssignmentRepository.observeAll().first().count { it.personId == personId }
                if (remaining == 0) personRepository.delete(personId)
            }

            congregationRepository.delete(congregationId)
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "PERMANENT_DELETE_CONGREGATION",
                targetType = "Congregation",
                targetId = congregationId,
                congregationId = congregationId,
                details = "cascaded: ${groups.size} group(s), ${assignments.size} role assignment(s)",
            )
        }
    }
}
