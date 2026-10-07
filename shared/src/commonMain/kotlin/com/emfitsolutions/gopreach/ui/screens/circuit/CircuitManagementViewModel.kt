package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.repository.CircuitCodeRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** The Super-Admin's at-a-glance numbers. */
data class CircuitOverview(
    val overseers: Int,
    val circuits: Int,
    val congregations: Int,
)

/** One entry in the circuit picker: [scopeId] is the overseer's person id, or [CIRCUIT_SCOPE_ALL]. */
data class CircuitChoice(val scopeId: String, val label: String, val overseerPersonId: String?)

/** Backs Circuit Overseer Management (Super-Admin only): system-wide overview and the circuit picker that drives the shared Circuit screens. */
class CircuitManagementViewModel(
    personRepository: PersonRepository,
    roleAssignmentRepository: RoleAssignmentRepository,
    codeRepository: CircuitCodeRepository,
    congregationRepository: CongregationRepository,
) : ViewModel() {

    val overview: Flow<CircuitOverview> = combine(
        roleAssignmentRepository.observeAll(),
        codeRepository.observeAll(),
        congregationRepository.observeAll(),
    ) { assignments, codes, congregations ->
        CircuitOverview(
            overseers = assignments.filter { (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role == AdminRole.CIRCUIT_OVERSEER }.map { it.personId }.toSet().size,
            circuits = codes.size,
            congregations = congregations.count { it.status == RecordStatus.ACTIVE },
        )
    }

    /** "All Circuits" plus every circuit that has an overseer, e.g. "NT01 — Juan Dela Cruz". */
    val choices: Flow<List<CircuitChoice>> = combine(codeRepository.observeAll(), personRepository.observeAll()) { codes, people ->
        val byId = people.associateBy { it.id }
        listOf(CircuitChoice(CIRCUIT_SCOPE_ALL, "All Circuits", null)) + codes
            .filter { it.overseerPersonId != null }
            .map { c -> CircuitChoice(c.overseerPersonId!!, "${c.code} — ${byId[c.overseerPersonId]?.fullName ?: "Unknown"}", c.overseerPersonId) }
            .sortedBy { it.label }
    }
}
