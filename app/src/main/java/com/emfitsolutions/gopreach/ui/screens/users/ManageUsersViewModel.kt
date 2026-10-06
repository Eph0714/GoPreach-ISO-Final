package com.emfitsolutions.gopreach.ui.screens.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.AccountStatus
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.model.UserAccessGrant
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.UserAccessGrantRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RestrictedUserRow(
    val person: Person,
    val assignment: RoleAssignment,
    val grant: UserAccessGrant?,
)

/**
 * "User Management" module (spec §3/§8) — every restricted (Circuit Overseer /
 * custom) account, Super-Admin (or an Admin explicitly granted MANAGE_USERS —
 * spec §14) only; visibility itself is gated by the nav graph/dashboard tile,
 * not this ViewModel.
 */
@HiltViewModel
class ManageUsersViewModel @Inject constructor(
    private val personRepository: PersonRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val userAccessGrantRepository: UserAccessGrantRepository,
    private val auditLogRepository: AuditLogRepository,
) : ViewModel() {

    val users: StateFlow<List<RestrictedUserRow>> = combine(
        personRepository.observeAll(),
        roleAssignmentRepository.observeAll(),
        userAccessGrantRepository.observeAll(),
    ) { people, assignments, grants ->
        assignments
            // resolvedRoleTypeOrNull, never the throwing resolvedRoleType — see
            // DashboardStats.computeStatMembers' doc comment for the crash this
            // avoids (as? alone doesn't help; the throw happens before the cast).
            .filter { (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role == AdminRole.CIRCUIT_OVERSEER }
            .mapNotNull { assignment ->
                val person = people.firstOrNull { it.id == assignment.personId } ?: return@mapNotNull null
                RestrictedUserRow(person, assignment, grants.firstOrNull { it.personId == person.id })
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Spec §9 — account-level status, independent of the RoleAssignment itself
     * (which stays ACTIVE; it's what makes this person show up here at all). */
    fun setAccountStatus(row: RestrictedUserRow, status: AccountStatus, actingPersonId: String) {
        viewModelScope.launch {
            val previous = row.person.accountStatus
            personRepository.save(row.person.copy(accountStatus = status))
            auditLogRepository.log(
                actorPersonId = actingPersonId,
                action = "CHANGE_USER_STATUS",
                targetType = "Person",
                targetId = row.person.id,
                details = "status: $previous -> $status (${row.person.fullName})",
            )
        }
    }

    /** Same "deactivate, never delete" pattern as every other role in the app
     * (spec §9's "do not delete historical records"). Distinct from account
     * status — this only affects whether the Circuit Overseer role itself still
     * resolves, in case a future role needs the same person deactivated as one
     * role but not another. */
    fun setRoleActive(assignment: RoleAssignment, active: Boolean) {
        viewModelScope.launch {
            roleAssignmentRepository.save(assignment.copy(status = if (active) RoleAssignmentStatus.ACTIVE else RoleAssignmentStatus.INACTIVE))
        }
    }

    /** Only Super-Admin ever sees this (see BUILD_PLAN.md scoping). Deletes the
     * UserAccessGrant, the Circuit Overseer RoleAssignment, and the Person doc
     * itself — a restricted User has no historical reports of their own tied to
     * them the way a Publisher does, so nothing here needs a relationship check. */
    fun permanentlyDelete(row: RestrictedUserRow, actorPersonId: String) {
        viewModelScope.launch {
            userAccessGrantRepository.delete(row.person.id)
            roleAssignmentRepository.delete(row.assignment.id)
            personRepository.delete(row.person.id)
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "PERMANENT_DELETE_USER",
                targetType = "Person",
                targetId = row.person.id,
                details = row.person.fullName,
            )
        }
    }
}
