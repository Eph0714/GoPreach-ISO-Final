package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.AccountStatus
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** A Circuit Overseer a congregation may be assigned to: account active, role active, and holding an active Circuit Code. */
data class OverseerOption(val personId: String, val name: String, val circuitCode: String)

/**
 * "Circuit Overseer Assigned" dropdown source for the Congregation record. Only overseers that are *currently
 * assignable* appear — an inactive account, an inactive role, or an inactive/missing Circuit Code drops them out,
 * which is how "prevent assigning a congregation to an inactive Circuit Overseer / inactive Circuit Code" is
 * offered in the UI (the transaction in [CircuitAssignmentService] enforces it again on the server).
 */
class CircuitOverseerDirectory(
    personRepository: PersonRepository,
    roleAssignmentRepository: RoleAssignmentRepository,
    userAccessGrantRepository: UserAccessGrantRepository,
    codeRepository: CircuitCodeRepository,
) {
    val activeOverseers: Flow<List<OverseerOption>> = combine(
        personRepository.observeAll(),
        roleAssignmentRepository.observeAll(),
        userAccessGrantRepository.observeAll(),
        codeRepository.observeAll(),
    ) { people, assignments, grants, codes ->
        val grantsById = grants.associateBy { it.personId }
        val codesById = codes.associateBy { it.code }
        val overseerIds = assignments
            .filter { it.status == RoleAssignmentStatus.ACTIVE }
            .filter { (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role == AdminRole.CIRCUIT_OVERSEER }
            .map { it.personId }
            .toSet()
        people
            .filter { it.id in overseerIds && it.accountStatus == AccountStatus.ACTIVE }
            .mapNotNull { person ->
                val code = grantsById[person.id]?.circuitCode?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val codeDoc = codesById[code] ?: return@mapNotNull null
                if (codeDoc.status != RecordStatus.ACTIVE || codeDoc.overseerPersonId != person.id) return@mapNotNull null
                OverseerOption(person.id, person.fullName, code)
            }
            .sortedBy { it.name }
    }
}
