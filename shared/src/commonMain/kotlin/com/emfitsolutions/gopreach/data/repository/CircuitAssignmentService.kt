package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.isValidCircuitCode
import com.emfitsolutions.gopreach.data.model.normalizeCircuitCode

sealed class CircuitResult {
    data object Success : CircuitResult()

    /** The server said no because of the one-to-one / one-to-many rules (taken code, taken congregation, still in use, ...). */
    data class Conflict(val message: String) : CircuitResult()

    data class Offline(
        val message: String = "An internet connection is required for this change. Connect and try again.",
    ) : CircuitResult()

    data class Error(val message: String) : CircuitResult()
}

/** The message a screen should show for a failed result; null for [CircuitResult.Success]. */
fun CircuitResult.messageOrNull(): String? = when (this) {
    is CircuitResult.Success -> null
    is CircuitResult.Conflict -> message
    is CircuitResult.Offline -> message
    is CircuitResult.Error -> message
}

/**
 * Every write that touches the Circuit Code ↔ Circuit Overseer ↔ Congregation
 * relationships. Unlike the app's normal queue-and-flush-later writes, each
 * method here is **one server-side transaction**: it re-reads the live
 * documents, checks the rules (a code has at most one overseer; a congregation
 * has at most one overseer), and only then writes. Two admins editing at the
 * same moment therefore cannot both win — the loser gets [CircuitResult.Conflict].
 * Same precedent as [TerritoryAssignmentRepository].
 */
interface CircuitAssignmentService {

    /** Creates `circuitCodes/{code}`; [CircuitResult.Conflict] if that code already exists. */
    suspend fun createCode(code: String, description: String, actorPersonId: String): CircuitResult

    /** Description / status only — the code itself and its overseer are never changed here. */
    suspend fun updateCode(code: String, description: String, status: RecordStatus, actorPersonId: String): CircuitResult

    /** Refused while an overseer holds the code or any congregation still carries it. */
    suspend fun deleteCode(code: String, actorPersonId: String): CircuitResult

    /**
     * The whole overseer assignment in one transaction: claim [circuitCode] (releasing the one
     * [personId] held before, if different), claim every id in [congregationIds] and release the ones
     * dropped from the list, and write the overseer's access grant to match.
     */
    suspend fun saveOverseerAssignment(
        personId: String,
        circuitCode: String,
        congregationIds: Set<String>,
        actorPersonId: String,
    ): CircuitResult

    /** Give one congregation to [overseerPersonId], or take it away (null). Refused if it already belongs to someone else — unless [allowMove] (the Congregation form),
     * which moves it from its current overseer to the new one in the same transaction. */
    suspend fun setCongregationOverseer(
        congregationId: String,
        overseerPersonId: String?,
        actorPersonId: String,
        allowMove: Boolean = false,
    ): CircuitResult

    /** Frees the overseer's circuit code and removes their grant. Refused while they still hold congregations. */
    suspend fun releaseOverseer(personId: String, actorPersonId: String): CircuitResult
}

/** The normalised code and no error, or no code and an error message. */
fun validateNewCircuitCode(raw: String): Pair<String?, String?> {
    val code = normalizeCircuitCode(raw)
    return when {
        code.isBlank() -> null to "Circuit Code is required."
        !isValidCircuitCode(code) ->
            null to "Circuit Code may only contain letters, numbers and dashes (2–20 characters), e.g. NT01."
        else -> code to null
    }
}
