package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.ScopeType
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.UserAccessGrantRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** What the Circuit Overseer's dashboard header shows: the circuit, whose it is, and how many congregations it has. */
data class CircuitDashboardState(
    val circuitCode: String?,
    val overseerName: String,
    val assignedCount: Int,
    /** The congregations the overseer oversees, by name. */
    val congregationNames: List<String> = emptyList(),
)

/**
 * Header of the Circuit Overseer dashboard. The set of congregations comes from the overseer's own grant — exactly what
 * the security rules let this account read.
 */
class CircuitDashboardViewModel(
    private val personRepository: PersonRepository,
    private val grantRepository: UserAccessGrantRepository,
    private val congregationRepository: CongregationRepository,
) : ViewModel() {

    fun stateFor(personId: String): Flow<CircuitDashboardState> =
        combine(personRepository.observeAll(), grantRepository.observeCircuitScope(personId), congregationRepository.observeAll()) { people, grant, congregations ->
            val allCircuits = personId == CIRCUIT_SCOPE_ALL
            val active = congregations.filter { it.status == RecordStatus.ACTIVE }
            val mine = when {
                allCircuits || grant?.resolvedScopeType == ScopeType.ALL_CONGREGATIONS -> active
                else -> active.filter { it.id in grant?.scopeCongregationIds.orEmpty() }
            }
            val count = mine.size
            CircuitDashboardState(
                circuitCode = if (allCircuits) "ALL" else grant?.circuitCode?.takeIf { it.isNotBlank() },
                overseerName = if (allCircuits) "All Circuit Overseers" else people.firstOrNull { it.id == personId }?.fullName.orEmpty(),
                assignedCount = count,
                congregationNames = mine.map { it.name }.sorted(),
            )
        }
}
