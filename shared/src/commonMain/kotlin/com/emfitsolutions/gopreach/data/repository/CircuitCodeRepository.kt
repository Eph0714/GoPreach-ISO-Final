package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.CircuitCode
import com.emfitsolutions.gopreach.data.model.CongregationCircuit
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

private const val CODES = "circuitCodes"
private const val LINKS = "congregationCircuits"

/**
 * Read side of the Circuit Code (`circuitCodes/{CODE}`) and Congregation →
 * Circuit Overseer (`congregationCircuits/{congregationId}`) documents. Every
 * *write* — create, edit, delete, claim by an overseer — goes through
 * [CircuitAssignmentService], which runs it as a server-side transaction; this
 * class only observes the mirrored copies.
 */
class CircuitCodeRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    fun observeAll(): Flow<List<CircuitCode>> =
        offline.observeCollection<CircuitCode>(CODES).map { list -> list.sortedBy { it.code } }

    /** Every congregation → overseer link, keyed by congregation id. */
    fun observeLinks(): Flow<Map<String, CongregationCircuit>> =
        offline.observeCollection<CongregationCircuit>(LINKS).map { list -> list.associateBy { it.congregationId.ifBlank { it.id } } }

    fun startRemoteSync(): Flow<Unit> = merge(
        remote.mirror(CODES, CircuitCode::class) { it.id },
        remote.mirror(LINKS, CongregationCircuit::class) { it.id },
    )
}
