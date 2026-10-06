package com.emfitsolutions.gopreach.ui.screens.accountmanagement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.AccountStatus
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.AuthRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.domain.PermissionChecker
import com.emfitsolutions.gopreach.data.repository.AuthResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The six "Account Type" buckets spec §10's tree lists, one per role this
 * feature manages credentials for (plus [PUBLISHER], which is a Publisher
 * category rather than an [AdminRole]). */
enum class AccountType(val label: String) {
    PUBLISHER("Publishers"),
    ELDER("Elders"),
    MINISTERIAL_SERVANT("Ministerial Servants"),
    COORDINATOR_ELDER("Coordinator Elders"),
    SERVICE_OVERSEER("Service Overseers"),
    ADMIN("Admins"),
}

data class AccountRow(
    val person: Person,
    val accountType: AccountType,
    /** The [AdminRole] this row's active RoleAssignment resolves to — null
     * for [AccountType.PUBLISHER] rows (a Publisher category, not an Admin
     * role), which is exactly what [PermissionChecker.canManageCredentialsFor]
     * expects for its `targetAdminRole` parameter. */
    val targetAdminRole: AdminRole?,
    val congregationId: String?,
    val congregationName: String,
)

/**
 * Account / Credential Management (spec's own numbered spec) — congregation-
 * and role-scoped username changes + account-status control, on top of the
 * same Person/RoleAssignment/Congregation data every other admin-facing
 * module already reads (see [com.emfitsolutions.gopreach.ui.screens.admins.ManageAdminsViewModel]
 * for the precedent this follows). [PermissionChecker.canManageCredentialsFor]
 * is the single source of truth for both which [AccountType] buckets a role
 * sees ([availableAccountTypes]) and which specific rows it may act on
 * ([rowsFor] never returns a row the signed-in session isn't actually
 * authorized to manage) — mirrored server-side in firestore.rules so a raw
 * Firestore write can't bypass this screen's own gating (spec §6/§11).
 */
@HiltViewModel
class AccountManagementViewModel @Inject constructor(
    private val personRepository: PersonRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val congregationRepository: CongregationRepository,
    private val authRepository: AuthRepository,
    private val auditLogRepository: AuditLogRepository,
) : ViewModel() {

    /** Super-Admin's own Congregation Selector (spec §1) — the dropdown's
     * data source, same pattern as [com.emfitsolutions.gopreach.ui.screens.admins.ManageAdminsViewModel.congregations]. */
    val congregations: StateFlow<List<Congregation>> = congregationRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Which "Account Type" buckets [actingRole] ever gets to see at all
     * (spec §10's tree, one branch per role) — independent of congregation,
     * since Super-Admin's tree doesn't change per congregation, it only adds
     * the selector above it. */
    fun availableAccountTypes(actingRole: AdminRole?): List<AccountType> = when (actingRole) {
        AdminRole.SUPER_ADMIN -> AccountType.entries
        AdminRole.ADMIN_PER_CONGREGATION -> listOf(AccountType.PUBLISHER, AccountType.ELDER, AccountType.MINISTERIAL_SERVANT)
        AdminRole.COORDINATOR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY -> listOf(AccountType.PUBLISHER)
        else -> emptyList()
    }

    /** [congregationFilter] is the Super-Admin's own Congregation Selector
     * choice (null = every congregation); for every other role it's ignored —
     * [actingCongregationId] already fixes their one congregation, and
     * [PermissionChecker.canManageCredentialsFor] enforces that regardless of
     * whatever this parameter is passed as. */
    fun rowsFor(
        actingRole: AdminRole?,
        actingCongregationId: String?,
        accountType: AccountType,
        congregationFilter: String?,
        searchQuery: String,
    ): Flow<List<AccountRow>> = combine(
        personRepository.observeAll(),
        roleAssignmentRepository.observeAll(),
        congregations,
    ) { people, assignments, congregationList ->
        if (actingRole == null) return@combine emptyList()
        val activeAssignments = assignments.filter { it.status == RoleAssignmentStatus.ACTIVE }
        fun congregationNameFor(congregationId: String?) =
            congregationList.firstOrNull { it.id == congregationId }?.name ?: "Unassigned"

        val candidateRows: List<AccountRow> = when (accountType) {
            AccountType.PUBLISHER -> activeAssignments
                .filter { it.resolvedRoleTypeOrNull() is RoleType.Publisher }
                .distinctBy { it.personId }
                .mapNotNull { a -> toRow(a, AccountType.PUBLISHER, null, people, ::congregationNameFor) }
            AccountType.ELDER -> rowsForAdminRole(AdminRole.REGULAR_ELDER, AccountType.ELDER, activeAssignments, people, ::congregationNameFor)
            AccountType.MINISTERIAL_SERVANT ->
                rowsForAdminRole(AdminRole.MINISTERIAL_SERVANT, AccountType.MINISTERIAL_SERVANT, activeAssignments, people, ::congregationNameFor)
            AccountType.COORDINATOR_ELDER ->
                rowsForAdminRole(AdminRole.COORDINATOR_ELDER, AccountType.COORDINATOR_ELDER, activeAssignments, people, ::congregationNameFor)
            // SECRETARY is bucketed with SERVICE_OVERSEER here the same way
            // every other role-gating check in this app treats the two as
            // one — see AdminRole.SECRETARY's own doc comment.
            AccountType.SERVICE_OVERSEER ->
                rowsForAdminRole(AdminRole.SERVICE_OVERSEER, AccountType.SERVICE_OVERSEER, activeAssignments, people, ::congregationNameFor) +
                    rowsForAdminRole(AdminRole.SECRETARY, AccountType.SERVICE_OVERSEER, activeAssignments, people, ::congregationNameFor)
            AccountType.ADMIN -> rowsForAdminRole(AdminRole.ADMIN_PER_CONGREGATION, AccountType.ADMIN, activeAssignments, people, ::congregationNameFor)
        }

        val query = searchQuery.trim().lowercase()
        candidateRows
            .filter { row ->
                PermissionChecker.canManageCredentialsFor(
                    actingRole = actingRole,
                    actingCongregationId = actingCongregationId,
                    targetIsPublisher = row.targetAdminRole == null,
                    targetAdminRole = row.targetAdminRole,
                    targetCongregationId = row.congregationId,
                )
            }
            .filter { congregationFilter == null || it.congregationId == congregationFilter }
            .filter { row ->
                query.isBlank() ||
                    row.person.fullName.lowercase().contains(query) ||
                    row.person.username.lowercase().contains(query) ||
                    row.accountType.label.lowercase().contains(query) ||
                    row.congregationName.lowercase().contains(query)
            }
            .sortedBy { it.person.fullName }
    }

    private fun rowsForAdminRole(
        role: AdminRole,
        accountType: AccountType,
        assignments: List<RoleAssignment>,
        people: List<Person>,
        congregationNameFor: (String?) -> String,
    ): List<AccountRow> = assignments
        .filter { (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role == role }
        .mapNotNull { a -> toRow(a, accountType, role, people, congregationNameFor) }

    private fun toRow(
        assignment: RoleAssignment,
        accountType: AccountType,
        targetAdminRole: AdminRole?,
        people: List<Person>,
        congregationNameFor: (String?) -> String,
    ): AccountRow? {
        val person = people.firstOrNull { it.id == assignment.personId } ?: return null
        return AccountRow(
            person = person,
            accountType = accountType,
            targetAdminRole = targetAdminRole,
            congregationId = assignment.congregationId,
            congregationName = congregationNameFor(assignment.congregationId),
        )
    }

    /** Spec §7 — delegates to [AuthRepository.adminChangeUsername]; the
     * caller (screen) already filtered [row] out of [rowsFor] if the signed-in
     * session isn't authorized to act on it, so no second check is needed
     * here (same trust boundary [com.emfitsolutions.gopreach.ui.screens.admins.ManageAdminsViewModel]
     * and every other management ViewModel in this app already relies on). */
    fun changeUsername(row: AccountRow, newUsername: String, actingPersonId: String, onResult: (AuthResult) -> Unit) {
        viewModelScope.launch {
            onResult(authRepository.adminChangeUsername(row.person.id, newUsername, actingPersonId))
        }
    }

    /** Same shape as [com.emfitsolutions.gopreach.ui.screens.users.ManageUsersViewModel.setAccountStatus]
     * — writes [Person.accountStatus] and audit-logs the before/after. */
    fun setAccountStatus(row: AccountRow, status: AccountStatus, actingPersonId: String) {
        viewModelScope.launch {
            val previous = row.person.accountStatus
            if (previous == status) return@launch
            personRepository.save(row.person.copy(accountStatus = status))
            auditLogRepository.log(
                actorPersonId = actingPersonId,
                action = "ACCOUNT_MGMT_STATUS_CHANGE",
                targetType = "Person",
                targetId = row.person.id,
                congregationId = row.congregationId,
                details = "status: $previous -> $status",
            )
        }
    }
}
