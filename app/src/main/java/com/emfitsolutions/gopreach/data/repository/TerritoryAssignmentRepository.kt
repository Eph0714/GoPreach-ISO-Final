package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.TerritoryAssignment
import com.emfitsolutions.gopreach.data.model.TerritoryAssignmentBarangay
import com.emfitsolutions.gopreach.data.remote.SyncApi
import com.emfitsolutions.gopreach.data.sync.ConnectivityObserver
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.SyncEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val ASSIGNMENTS_COLLECTION = "territoryAssignments"
private const val BARANGAYS_COLLECTION = "territoryAssignmentBarangays"

/** A single save stays well under the server's per-transaction limit, so the caller gets this app's own clear message instead of a
 * cryptic server-side limit error (see [TerritoryAssignmentResult.Error] callers). */
const val MAX_BARANGAYS_PER_SAVE = 400

/** One municipality's desired barangay set within a [TerritoryAssignmentRepository.saveGroupTerritoryForProvince] session. */
data class MunicipalitySelection(
    val provinceId: Int,
    val provinceName: String,
    val muncityId: Int,
    val muncityName: String,
    val barangays: List<PsgcOption>,
)

sealed class TerritoryAssignmentResult {
    data class Success(val assignmentId: String) : TerritoryAssignmentResult()

    /** A barangay in this save attempt is already claimed by a different assignment — the server's own conflict check (not just the
     * wizard's client-side "already taken" hint) caught it. */
    data class Conflict(val barangayName: String, val takenByGroupName: String) : TerritoryAssignmentResult()

    data class Offline(
        val message: String = "An internet connection is required to save a territory assignment. Connect and try again.",
    ) : TerritoryAssignmentResult()

    data class Error(val message: String) : TerritoryAssignmentResult()
}

/**
 * Territory Assignment module — assigns every barangay of one municipality to exactly one FS Group per congregation. The write methods
 * go **straight to the server**, not through the offline queue: the thing that must be atomic is not "did my write land" (which the
 * queue guarantees, eventually) but "did nobody else's write land on the same barangay between my read and my write" — exactly what a
 * server transaction provides and a queued write cannot. The server serializes the claims, so two admins can never take the same
 * barangay. It answers 200 / 409 {barangayName, takenByGroupName} / 403 / 400.
 *
 * Each barangay claim's document id is deterministic — `"${congregationId}_${barangayId}"` (see [TerritoryAssignmentBarangay]).
 */
class TerritoryAssignmentRepository(
    private val offline: OfflineFirestoreRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val auditLogRepository: AuditLogRepository,
    private val syncApi: SyncApi,
    private val syncEngine: SyncEngine,
) {
    private suspend fun viaServer(path: String, body: JsonObject, resultId: String, auditAction: String, congregationId: String, actorPersonId: String, details: String): TerritoryAssignmentResult {
        val reply = try { syncApi.postJson(path, body) } catch (e: Exception) { return TerritoryAssignmentResult.Error(e.message ?: "Couldn't reach the server.") }
        val message = (reply.body?.get("message") as? JsonPrimitive)?.content
        return when (reply.status) {
            200 -> {
                runCatching { syncEngine.syncOnce() }
                auditLogRepository.log(actorPersonId = actorPersonId, action = auditAction, targetType = "TerritoryAssignment", targetId = resultId, congregationId = congregationId, details = details)
                TerritoryAssignmentResult.Success(resultId)
            }
            409 -> TerritoryAssignmentResult.Conflict(
                (reply.body?.get("barangayName") as? JsonPrimitive)?.content ?: "a barangay",
                (reply.body?.get("takenByGroupName") as? JsonPrimitive)?.content ?: "another group",
            )
            else -> TerritoryAssignmentResult.Error(message ?: "The server did not allow that change.")
        }
    }

    fun observeAssignments(): Flow<List<TerritoryAssignment>> = offline.observeCollection(ASSIGNMENTS_COLLECTION)
    fun observeBarangayClaims(): Flow<List<TerritoryAssignmentBarangay>> = offline.observeCollection(BARANGAYS_COLLECTION)

    /**
     * "A single Field Service Group may cover multiple municipalities" — the group-level save. Takes the full desired barangay set for
     * every municipality this Group should hold **within one province** ([municipalities] is a declarative end-state, not a delta) and
     * the server applies every create / update / delete — assignment headers and barangay claims — in one transaction. Scoped to one
     * province per call so a save can never touch this Group's municipalities in a different province.
     */
    suspend fun saveGroupTerritoryForProvince(
        congregationId: String,
        groupId: String,
        groupName: String,
        provinceId: Int,
        provinceName: String,
        municipalities: List<MunicipalitySelection>,
        actorPersonId: String,
    ): TerritoryAssignmentResult {
        if (!connectivityObserver.isOnline()) return TerritoryAssignmentResult.Offline()
        val keep = municipalities.filter { it.barangays.isNotEmpty() }
        if (keep.isEmpty()) return TerritoryAssignmentResult.Error("Select at least one barangay.")
        if (keep.sumOf { it.barangays.size } > MAX_BARANGAYS_PER_SAVE) {
            return TerritoryAssignmentResult.Error("You can assign at most $MAX_BARANGAYS_PER_SAVE barangays in one save. Split this into two assignments.")
        }
        val body = buildJsonObject {
            put("congregationId", congregationId); put("groupId", groupId); put("groupName", groupName)
            put("provinceId", provinceId); put("provinceName", provinceName)
            put("municipalities", buildJsonArray {
                keep.forEach { m ->
                    add(buildJsonObject {
                        put("muncityId", m.muncityId); put("muncityName", m.muncityName)
                        put("barangays", buildJsonArray {
                            m.barangays.forEach { b -> add(buildJsonObject { put("id", b.id); put("name", b.name) }) }
                        })
                    })
                }
            })
        }
        return viaServer("/v1/territory/save", body, groupId, "EDIT_TERRITORY_ASSIGNMENT", congregationId, actorPersonId, "group=$groupName province=$provinceName municipalities=${keep.size} barangays=${keep.sumOf { it.barangays.size }}")
    }

    /** Removes every municipality this Group holds in [provinceId] — the card-level "remove entire assignment" action. */
    suspend fun removeGroupTerritory(
        congregationId: String,
        groupId: String,
        provinceId: Int,
        actorPersonId: String,
    ): TerritoryAssignmentResult {
        if (!connectivityObserver.isOnline()) return TerritoryAssignmentResult.Offline()
        val body = buildJsonObject { put("congregationId", congregationId); put("groupId", groupId); put("provinceId", provinceId) }
        return viaServer("/v1/territory/remove-group", body, groupId, "REMOVE_TERRITORY_ASSIGNMENT", congregationId, actorPersonId, "group=$groupId province=$provinceId")
    }

    /** Hard delete — see [TerritoryAssignment]'s own doc comment for why this module has no Inactive state: an assignment that still
     * "existed" would keep every one of its barangays unavailable to every other Group. */
    suspend fun removeAssignment(assignmentId: String, congregationId: String, actorPersonId: String): TerritoryAssignmentResult {
        if (!connectivityObserver.isOnline()) return TerritoryAssignmentResult.Offline()
        val body = buildJsonObject { put("assignmentId", assignmentId) }
        return viaServer("/v1/territory/remove", body, assignmentId, "REMOVE_TERRITORY_ASSIGNMENT", congregationId, actorPersonId, "assignment=$assignmentId")
    }
}
