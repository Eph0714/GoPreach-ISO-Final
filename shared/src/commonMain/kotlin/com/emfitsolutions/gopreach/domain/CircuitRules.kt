package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.AccountStatus
import com.emfitsolutions.gopreach.data.model.CircuitCode
import com.emfitsolutions.gopreach.data.model.CongregationCircuit
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Permission
import com.emfitsolutions.gopreach.data.model.RecordStatus

/**
 * Circuit Overseer module — the assignment rules, as pure functions so the UI
 * (what to *offer*) and the transaction service (what to *accept*) read the
 * exact same definition. The service re-checks every one of these against the
 * live server documents inside its transaction; these only decide what the
 * screens show from the local cache.
 *
 * [links] is every `congregationCircuits` document keyed by congregation id.
 */
object CircuitRules {

    /** A Circuit Overseer's grant is fixed: view-only access to the reports/records of the
     * assigned congregations. Nothing here can change data. */
    val OVERSEER_PERMISSIONS: List<Permission> = listOf(
        Permission.VIEW_CONGREGATIONS,
        Permission.VIEW_GROUPS,
        Permission.VIEW_ELDERS,
        Permission.VIEW_PUBLISHERS,
        Permission.PRINT_REPORTS,
        Permission.EXPORT_REPORTS,
    )

    /** Active codes nobody else holds — plus [forPersonId]'s own code, so an edit form can still show it. */
    fun availableCodes(codes: List<CircuitCode>, forPersonId: String?): List<CircuitCode> =
        codes.filter { it.status == RecordStatus.ACTIVE && (it.overseerPersonId == null || it.overseerPersonId == forPersonId) }
            .sortedBy { it.code }

    /** Owner of [congregationId], or null if nobody holds it. */
    fun ownerOf(links: Map<String, CongregationCircuit>, congregationId: String): String? =
        links[congregationId]?.circuitOverseerPersonId?.takeIf { it.isNotBlank() }

    /** Congregations a form for [forPersonId] may tick: free ones, and the ones that person already holds. */
    fun availableCongregations(
        congregations: List<Congregation>,
        links: Map<String, CongregationCircuit>,
        forPersonId: String?,
    ): List<Congregation> = congregations
        .filter { c ->
            val owner = ownerOf(links, c.id)
            (owner == null || owner == forPersonId) && (c.status == RecordStatus.ACTIVE || owner == forPersonId)
        }
        .sortedBy { it.name }

    /** Congregations owned by someone *else* — shown disabled, never selectable. */
    fun takenCongregations(
        congregations: List<Congregation>,
        links: Map<String, CongregationCircuit>,
        forPersonId: String?,
    ): List<Congregation> = congregations
        .filter { c -> ownerOf(links, c.id).let { it != null && it != forPersonId } }
        .sortedBy { it.name }

    /** Why [congregation] may NOT be given to [overseerPersonId], or null if it may. */
    fun congregationAssignmentProblem(
        congregation: Congregation,
        links: Map<String, CongregationCircuit>,
        overseerPersonId: String,
        overseerStatus: AccountStatus,
        code: CircuitCode?,
        ownerName: (String) -> String = { it },
    ): String? {
        val owner = ownerOf(links, congregation.id)
        return when {
            overseerStatus != AccountStatus.ACTIVE -> "That Circuit Overseer account is not active."
            code == null -> "That Circuit Overseer has no Circuit Code yet."
            code.status != RecordStatus.ACTIVE -> "Circuit Code ${code.code} is inactive."
            code.overseerPersonId != null && code.overseerPersonId != overseerPersonId ->
                "Circuit Code ${code.code} belongs to a different Circuit Overseer."
            owner != null && owner != overseerPersonId ->
                "${congregation.name} is already assigned to ${ownerName(owner)}. Remove it from that Circuit Overseer first."
            congregation.status != RecordStatus.ACTIVE && owner != overseerPersonId -> "${congregation.name} is inactive."
            else -> null
        }
    }

    /** Migration helper — congregations that still need an overseer/code before validation becomes mandatory. */
    fun unassigned(congregations: List<Congregation>, links: Map<String, CongregationCircuit>): List<Congregation> =
        congregations.filter { c -> links[c.id]?.let { it.circuitOverseerPersonId.isBlank() || it.circuitCode.isBlank() } ?: true }
            .sortedBy { it.name }
}
