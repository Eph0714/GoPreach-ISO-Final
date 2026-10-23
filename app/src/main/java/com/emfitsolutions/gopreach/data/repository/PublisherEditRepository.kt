package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.remote.SyncApi
import com.emfitsolutions.gopreach.data.sync.ConnectivityObserver
import com.emfitsolutions.gopreach.data.sync.SyncEngine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Everything one Edit of a publisher record can change; what is null / not sent is left as it is. */
data class PublisherEdit(
    /** The personal details as the form now has them. */
    val person: Person,
    /** The classification (a PublisherCategory name), or null to leave it. */
    val category: String? = null,
    /** [sendGroup] false leaves the FS Group alone; true sets it to [groupId] (null = unassigned). */
    val sendGroup: Boolean = false,
    val groupId: String? = null,
    /** Only a Super Admin sends a congregation. */
    val congregationId: String? = null,
    val username: String? = null,
    /** The account's sign-in status (ACTIVE, INACTIVE, SUSPENDED), never the classification. */
    val accountStatus: String? = null,
    /** A new temporary password, or null / blank to keep the current one. It only travels in this request and is never stored by the app. */
    val password: String? = null,
)

sealed class PublisherEditResult {
    data class Success(val changed: List<String>) : PublisherEditResult()
    /** Nothing was sent: the unified save needs the server (it checks the roles and writes everything or nothing). */
    data object Offline : PublisherEditResult()
    /** The server refused; [field] names the part that was wrong when it said so. Nothing was written. */
    data class Refused(val status: Int, val message: String, val field: String?) : PublisherEditResult()
    data class Failed(val message: String) : PublisherEditResult()
}

/**
 * The Publisher Record's one Edit, straight to the server (POST /v1/publishers/:id/edit) exactly like the website: the server checks the caller's assigned roles and
 * the record's congregation and FS Group, validates every part and writes all of it or none of it, then sets a new password. Like the territory claims this is not
 * queued: an all-or-nothing save with a sign-in in it has to be answered by the server before anything is reported as saved. Afterwards the app syncs so the list shows it.
 */
class PublisherEditRepository(
    private val connectivityObserver: ConnectivityObserver,
    private val syncApi: SyncApi,
    private val syncEngine: SyncEngine,
) {
    suspend fun edit(edit: PublisherEdit): PublisherEditResult {
        if (!connectivityObserver.isOnline()) return PublisherEditResult.Offline
        val reply = try {
            syncApi.postJson("/v1/publishers/${edit.person.id}/edit", publisherEditBody(edit))
        } catch (e: Exception) {
            return PublisherEditResult.Failed(e.message ?: "Couldn't reach the server. Check your connection and try again.")
        }
        val message = (reply.body?.get("message") as? JsonPrimitive)?.contentOrNull ?: (reply.body?.get("error") as? JsonPrimitive)?.contentOrNull
        return when (reply.status) {
            200 -> {
                runCatching { syncEngine.syncOnce() }
                PublisherEditResult.Success((reply.body?.get("changed") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty())
            }
            401 -> PublisherEditResult.Failed("Your session has ended. Please sign in again.")
            else -> PublisherEditResult.Refused(reply.status, message ?: "The server did not accept this change.", (reply.body?.get("field") as? JsonPrimitive)?.contentOrNull)
        }
    }

}

/** The request body of one Edit (the personal details, and only the parts that were asked to change). */
internal fun publisherEditBody(edit: PublisherEdit): JsonObject = buildJsonObject {
    put("person", details(edit.person))
    edit.category?.let { put("category", it) }
    if (edit.sendGroup) put("groupId", edit.groupId?.let { JsonPrimitive(it) } ?: JsonNull)
    edit.congregationId?.let { put("congregationId", it) }
    edit.username?.takeIf { it.isNotBlank() }?.let { put("username", it) }
    edit.accountStatus?.let { put("accountStatus", it) }
    edit.password?.takeIf { it.isNotBlank() }?.let { put("password", it) }
}

/** The personal details of a publisher record: the same keys the website sends. */
private fun details(p: Person): JsonObject = buildJsonObject {
    fun text(key: String, v: String?) = put(key, v?.let { JsonPrimitive(it) } ?: JsonNull)
    text("firstName", p.firstName); text("lastName", p.lastName); text("middleInitial", p.middleInitial); text("extensionName", p.extensionName)
    text("address", p.address); text("contact", p.contact); text("province", p.province); text("cityMunicipality", p.cityMunicipality); text("barangay", p.barangay)
    text("contactPerson", p.contactPerson); text("contactPersonNumber", p.contactPersonNumber); text("remarks", p.remarks); text("email", p.email)
    text("gender", p.gender?.name)
    put("birthdate", p.birthdate?.let { JsonPrimitive(it) } ?: JsonNull)
    put("baptismalDate", p.baptismalDate?.let { JsonPrimitive(it) } ?: JsonNull)
    put("gpsLat", p.gpsLat?.let { JsonPrimitive(it) } ?: JsonNull)
    put("gpsLng", p.gpsLng?.let { JsonPrimitive(it) } ?: JsonNull)
}
