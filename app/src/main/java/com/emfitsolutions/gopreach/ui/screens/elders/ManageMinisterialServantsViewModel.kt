package com.emfitsolutions.gopreach.ui.screens.elders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RegularElderRole
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** "MINISTERIAL ACCOUNT" spec — "CRUD Ministerial Servant," reachable by
 * Super-Admin/Admin/Coordinator Elder. Reuses [ElderRow] (from
 * ManageCoordinatorEldersViewModel) since the shape is identical; scoped by
 * congregation the same way Coordinator Elders/Service Overseers already
 * are — unlike Service Overseer, multiple active rows per congregation are
 * expected, not an anomaly. */
@HiltViewModel
class ManageMinisterialServantsViewModel @Inject constructor(
    private val personRepository: PersonRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val congregationRepository: CongregationRepository,
    private val auditLogRepository: AuditLogRepository,
    private val recycleBinRepository: com.emfitsolutions.gopreach.data.repository.RecycleBinRepository,
) : ViewModel() {

    /** For the Edit dialog's Congregation dropdown — Super-Admin only (an
     * Admin/Coordinator Elder editing is already fixed to their own
     * congregation, same as everywhere else in this app). */
    val congregations: StateFlow<List<Congregation>> =
        congregationRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** "Allow the user to edit also the user role, show the checkbox to edit
     * the user roles" — the additional, simultaneous "Select Role"
     * assignments this Ministerial Servant holds on top of their primary
     * role, same "extra RoleAssignment" shape Coordinator Elder/Service
     * Overseer's own edit dialogs already read (see
     * ManageServiceOverseersViewModel.additionalRolesFor's doc comment for
     * why these are separate RoleAssignment docs). Unlike those two — a
     * single Group Overseer boolean — a Ministerial Servant's own Group role
     * is one of two mutually exclusive options (see
     * MinisterialServantEnrollmentViewModel's own doc comment on
     * [RegularElderRole]), so this returns the [RegularElderRole]? itself
     * (GROUP_SERVANT, GROUP_ASSISTANT, or null) rather than a Boolean. */
    fun additionalRolesFor(personId: String): Flow<Pair<RegularElderRole?, PublisherCategory?>> =
        roleAssignmentRepository.observeForPerson(personId).map { assignments ->
            val groupRole = assignments.firstOrNull {
                it.status == RoleAssignmentStatus.ACTIVE &&
                    (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role == AdminRole.REGULAR_ELDER &&
                    (it.regularElderRole == RegularElderRole.GROUP_SERVANT || it.regularElderRole == RegularElderRole.GROUP_ASSISTANT)
            }?.regularElderRole
            val publisherCategory = assignments
                .firstOrNull { it.status == RoleAssignmentStatus.ACTIVE && it.resolvedRoleTypeOrNull() is RoleType.Publisher }
                ?.let { (it.resolvedRoleTypeOrNull() as RoleType.Publisher).category }
            groupRole to publisherCategory
        }

    /** Saves the Person fields, an optional congregation reassignment on the
     * primary Ministerial Servant role, and reconciles the two additional
     * "Select Role" assignments (Group Servant/Group Assistant / publisher
     * category) against whatever's currently checked — creating, updating,
     * or removing each one as needed, the same reconciliation
     * ManageServiceOverseersViewModel.updateRolesAndPerson already does for
     * its own single-boolean Group Overseer checkbox. */
    fun updateRolesAndPerson(
        row: ElderRow,
        updatedPerson: Person,
        newCongregationId: String,
        groupRole: RegularElderRole?,
        publisherCategory: PublisherCategory?,
        actorPersonId: String,
    ) {
        viewModelScope.launch {
            personRepository.save(updatedPerson)

            val now = System.currentTimeMillis()
            if (row.assignment.congregationId != newCongregationId) {
                roleAssignmentRepository.save(
                    row.assignment.copy(congregationId = newCongregationId, lastEditedByPersonId = actorPersonId, lastEditedAt = now),
                )
            }

            val existingAssignments = roleAssignmentRepository.observeForPerson(row.person.id).first()

            val existingGroupRole = existingAssignments.firstOrNull {
                it.status == RoleAssignmentStatus.ACTIVE &&
                    (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role == AdminRole.REGULAR_ELDER &&
                    (it.regularElderRole == RegularElderRole.GROUP_SERVANT || it.regularElderRole == RegularElderRole.GROUP_ASSISTANT)
            }
            when {
                groupRole != null && existingGroupRole == null -> roleAssignmentRepository.save(
                    RoleAssignment(
                        personId = row.person.id,
                        roleType = RoleType.serialize(RoleType.Admin(AdminRole.REGULAR_ELDER)),
                        congregationId = newCongregationId,
                        regularElderRole = groupRole,
                        status = RoleAssignmentStatus.ACTIVE,
                        dateAssigned = now,
                        assignedByPersonId = actorPersonId,
                    ),
                )
                groupRole != null && existingGroupRole != null -> roleAssignmentRepository.save(
                    existingGroupRole.copy(
                        regularElderRole = groupRole,
                        congregationId = newCongregationId,
                        lastEditedByPersonId = actorPersonId,
                        lastEditedAt = now,
                    ),
                )
                groupRole == null && existingGroupRole != null -> roleAssignmentRepository.delete(existingGroupRole.id)
            }

            val existingPublisher = existingAssignments.firstOrNull {
                it.status == RoleAssignmentStatus.ACTIVE && it.resolvedRoleTypeOrNull() is RoleType.Publisher
            }
            when {
                publisherCategory != null && existingPublisher == null -> roleAssignmentRepository.save(
                    RoleAssignment(
                        personId = row.person.id,
                        roleType = RoleType.serialize(RoleType.Publisher(publisherCategory)),
                        congregationId = newCongregationId,
                        status = RoleAssignmentStatus.ACTIVE,
                        dateAssigned = now,
                        assignedByPersonId = actorPersonId,
                    ),
                )
                publisherCategory != null && existingPublisher != null -> roleAssignmentRepository.save(
                    existingPublisher.copy(
                        roleType = RoleType.serialize(RoleType.Publisher(publisherCategory)),
                        congregationId = newCongregationId,
                        lastEditedByPersonId = actorPersonId,
                        lastEditedAt = now,
                    ),
                )
                publisherCategory == null && existingPublisher != null -> roleAssignmentRepository.delete(existingPublisher.id)
            }

            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "EDIT_MINISTERIAL_SERVANT",
                targetType = "Person",
                targetId = row.person.id,
                congregationId = newCongregationId,
                details = "groupRole: $groupRole, publisherCategory: $publisherCategory",
            )
        }
    }

    fun rowsFor(visibleCongregationId: String?): Flow<List<ElderRow>> = combine(
        personRepository.observeAll(),
        roleAssignmentRepository.observeAll(),
        congregationRepository.observeAll(),
    ) { people, assignments, congregations ->
        assignments
            .filter { (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role == AdminRole.MINISTERIAL_SERVANT }
            .filter { visibleCongregationId == null || it.congregationId == visibleCongregationId }
            .mapNotNull { assignment ->
                val person = people.firstOrNull { it.id == assignment.personId } ?: return@mapNotNull null
                val scopeName = congregations.firstOrNull { it.id == assignment.congregationId }?.name ?: "Unassigned"
                ElderRow(person, assignment, scopeName, assignment.status == RoleAssignmentStatus.ACTIVE)
            }
            .sortedBy { it.person.fullName }
    }

    fun setActive(assignment: RoleAssignment, active: Boolean, actorPersonId: String) {
        viewModelScope.launch {
            val previous = assignment.status
            val newStatus = if (active) RoleAssignmentStatus.ACTIVE else RoleAssignmentStatus.INACTIVE
            roleAssignmentRepository.save(assignment.copy(status = newStatus))
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "CHANGE_MINISTERIAL_SERVANT_STATUS",
                targetType = "Person",
                targetId = assignment.personId,
                congregationId = assignment.congregationId,
                details = "status: $previous -> $newStatus",
            )
        }
    }

    fun updatePerson(person: Person) {
        viewModelScope.launch { personRepository.save(person) }
    }

    /** Super-Admin/Admin-only permanent delete (see BUILD_PLAN.md scoping). */
    fun permanentlyDelete(row: ElderRow, actorPersonId: String) {
        viewModelScope.launch {
            val remaining = roleAssignmentRepository.observeAll().first().count { it.personId == row.person.id && it.id != row.assignment.id }
            recycleBinRepository.moveToTrash(
                recordType = "Ministerial Servant",
                module = "Ministerial Servants",
                label = row.person.fullName,
                congregationId = row.assignment.congregationId,
                groupId = row.assignment.groupId,
                originalCreatedAt = row.person.createdAt,
                originalModifiedAt = row.assignment.lastEditedAt,
                deletedByPersonId = actorPersonId,
                items = buildList {
                    add(recycleBinRepository.item("roleAssignments", row.assignment.id, row.assignment))
                    if (remaining == 0) add(recycleBinRepository.item("people", row.person.id, row.person))
                },
            )
            roleAssignmentRepository.delete(row.assignment.id)
            if (remaining == 0) personRepository.delete(row.person.id)
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "PERMANENT_DELETE_MINISTERIAL_SERVANT",
                targetType = "Person",
                targetId = row.person.id,
                congregationId = row.assignment.congregationId,
            )
        }
    }
}
