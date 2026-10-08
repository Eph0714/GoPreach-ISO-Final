package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.CircuitCode
import com.emfitsolutions.gopreach.data.model.CongregationCircuit
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.ScopeType
import com.emfitsolutions.gopreach.data.model.UserAccessGrant
import com.emfitsolutions.gopreach.data.remote.SyncApi
import com.emfitsolutions.gopreach.data.sync.ConnectivityObserver
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.domain.CircuitRules
import com.emfitsolutions.gopreach.platform.nowMillis
import kotlinx.coroutines.flow.first

/**
 * [CircuitAssignmentService] for the Hostinger backend: the same checks as the Firestore transactions, made against this device's
 * synchronized copy, then the changed documents pushed in ONE request. The server re-validates every document with its port of
 * firestore.rules (only the Super-Admin writes these collections), so a stale device can at worst be refused, never corrupt the links.
 */
class BackendCircuitAssignmentService(
    api: SyncApi,
    private val offline: OfflineFirestoreRepository,
    connectivity: ConnectivityObserver,
    private val audit: AuditLogRepository,
) : CircuitAssignmentService {
    private val writer = ServerWriter(api, connectivity)
    private val codes = "circuitCodes"
    private val links = "congregationCircuits"
    private val grants = "userAccessGrants"
    private val congregations = "congregations"

    private suspend fun allLinks(): List<CongregationCircuit> = offline.observeCollection<CongregationCircuit>(links).first()

    private suspend fun finish(action: String, target: String, actor: String, details: String?, result: CircuitResult): CircuitResult {
        if (result is CircuitResult.Success) audit.log(actorPersonId = actor, action = action, targetType = "Circuit", targetId = target, details = details)
        return result
    }

    override suspend fun createCode(code: String, description: String, actorPersonId: String): CircuitResult {
        if (offline.get<CircuitCode>(codes, code) != null) return CircuitResult.Conflict("Circuit Code $code already exists.")
        val doc = CircuitCode(
            id = code, code = code, description = description.trim(), status = RecordStatus.ACTIVE,
            overseerPersonId = null, createdAt = nowMillis(), createdByPersonId = actorPersonId,
        )
        val result = writer.push(listOf(writer.op(codes, code, doc)))
        if (result is CircuitResult.Success) offline.cacheFromServer(codes, code, doc)
        return finish("CREATE_CIRCUIT_CODE", code, actorPersonId, null, result)
    }

    override suspend fun updateCode(code: String, description: String, status: RecordStatus, actorPersonId: String): CircuitResult {
        val cur = offline.get<CircuitCode>(codes, code) ?: return CircuitResult.Conflict("Circuit Code $code no longer exists.")
        val doc = cur.copy(description = description.trim(), status = status)
        val result = writer.push(listOf(writer.op(codes, code, doc)))
        if (result is CircuitResult.Success) offline.cacheFromServer(codes, code, doc)
        return finish("UPDATE_CIRCUIT_CODE", code, actorPersonId, "status: $status", result)
    }

    override suspend fun deleteCode(code: String, actorPersonId: String): CircuitResult {
        val cur = offline.get<CircuitCode>(codes, code) ?: return CircuitResult.Success
        if (allLinks().any { it.circuitCode == code }) return CircuitResult.Conflict("Circuit Code $code is still assigned to congregations. Remove those assignments first.")
        if (cur.overseerPersonId != null) return CircuitResult.Conflict("Circuit Code $code is assigned to a Circuit Overseer. Remove that assignment first.")
        val result = writer.push(listOf(writer.deleteOp(codes, code)))
        if (result is CircuitResult.Success) offline.deleteFromServer(codes, code)
        return finish("DELETE_CIRCUIT_CODE", code, actorPersonId, null, result)
    }

    override suspend fun saveOverseerAssignment(personId: String, circuitCode: String, congregationIds: Set<String>, actorPersonId: String): CircuitResult {
        val grant = offline.get<UserAccessGrant>(grants, personId)
        val previousCode = grant?.circuitCode
        val previousScope = grant?.scopeCongregationIds.orEmpty().toSet()
        val code = offline.get<CircuitCode>(codes, circuitCode) ?: return CircuitResult.Conflict("Circuit Code $circuitCode doesn't exist.")
        if (code.status != RecordStatus.ACTIVE) return CircuitResult.Conflict("Circuit Code $circuitCode is inactive.")
        if (code.overseerPersonId != null && code.overseerPersonId != personId) {
            return CircuitResult.Conflict("Circuit Code $circuitCode is already assigned to another Circuit Overseer.")
        }
        val linkDocs = allLinks()
        val heldNow = linkDocs.filter { it.circuitOverseerPersonId == personId }.map { it.congregationId }.toSet()
        for (id in congregationIds) {
            val congregation = offline.get<Congregation>(congregations, id) ?: return CircuitResult.Conflict("A selected congregation no longer exists.")
            val name = congregation.name.ifBlank { "This congregation" }
            val owner = linkDocs.firstOrNull { it.congregationId == id }?.circuitOverseerPersonId
            if (owner != null && owner != personId) return CircuitResult.Conflict("$name is already assigned to another Circuit Overseer. Remove it from them first.")
            if (owner != personId && congregation.status != RecordStatus.ACTIVE) return CircuitResult.Conflict("$name is inactive.")
        }
        val now = nowMillis()
        val touched = congregationIds + previousScope + heldNow
        val ops = buildList {
            add(writer.op(codes, circuitCode, code.copy(overseerPersonId = personId)))
            if (previousCode != null && previousCode != circuitCode) {
                offline.get<CircuitCode>(codes, previousCode)?.takeIf { it.overseerPersonId == personId }
                    ?.let { add(writer.op(codes, previousCode, it.copy(overseerPersonId = null))) }
            }
            for (id in congregationIds) {
                add(
                    writer.op(
                        links, id,
                        CongregationCircuit(id = id, congregationId = id, circuitOverseerPersonId = personId, circuitCode = circuitCode, assignedAt = now, assignedByPersonId = actorPersonId),
                    ),
                )
            }
            for (id in touched - congregationIds) {
                if (linkDocs.firstOrNull { it.congregationId == id }?.circuitOverseerPersonId == personId) add(writer.deleteOp(links, id))
            }
            add(
                writer.op(
                    grants, personId,
                    UserAccessGrant(
                        personId = personId, permissions = CircuitRules.OVERSEER_PERMISSIONS.map { it.name }, scopeType = ScopeType.SELECTED_CONGREGATIONS.name,
                        scopeCongregationIds = congregationIds.toList(), scopeGroupIds = emptyList(), circuitCode = circuitCode,
                        createdByPersonId = grant?.createdByPersonId ?: actorPersonId, createdAt = grant?.createdAt ?: now,
                        lastEditedByPersonId = actorPersonId, lastEditedAt = now,
                    ),
                ),
            )
        }
        return finish("SET_CIRCUIT_ASSIGNMENT", personId, actorPersonId, "code: $circuitCode, congregations: ${congregationIds.size}", writer.push(ops))
    }

    override suspend fun setCongregationOverseer(congregationId: String, overseerPersonId: String?, actorPersonId: String, allowMove: Boolean): CircuitResult {
        val link = offline.get<CongregationCircuit>(links, congregationId)
        val currentOwner = link?.circuitOverseerPersonId
        val action = "SET_CONGREGATION_OVERSEER"
        val details = "overseer: ${overseerPersonId ?: "none"}"
        if (overseerPersonId == null) {
            if (currentOwner == null) return CircuitResult.Success
            val old = offline.get<UserAccessGrant>(grants, currentOwner)
            val ops = buildList {
                add(writer.deleteOp(links, congregationId))
                if (old != null) add(writer.op(grants, currentOwner, old.copy(scopeCongregationIds = old.scopeCongregationIds - congregationId)))
            }
            return finish(action, congregationId, actorPersonId, details, writer.push(ops))
        }
        val congregation = offline.get<Congregation>(congregations, congregationId) ?: return CircuitResult.Conflict("That congregation no longer exists.")
        val name = congregation.name.ifBlank { "This congregation" }
        val moving = currentOwner != null && currentOwner != overseerPersonId
        if (moving && !allowMove) return CircuitResult.Conflict("$name is already assigned to another Circuit Overseer. Remove it from them first.")
        val grant = offline.get<UserAccessGrant>(grants, overseerPersonId)
        val code = grant?.circuitCode ?: return CircuitResult.Conflict("That Circuit Overseer has no Circuit Code yet.")
        val codeDoc = offline.get<CircuitCode>(codes, code)
        if (codeDoc?.status != RecordStatus.ACTIVE) return CircuitResult.Conflict("Circuit Code $code is inactive.")
        if (codeDoc.overseerPersonId != overseerPersonId) return CircuitResult.Conflict("Circuit Code $code does not belong to that Circuit Overseer.")
        if (currentOwner == null && congregation.status != RecordStatus.ACTIVE) return CircuitResult.Conflict("$name is inactive.")
        val ops = buildList {
            add(
                writer.op(
                    links, congregationId,
                    CongregationCircuit(id = congregationId, congregationId = congregationId, circuitOverseerPersonId = overseerPersonId, circuitCode = code, assignedAt = nowMillis(), assignedByPersonId = actorPersonId),
                ),
            )
            if (congregationId !in grant.scopeCongregationIds) add(writer.op(grants, overseerPersonId, grant.copy(scopeCongregationIds = grant.scopeCongregationIds + congregationId)))
            if (moving) {
                offline.get<UserAccessGrant>(grants, currentOwner!!)
                    ?.let { old -> add(writer.op(grants, currentOwner, old.copy(scopeCongregationIds = old.scopeCongregationIds - congregationId))) }
            }
        }
        return finish(action, congregationId, actorPersonId, details, writer.push(ops))
    }

    override suspend fun releaseOverseer(personId: String, actorPersonId: String): CircuitResult {
        if (allLinks().any { it.circuitOverseerPersonId == personId }) {
            return CircuitResult.Conflict("This Circuit Overseer still has assigned congregations. Reassign or unassign them first.")
        }
        val grant = offline.get<UserAccessGrant>(grants, personId)
        if (grant != null && grant.scopeCongregationIds.isNotEmpty()) {
            return CircuitResult.Conflict("This Circuit Overseer still has assigned congregations. Reassign or unassign them first.")
        }
        val ops = buildList {
            grant?.circuitCode?.let { c ->
                offline.get<CircuitCode>(codes, c)?.takeIf { it.overseerPersonId == personId }?.let { add(writer.op(codes, c, it.copy(overseerPersonId = null))) }
            }
            if (grant != null) add(writer.deleteOp(grants, personId))
        }
        if (ops.isEmpty()) return CircuitResult.Success
        return finish("RELEASE_CIRCUIT_OVERSEER", personId, actorPersonId, null, writer.push(ops))
    }
}
