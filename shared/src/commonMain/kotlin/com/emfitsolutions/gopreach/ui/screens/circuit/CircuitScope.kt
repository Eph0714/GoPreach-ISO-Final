package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.emfitsolutions.gopreach.data.model.ScopeType
import com.emfitsolutions.gopreach.data.model.UserAccessGrant
import com.emfitsolutions.gopreach.data.repository.UserAccessGrantRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** "Every circuit" — only the Super-Admin's Circuit Overseer Management ever uses it. */
const val CIRCUIT_SCOPE_ALL = "*"

/**
 * Whose circuit the Circuit Overseer screens are showing. A Circuit Overseer always sees their own (their person id);
 * the Super-Admin picks any overseer's circuit — or [CIRCUIT_SCOPE_ALL] — in Circuit Overseer Management, and the very
 * same screens then show that choice. The value only decides what the *screens* ask for: what an account can actually
 * read is still decided by the security rules (a Circuit Overseer's device never even receives another circuit's data).
 */
object CircuitScopeStore {
    var selected: String by mutableStateOf(CIRCUIT_SCOPE_ALL)
        private set

    /** The congregation the overseer chose for the detailed record lists (Publishers, Elders & MS, Field Service Report); null = none yet. */
    var congregation: String? by mutableStateOf(null)
        private set

    /** Switching circuit drops the congregation choice — it belonged to the previous circuit. */
    fun selectCircuit(scopeId: String) {
        if (scopeId != selected) congregation = null
        selected = scopeId
    }

    fun selectCongregation(congregationId: String?) {
        congregation = congregationId
    }

    /** The scope for the signed-in account: the overseer themself, or the Super-Admin's current choice. */
    fun scopeFor(isSuperAdmin: Boolean, personId: String): String = if (isSuperAdmin) selected else personId
}

/** The grant whose congregations a scope covers; "all circuits" is a synthetic all-congregations grant. */
internal fun UserAccessGrantRepository.observeCircuitScope(scopeId: String): Flow<UserAccessGrant?> =
    if (scopeId == CIRCUIT_SCOPE_ALL) flowOf(UserAccessGrant(personId = CIRCUIT_SCOPE_ALL, scopeType = ScopeType.ALL_CONGREGATIONS.name))
    else observeForPerson(scopeId)
