package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.repository.CircuitAssignmentService
import com.emfitsolutions.gopreach.data.repository.CircuitCodeRepository
import com.emfitsolutions.gopreach.data.repository.CircuitOverseerDirectory
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.OverseerOption
import com.emfitsolutions.gopreach.data.repository.messageOrNull
import com.emfitsolutions.gopreach.domain.CircuitRules
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * "Circuit Assignment" — the update process for congregations that existed before the Circuit Overseer module:
 * every one with no Circuit Overseer is listed here, and can be given one (one by one, or a whole selection at
 * once). Each assignment is the same server transaction the Congregation form uses, so it can't collide with
 * someone else's edit. Once the list is empty, the Congregation form starts requiring the field on every save.
 */
class CircuitAssignmentMigrationViewModel(
    congregationRepository: CongregationRepository,
    codeRepository: CircuitCodeRepository,
    circuitOverseerDirectory: CircuitOverseerDirectory,
    private val service: CircuitAssignmentService,
) : ViewModel() {

    /** Active congregations still without a Circuit Overseer. */
    val unassigned: StateFlow<List<Congregation>> = combine(
        congregationRepository.observeAll(),
        codeRepository.observeLinks(),
    ) { all, links -> CircuitRules.unassigned(all.filter { it.status == RecordStatus.ACTIVE }, links) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val overseers: StateFlow<List<OverseerOption>> = circuitOverseerDirectory.activeOverseers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** [onResult] gets null on success or the message to show. */
    fun assign(congregationId: String, overseerPersonId: String, actorPersonId: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            onResult(service.setCongregationOverseer(congregationId, overseerPersonId, actorPersonId).messageOrNull())
        }
    }

    /** Assigns every id in [congregationIds] to one overseer; [onResult] gets how many worked and the first failure message, if any. */
    fun assignMany(congregationIds: Collection<String>, overseerPersonId: String, actorPersonId: String, onResult: (done: Int, firstProblem: String?) -> Unit) {
        viewModelScope.launch {
            var done = 0
            var firstProblem: String? = null
            for (id in congregationIds) {
                val problem = service.setCongregationOverseer(id, overseerPersonId, actorPersonId).messageOrNull()
                if (problem == null) done++ else if (firstProblem == null) firstProblem = problem
            }
            onResult(done, firstProblem)
        }
    }
}
