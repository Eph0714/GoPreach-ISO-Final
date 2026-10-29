package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.CongregationCircuit
import com.emfitsolutions.gopreach.data.remote.SyncApi
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.SyncEngine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

sealed interface CoCongregationResult {
    /** [alreadyThere] is true when the server had this very request already (a retry): nothing new was added. */
    data class Saved(val congregation: Congregation, val alreadyThere: Boolean = false) : CoCongregationResult
    data class Failed(val message: String, val field: String? = null) : CoCongregationResult
}

/**
 * Congregation Management for the Circuit Overseer. The server (`/v1/co/congregations`) decides everything from the signed-in account: it assigns the
 * congregation to the caller, refuses a request that names another overseer, and refuses anything of another overseer's. This class only carries the
 * request, and keeps the device's own copy in step so the list shows the result at once.
 */
class CoCongregationService(
    private val api: SyncApi,
    private val offline: OfflineFirestoreRepository,
    private val syncEngine: SyncEngine,
) {
    /** Enrols a congregation under the signed-in Circuit Overseer. [requestId] stays the same for every retry of one form, so a repeat never adds a second congregation. */
    suspend fun enroll(
        name: String, code: String, province: String, cityMunicipality: String, barangay: String,
        languages: List<String>, requestId: String, coPersonId: String,
    ): CoCongregationResult = send("/v1/co/congregations", coPersonId, buildJsonObject {
        put("name", name); put("code", code); put("province", province); put("cityMunicipality", cityMunicipality); put("barangay", barangay)
        put("languages", JsonArray(languages.map { JsonPrimitive(it) })); put("requestId", requestId)
    })

    /** Edits the name, place and languages of one of this overseer's congregations (the code, status and assigned overseer are never sent). */
    suspend fun edit(
        congregationId: String, name: String, province: String, cityMunicipality: String, barangay: String, languages: List<String>, coPersonId: String,
    ): CoCongregationResult = send("/v1/co/congregations/$congregationId", coPersonId, buildJsonObject {
        put("name", name); put("province", province); put("cityMunicipality", cityMunicipality); put("barangay", barangay)
        put("languages", JsonArray(languages.map { JsonPrimitive(it) }))
    })

    private suspend fun send(path: String, coPersonId: String, body: JsonObject): CoCongregationResult {
        val reply = try {
            api.postJson(path, body)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return CoCongregationResult.Failed("Couldn't reach the server. Check your internet connection and try again.")
        }
        val json = reply.body
        if (reply.status !in 200..299 || json == null) {
            val message = json?.get("error")?.let { (it as? JsonPrimitive)?.contentOrNull }
            return CoCongregationResult.Failed(
                message ?: when (reply.status) {
                    401 -> "Your session has ended. Sign in again."
                    403 -> "This is not allowed for your account."
                    404 -> "That congregation was not found."
                    else -> "The congregation could not be saved. Please try again."
                },
                json?.get("field")?.let { (it as? JsonPrimitive)?.contentOrNull },
            )
        }
        val id = (json["id"] as? JsonPrimitive)?.contentOrNull ?: return CoCongregationResult.Failed("The server's answer could not be read.")
        fun str(k: String) = (json[k] as? JsonPrimitive)?.contentOrNull
        val congregation = Congregation(
            id = id, name = str("name").orEmpty(), address = str("address").orEmpty(), province = str("province"), cityMunicipality = str("cityMunicipality"), barangay = str("barangay"),
            code = str("code").orEmpty(), languages = (json["languages"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            createdAt = (json["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0L, createdByPersonId = str("createdByPersonId").orEmpty(),
            enrolledByPersonId = str("enrolledByPersonId"),
            status = if (str("status") == "INACTIVE") com.emfitsolutions.gopreach.data.model.RecordStatus.INACTIVE else com.emfitsolutions.gopreach.data.model.RecordStatus.ACTIVE,
        )
        // the device's own copy, so the list shows it now (the same documents arrive by the normal sync a moment later)
        offline.cacheFromServer("congregations", id, congregation)
        val circuitCode = str("circuitCode")
        if (circuitCode != null) {
            offline.cacheFromServer(
                "congregationCircuits", id,
                CongregationCircuit(id = id, congregationId = id, circuitOverseerPersonId = coPersonId, circuitCode = circuitCode, assignedAt = (json["assignedAt"] as? JsonPrimitive)?.longOrNull ?: 0L, assignedByPersonId = coPersonId),
            )
        }
        runCatching { syncEngine.syncOnce() }
        return CoCongregationResult.Saved(congregation, alreadyThere = (json["duplicate"] as? JsonPrimitive)?.contentOrNull == "true")
    }
}

/** A fresh id for one enrolment form (12 letters and digits), kept for every retry of that form. */
fun newRequestId(): String {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    return buildString { repeat(14) { append(chars.random()) } }
}
