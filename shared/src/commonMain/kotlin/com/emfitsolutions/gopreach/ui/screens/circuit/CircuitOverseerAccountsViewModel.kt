package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.AccountStatus
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.model.UserAccessGrant
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.CircuitAssignmentService
import com.emfitsolutions.gopreach.data.repository.CircuitCodeRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.UserAccessGrantRepository
import com.emfitsolutions.gopreach.data.repository.messageOrNull
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CircuitOverseerRow(
    val person: Person,
    val assignment: RoleAssignment,
    val grant: UserAccessGrant?,
    /** The code recorded on the overseer's grant; null for an account created before this module existed. */
    val circuitCode: String?,
    /** Congregations whose `congregationCircuits` link names this person — the source of truth, not the grant's copy. */
    val congregations: List<Congregation>,
) {
    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return person.lastName.lowercase().contains(q) || person.firstName.lowercase().contains(q) ||
            person.username.lowercase().contains(q) || (circuitCode ?: "").lowercase().contains(q) ||
            congregations.any { it.name.lowercase().contains(q) }
    }
}

/** "Circuit Overseer Accounts" list — every account holding the Circuit Overseer role. */
class CircuitOverseerAccountsViewModel(
    private val personRepository: PersonRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    userAccessGrantRepository: UserAccessGrantRepository,
    codeRepository: CircuitCodeRepository,
    congregationRepository: CongregationRepository,
    private val service: CircuitAssignmentService,
    private val auditLogRepository: AuditLogRepository,
) : ViewModel() {

    private data class Sources(
        val people: List<Person>,
        val assignments: List<RoleAssignment>,
        val grants: List<UserAccessGrant>,
    )

    private val sources = combine(
        personRepository.observeAll(),
        roleAssignmentRepository.observeAll(),
        userAccessGrantRepository.observeAll(),
    ) { people, assignments, grants -> Sources(people, assignments, grants) }

    val rows: StateFlow<List<CircuitOverseerRow>> = combine(
        sources,
        codeRepository.observeLinks(),
        congregationRepository.observeAll(),
    ) { s, links, congregations ->
        val peopleById = s.people.associateBy { it.id }
        val grantsById = s.grants.associateBy { it.personId }
        s.assignments
            // resolvedRoleTypeOrNull, never the throwing resolvedRoleType — one corrupt row must not crash the list.
            .filter { (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role == AdminRole.CIRCUIT_OVERSEER }
            .distinctBy { it.personId }
            .mapNotNull { assignment ->
                val person = peopleById[assignment.personId] ?: return@mapNotNull null
                val grant = grantsById[person.id]
                val mine = congregations.filter { links[it.id]?.circuitOverseerPersonId == person.id }.sortedBy { it.name }
                CircuitOverseerRow(person, assignment, grant, grant?.circuitCode?.takeIf { it.isNotBlank() }, mine)
            }
            .sortedWith(compareBy({ it.person.lastName.lowercase() }, { it.person.firstName.lowercase() }))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setStatus(row: CircuitOverseerRow, status: AccountStatus, actorPersonId: String) {
        viewModelScope.launch {
            val previous = row.person.accountStatus
            personRepository.save(row.person.copy(accountStatus = status))
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "CHANGE_USER_STATUS",
                targetType = "Person",
                targetId = row.person.id,
                details = "status: $previous -> $status (${row.person.fullName})",
            )
        }
    }

    /** Why [row] can't be deleted yet, or null if it can. Shown in the confirmation dialog before anything happens. */
    fun deleteBlocker(row: CircuitOverseerRow): String? =
        if (row.congregations.isNotEmpty()) {
            "${row.person.fullName} still has ${row.congregations.size} assigned congregation(s) " +
                "(${row.congregations.joinToString(", ") { it.name }}). Reassign or unassign them first — " +
                "the account cannot be deleted while active congregation assignments remain."
        } else null

    /** Deletes the account. The server re-checks that no congregation is still assigned; [onResult] gets null on success. */
    fun delete(row: CircuitOverseerRow, actorPersonId: String, onResult: (String?) -> Unit) {
        deleteBlocker(row)?.let { onResult(it); return }
        viewModelScope.launch {
            val released = service.releaseOverseer(row.person.id, actorPersonId)
            if (released.messageOrNull() != null) {
                onResult(released.messageOrNull())
                return@launch
            }
            roleAssignmentRepository.delete(row.assignment.id)
            personRepository.delete(row.person.id)
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "PERMANENT_DELETE_USER",
                targetType = "Person",
                targetId = row.person.id,
                details = row.person.fullName,
            )
            onResult(null)
        }
    }
}
