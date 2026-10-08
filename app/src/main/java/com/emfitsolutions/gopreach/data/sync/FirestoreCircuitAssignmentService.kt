package com.emfitsolutions.gopreach.data.sync

import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.ScopeType
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.CircuitAssignmentService
import com.emfitsolutions.gopreach.data.repository.CircuitResult
import com.emfitsolutions.gopreach.domain.CircuitRules
import com.emfitsolutions.gopreach.platform.nowMillis
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

private const val CODES = "circuitCodes"
private const val LINKS = "congregationCircuits"
private const val CONGREGATIONS = "congregations"
private const val GRANTS = "userAccessGrants"

/** Thrown inside a transaction lambda so none of its writes apply; never escapes this class. */
private class CircuitConflict(message: String) : Exception(message)

/**
 * Firestore implementation of [CircuitAssignmentService]. Every method is one
 * `runTransaction`: **all reads first, then all writes**, so a concurrent
 * edit of the same code / congregation by another admin makes Firestore retry
 * this transaction against the new state, where the ownership check below then
 * fails with a conflict instead of silently double-assigning.
 *
 * Why the database itself guarantees the rules:
 *  - a Circuit Code is `circuitCodes/{CODE}` — one document per code;
 *  - a congregation's overseer is `congregationCircuits/{congregationId}` — one document per congregation;
 *  - claiming either is a transaction on that single document.
 */
class FirestoreCircuitAssignmentService(
    private val firestore: FirebaseFirestore,
    private val connectivityObserver: ConnectivityObserver,
    private val auditLogRepository: AuditLogRepository,
) : CircuitAssignmentService {

    private suspend fun run(
        action: String,
        targetId: String,
        actor: String,
        details: String? = null,
        block: suspend () -> Unit,
    ): CircuitResult {
        if (!connectivityObserver.isOnline()) return CircuitResult.Offline()
        return try {
            block()
            auditLogRepository.log(actorPersonId = actor, action = action, targetType = "Circuit", targetId = targetId, details = details)
            CircuitResult.Success
        } catch (e: Exception) {
            // Firestore wraps whatever a transaction lambda throws; unwrap ours.
            val conflict = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<CircuitConflict>().firstOrNull()
            if (conflict != null) CircuitResult.Conflict(conflict.message ?: "That assignment conflicts with an existing one.")
            else CircuitResult.Error(friendlyServerError(e))
        }
    }

    override suspend fun createCode(code: String, description: String, actorPersonId: String): CircuitResult =
        run("CREATE_CIRCUIT_CODE", code, actorPersonId) {
            val ref = firestore.collection(CODES).document(code)
            firestore.runTransaction { txn ->
                if (txn.get(ref).exists()) throw CircuitConflict("Circuit Code $code already exists.")
                txn.set(
                    ref,
                    mapOf(
                        "code" to code,
                        "description" to description.trim(),
                        "status" to RecordStatus.ACTIVE.name,
                        "overseerPersonId" to null,
                        "createdAt" to nowMillis(),
                        "createdByPersonId" to actorPersonId,
                    ),
                )
                null
            }.await()
        }

    override suspend fun updateCode(code: String, description: String, status: RecordStatus, actorPersonId: String): CircuitResult =
        run("UPDATE_CIRCUIT_CODE", code, actorPersonId, details = "status: $status") {
            val ref = firestore.collection(CODES).document(code)
            firestore.runTransaction { txn ->
                if (!txn.get(ref).exists()) throw CircuitConflict("Circuit Code $code no longer exists.")
                txn.update(ref, mapOf("description" to description.trim(), "status" to status.name))
                null
            }.await()
        }

    override suspend fun deleteCode(code: String, actorPersonId: String): CircuitResult =
        run("DELETE_CIRCUIT_CODE", code, actorPersonId) {
            val ref = firestore.collection(CODES).document(code)
            // A transaction can't run a query, so look for congregations still carrying this code first;
            // the overseer pointer re-read inside the transaction is the authoritative guard for the other half.
            val stillUsed = firestore.collection(LINKS).whereEqualTo("circuitCode", code).limit(1).get().await()
            if (!stillUsed.isEmpty) {
                throw CircuitConflict("Circuit Code $code is still assigned to congregations. Remove those assignments first.")
            }
            firestore.runTransaction { txn ->
                val snap = txn.get(ref)
                if (!snap.exists()) return@runTransaction null
                if (snap.getString("overseerPersonId") != null) {
                    throw CircuitConflict("Circuit Code $code is assigned to a Circuit Overseer. Remove that assignment first.")
                }
                txn.delete(ref)
                null
            }.await()
        }

    @Suppress("UNCHECKED_CAST")
    override suspend fun saveOverseerAssignment(
        personId: String,
        circuitCode: String,
        congregationIds: Set<String>,
        actorPersonId: String,
    ): CircuitResult = run(
        "SET_CIRCUIT_ASSIGNMENT", personId, actorPersonId,
        details = "code: $circuitCode, congregations: ${congregationIds.size}",
    ) {
        val grantRef = firestore.collection(GRANTS).document(personId)
        val codeRef = firestore.collection(CODES).document(circuitCode)
        // Links this overseer holds *right now*, found by query (a transaction can't query); each one is
        // re-read inside the transaction below, so a change in between just makes the check use fresh data.
        val heldNow = firestore.collection(LINKS).whereEqualTo("circuitOverseerPersonId", personId).get().await()
            .documents.map { it.id }.toSet()

        firestore.runTransaction { txn ->
            // ---- reads ----
            val grantSnap = txn.get(grantRef)
            val previousCode = grantSnap.getString("circuitCode")
            val previousScope = (grantSnap.get("scopeCongregationIds") as? List<String>).orEmpty().toSet()

            val codeSnap = txn.get(codeRef)
            if (!codeSnap.exists()) throw CircuitConflict("Circuit Code $circuitCode doesn't exist.")
            if (codeSnap.getString("status") != RecordStatus.ACTIVE.name) throw CircuitConflict("Circuit Code $circuitCode is inactive.")
            val codeOwner = codeSnap.getString("overseerPersonId")
            if (codeOwner != null && codeOwner != personId) {
                throw CircuitConflict("Circuit Code $circuitCode is already assigned to another Circuit Overseer.")
            }
            val previousCodeSnap = if (previousCode != null && previousCode != circuitCode) {
                txn.get(firestore.collection(CODES).document(previousCode))
            } else null

            val touched = congregationIds + previousScope + heldNow
            val linkSnaps = touched.associateWith { txn.get(firestore.collection(LINKS).document(it)) }
            val congregationSnaps = congregationIds.associateWith { txn.get(firestore.collection(CONGREGATIONS).document(it)) }

            // ---- checks ----
            for (id in congregationIds) {
                val congregation = congregationSnaps.getValue(id)
                if (!congregation.exists()) throw CircuitConflict("A selected congregation no longer exists.")
                val name = congregation.getString("name") ?: "This congregation"
                val owner = linkSnaps.getValue(id).takeIf { it.exists() }?.getString("circuitOverseerPersonId")
                if (owner != null && owner != personId) {
                    throw CircuitConflict("$name is already assigned to another Circuit Overseer. Remove it from them first.")
                }
                if (owner != personId && congregation.getString("status") != RecordStatus.ACTIVE.name) {
                    throw CircuitConflict("$name is inactive.")
                }
            }

            // ---- writes ----
            txn.update(codeRef, "overseerPersonId", personId)
            if (previousCodeSnap != null && previousCodeSnap.exists() && previousCodeSnap.getString("overseerPersonId") == personId) {
                txn.update(previousCodeSnap.reference, "overseerPersonId", null)
            }
            val now = nowMillis()
            for (id in congregationIds) {
                txn.set(
                    linkSnaps.getValue(id).reference,
                    mapOf(
                        "congregationId" to id,
                        "circuitOverseerPersonId" to personId,
                        "circuitCode" to circuitCode,
                        "assignedAt" to now,
                        "assignedByPersonId" to actorPersonId,
                    ),
                )
            }
            for (id in touched - congregationIds) {
                val link = linkSnaps.getValue(id)
                if (link.exists() && link.getString("circuitOverseerPersonId") == personId) txn.delete(link.reference)
            }
            val grant = mutableMapOf<String, Any?>(
                "personId" to personId,
                "permissions" to CircuitRules.OVERSEER_PERMISSIONS.map { it.name },
                "scopeType" to ScopeType.SELECTED_CONGREGATIONS.name,
                "scopeCongregationIds" to congregationIds.toList(),
                "scopeGroupIds" to emptyList<String>(),
                "circuitCode" to circuitCode,
                "lastEditedByPersonId" to actorPersonId,
                "lastEditedAt" to now,
            )
            if (!grantSnap.exists()) {
                grant["createdByPersonId"] = actorPersonId
                grant["createdAt"] = now
            }
            txn.set(grantRef, grant, SetOptions.merge())
            null
        }.await()
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun setCongregationOverseer(
        congregationId: String,
        overseerPersonId: String?,
        actorPersonId: String,
        allowMove: Boolean,
    ): CircuitResult = run(
        "SET_CONGREGATION_OVERSEER", congregationId, actorPersonId, details = "overseer: ${overseerPersonId ?: "none"}",
    ) {
        val linkRef = firestore.collection(LINKS).document(congregationId)
        val congregationRef = firestore.collection(CONGREGATIONS).document(congregationId)
        firestore.runTransaction { txn ->
            val link = txn.get(linkRef)
            val currentOwner = link.takeIf { it.exists() }?.getString("circuitOverseerPersonId")

            if (overseerPersonId == null) {
                // Unassign: delete the link and drop the congregation from the old owner's scope list.
                if (currentOwner != null) {
                    val oldGrantRef = firestore.collection(GRANTS).document(currentOwner)
                    val oldGrant = txn.get(oldGrantRef)
                    txn.delete(linkRef)
                    if (oldGrant.exists()) {
                        val remaining = (oldGrant.get("scopeCongregationIds") as? List<String>).orEmpty() - congregationId
                        txn.update(oldGrantRef, "scopeCongregationIds", remaining)
                    }
                }
                return@runTransaction null
            }

            val congregation = txn.get(congregationRef)
            if (!congregation.exists()) throw CircuitConflict("That congregation no longer exists.")
            val name = congregation.getString("name") ?: "This congregation"
            val moving = currentOwner != null && currentOwner != overseerPersonId
            if (moving && !allowMove) {
                throw CircuitConflict("$name is already assigned to another Circuit Overseer. Remove it from them first.")
            }
            val oldGrantRef = if (moving) firestore.collection(GRANTS).document(currentOwner!!) else null
            val oldGrant = oldGrantRef?.let { txn.get(it) }
            val grantRef = firestore.collection(GRANTS).document(overseerPersonId)
            val grant = txn.get(grantRef)
            val code = grant.getString("circuitCode")
                ?: throw CircuitConflict("That Circuit Overseer has no Circuit Code yet.")
            val codeSnap = txn.get(firestore.collection(CODES).document(code))
            if (codeSnap.getString("status") != RecordStatus.ACTIVE.name) throw CircuitConflict("Circuit Code $code is inactive.")
            if (codeSnap.getString("overseerPersonId") != overseerPersonId) {
                throw CircuitConflict("Circuit Code $code does not belong to that Circuit Overseer.")
            }
            if (currentOwner == null && congregation.getString("status") != RecordStatus.ACTIVE.name) {
                throw CircuitConflict("$name is inactive.")
            }
            val scope = (grant.get("scopeCongregationIds") as? List<String>).orEmpty()
            txn.set(
                linkRef,
                mapOf(
                    "congregationId" to congregationId,
                    "circuitOverseerPersonId" to overseerPersonId,
                    "circuitCode" to code,
                    "assignedAt" to nowMillis(),
                    "assignedByPersonId" to actorPersonId,
                ),
            )
            if (congregationId !in scope) txn.update(grantRef, "scopeCongregationIds", scope + congregationId)
            if (oldGrantRef != null && oldGrant != null && oldGrant.exists()) {
                txn.update(oldGrantRef, "scopeCongregationIds", (oldGrant.get("scopeCongregationIds") as? List<String>).orEmpty() - congregationId)
            }
            null
        }.await()
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun releaseOverseer(personId: String, actorPersonId: String): CircuitResult =
        run("RELEASE_CIRCUIT_OVERSEER", personId, actorPersonId) {
            val stillHeld = firestore.collection(LINKS).whereEqualTo("circuitOverseerPersonId", personId).limit(1).get().await()
            if (!stillHeld.isEmpty) {
                throw CircuitConflict("This Circuit Overseer still has assigned congregations. Reassign or unassign them first.")
            }
            val grantRef = firestore.collection(GRANTS).document(personId)
            firestore.runTransaction { txn ->
                val grant = txn.get(grantRef)
                val code = grant.getString("circuitCode")
                val codeSnap = code?.let { txn.get(firestore.collection(CODES).document(it)) }
                if (grant.exists() && (grant.get("scopeCongregationIds") as? List<String>).orEmpty().isNotEmpty()) {
                    throw CircuitConflict("This Circuit Overseer still has assigned congregations. Reassign or unassign them first.")
                }
                if (codeSnap != null && codeSnap.exists() && codeSnap.getString("overseerPersonId") == personId) {
                    txn.update(codeSnap.reference, "overseerPersonId", null)
                }
                if (grant.exists()) txn.delete(grantRef)
                null
            }.await()
        }
}
