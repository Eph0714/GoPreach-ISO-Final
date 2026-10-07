package com.emfitsolutions.gopreach.data.model

import com.emfitsolutions.gopreach.platform.DocumentId

/**
 * One Circuit Code (e.g. `NT01`) — Circuit Overseer module.
 *
 * Firestore collection: `circuitCodes/{CODE}` — **the document id IS the
 * upper-cased [code]**, so "Circuit Codes are unique" is enforced by the
 * database itself (a document id can exist only once), not by a client-side
 * check. [overseerPersonId] is the one Circuit Overseer holding this code;
 * it is only ever changed inside a transaction (see
 * [com.emfitsolutions.gopreach.data.repository.CircuitAssignmentService]).
 */
@kotlinx.serialization.Serializable
data class CircuitCode(
    @field:DocumentId val id: String = "",
    val code: String = "",
    val description: String = "",
    val status: RecordStatus = RecordStatus.ACTIVE,
    val overseerPersonId: String? = null,
    val createdAt: Long = 0L,
    val createdByPersonId: String = "",
)

/**
 * "This congregation belongs to this Circuit Overseer" — the Congregation →
 * Circuit Overseer / Circuit Code relationship.
 *
 * Firestore collection: `congregationCircuits/{congregationId}` — **keyed by
 * congregation id**, so "a congregation has at most one Circuit Overseer" is
 * structural: there is exactly one document that can say who owns it, and
 * taking it is a transaction on that one document. Kept as its own collection
 * (rather than two more fields on [Congregation]) so an ordinary congregation
 * edit — which rewrites the whole congregation document — can never overwrite
 * an assignment made a moment earlier by someone else, and so the existing
 * `congregations` security rules stay exactly as they were.
 * [circuitCode] is a denormalised copy of the overseer's own code, written in the
 * same transaction as [circuitOverseerPersonId] so the two can never disagree.
 */
@kotlinx.serialization.Serializable
data class CongregationCircuit(
    @field:DocumentId val id: String = "",
    val congregationId: String = "",
    val circuitOverseerPersonId: String = "",
    val circuitCode: String = "",
    val assignedAt: Long = 0L,
    val assignedByPersonId: String = "",
)

/** The only form a Circuit Code is ever stored or compared in. */
fun normalizeCircuitCode(raw: String): String = raw.trim().uppercase().replace(Regex("\\s+"), "")

/** Letters, digits and dashes only, 2–20 characters — keeps the code safe as a Firestore document id. */
fun isValidCircuitCode(code: String): Boolean = Regex("^[A-Z0-9][A-Z0-9-]{1,19}$").matches(code)
