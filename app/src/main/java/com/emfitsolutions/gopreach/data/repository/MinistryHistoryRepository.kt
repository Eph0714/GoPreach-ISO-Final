package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.remote.SyncApi
import com.emfitsolutions.gopreach.data.sync.ConnectivityObserver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** One thing done in the Ministry: enrolling a record, a visit logged / edited / deleted, a record moved, assigned, located, edited, archived ... */
data class HistoryEntry(
    val id: String,
    /** ENROLLED, VISIT or CHANGE (the filter chips). */
    val category: String,
    val summary: String,
    val at: Long,
    val actorName: String?,
    val recordId: String,
    val recordName: String,
    /** SEARCHING, RETURN_VISIT or BIBLE_STUDY (the list the record is in). */
    val stage: String,
    val recordExists: Boolean,
    val details: List<Pair<String, String>>,
) {
    val stageLabel: String get() = when (stage) { "RETURN_VISIT" -> "Return Visit"; "BIBLE_STUDY" -> "Bible Study"; else -> "Searching" }
}

data class HistoryPage(val entries: List<HistoryEntry>, val hasMore: Boolean, val failed: Boolean = false)

/**
 * The Ministry History, read from the server (GET /v1/ministry/history), the same entries the website shows. The server writes it when a record or a visit is saved
 * and decides which entries this person may see, so there is nothing to sync or store on the phone: it needs a connection.
 */
class MinistryHistoryRepository(
    private val connectivityObserver: ConnectivityObserver,
    private val syncApi: SyncApi,
) {
    /** [scope]: 0 = My, 1 = My FS Group, 2 = Congregation. [type]: null = all, or ENROLLED / VISIT / CHANGE. [before]: the `at` of the last entry already shown. */
    suspend fun page(scope: Int, type: String?, query: String, before: Long?, congregationId: String? = null): HistoryPage {
        if (!connectivityObserver.isOnline()) return HistoryPage(emptyList(), false, failed = true)
        val params = buildMap {
            put("scope", scope.toString())
            type?.let { put("type", it) }
            query.trim().takeIf { it.isNotEmpty() }?.let { put("q", it) }
            before?.let { put("before", it.toString()) }
            put("limit", "30")
            congregationId?.takeIf { it.isNotBlank() }?.let { put("congregationId", it) }
        }
        val reply = try { syncApi.callServer("GET", "/v1/ministry/history", params) } catch (_: Exception) { return HistoryPage(emptyList(), false, failed = true) }
        if (!reply.ok) return HistoryPage(emptyList(), false, failed = true)
        val root = runCatching { Json.parseToJsonElement(reply.text).jsonObject }.getOrNull() ?: return HistoryPage(emptyList(), false, failed = true)
        val entries = (root["entries"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parse) }
        return HistoryPage(entries, (root["hasMore"] as? JsonPrimitive)?.booleanOrNull == true)
    }

    private fun parse(o: JsonObject): HistoryEntry? {
        fun text(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        val id = text("id") ?: return null
        val details = (o["details"] as? JsonArray).orEmpty().mapNotNull { d ->
            val row = d as? JsonObject ?: return@mapNotNull null
            val label = (row["label"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            label to ((row["value"] as? JsonPrimitive)?.contentOrNull ?: "—")
        }
        return HistoryEntry(
            id = id,
            category = text("category") ?: "CHANGE",
            summary = text("summary").orEmpty(),
            at = (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L,
            actorName = text("actorName"),
            recordId = text("recordId").orEmpty(),
            recordName = text("recordName").orEmpty(),
            stage = text("stage") ?: "SEARCHING",
            recordExists = (o["recordExists"] as? JsonPrimitive)?.booleanOrNull == true,
            details = details,
        )
    }
}
