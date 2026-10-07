package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.CircuitCode
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.repository.CircuitAssignmentService
import com.emfitsolutions.gopreach.data.repository.CircuitCodeRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.messageOrNull
import com.emfitsolutions.gopreach.data.repository.validateNewCircuitCode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CircuitCodeRow(
    val code: CircuitCode,
    /** Full name of the Circuit Overseer holding this code, if any. */
    val overseerName: String?,
    val congregationCount: Int,
)

/** "Circuit Codes" module — Super-Admin manages the list; every write is a server transaction (see [CircuitAssignmentService]). */
class CircuitCodesViewModel(
    codeRepository: CircuitCodeRepository,
    personRepository: PersonRepository,
    private val service: CircuitAssignmentService,
) : ViewModel() {

    val rows: StateFlow<List<CircuitCodeRow>> = combine(
        codeRepository.observeAll(),
        codeRepository.observeLinks(),
        personRepository.observeAll(),
    ) { codes, links, people ->
        val peopleById = people.associateBy { it.id }
        codes.map { code ->
            CircuitCodeRow(
                code = code,
                overseerName = code.overseerPersonId?.let { peopleById[it]?.fullName ?: "Unknown account" },
                congregationCount = links.values.count { it.circuitCode == code.code },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** [onResult] gets null on success, or the message to show. */
    fun create(rawCode: String, description: String, actorPersonId: String, onResult: (String?) -> Unit) {
        val (code, problem) = validateNewCircuitCode(rawCode)
        if (code == null) {
            onResult(problem)
            return
        }
        viewModelScope.launch { onResult(service.createCode(code, description, actorPersonId).messageOrNull()) }
    }

    fun update(row: CircuitCodeRow, description: String, status: RecordStatus, actorPersonId: String, onResult: (String?) -> Unit) {
        viewModelScope.launch { onResult(service.updateCode(row.code.code, description, status, actorPersonId).messageOrNull()) }
    }

    /** Refused (with a message) while the code is held by an overseer or carried by a congregation. */
    fun delete(row: CircuitCodeRow, actorPersonId: String, onResult: (String?) -> Unit) {
        if (row.code.overseerPersonId != null) {
            onResult("Circuit Code ${row.code.code} is assigned to ${row.overseerName ?: "a Circuit Overseer"}. Remove that assignment first.")
            return
        }
        if (row.congregationCount > 0) {
            onResult("Circuit Code ${row.code.code} is still assigned to ${row.congregationCount} congregation(s). Remove those assignments first.")
            return
        }
        viewModelScope.launch { onResult(service.deleteCode(row.code.code, actorPersonId).messageOrNull()) }
    }
}
