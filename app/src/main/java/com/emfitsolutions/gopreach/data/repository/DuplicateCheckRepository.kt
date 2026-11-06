package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.remote.SyncApi
import com.emfitsolutions.gopreach.data.sync.ConnectivityObserver
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** An existing person who may be the same as the one being enrolled (one match of the server's duplicate check). */
data class DuplicateMatch(
    val id: String,
    val name: String,
    /** SEARCHING, RETURN_VISIT or BIBLE_STUDY. */
    val stage: String,
    val province: String?,
    val cityMunicipality: String?,
    val barangay: String?,
    val publisherName: String?,
    /** "strong" or "possible". */
    val confidence: String,
    /** Name similarity, 0..100. */
    val similarity: Int,
    val distanceMeters: Int?,
    val reasons: List<String>,
) {
    val isStrong: Boolean get() = confidence == "strong"
    val stageLabel: String get() = when (stage) { "RETURN_VISIT" -> "Return Visit"; "BIBLE_STUDY" -> "Bible Study"; else -> "Searching" }
    val place: String get() = listOfNotNull(barangay, cityMunicipality, province).filter { it.isNotBlank() }.joinToString(", ").ifBlank { "No address" }
    val reasonText: String get() = reasons.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
}

/**
 * "Possible duplicate person" check for a Searching / Return Visit / Bible Study record being enrolled: POST /v1/duplicates/check, the very same server
 * matching the website uses (similar combined name + geographic evidence, across all three record types, limited to what this person may see).
 * It never blocks an enrollment: offline or on any failure it simply reports nothing.
 */
class DuplicateCheckRepository(
    private val connectivityObserver: ConnectivityObserver,
    private val syncApi: SyncApi,
) {
    suspend fun check(
        name: String,
        province: String?,
        cityMunicipality: String?,
        barangay: String?,
        lat: Double?,
        lng: Double?,
        excludeId: String? = null,
        congregationId: String? = null,
    ): List<DuplicateMatch> {
        if (name.isBlank() || !connectivityObserver.isOnline()) return emptyList()
        val body = buildJsonObject {
            put("name", name.trim())
            province?.takeIf { it.isNotBlank() }?.let { put("province", it) }
            cityMunicipality?.takeIf { it.isNotBlank() }?.let { put("cityMunicipality", it) }
            barangay?.takeIf { it.isNotBlank() }?.let { put("barangay", it) }
            if (lat != null && lng != null) { put("lat", lat); put("lng", lng) }
            excludeId?.let { put("excludeId", it) }
            congregationId?.takeIf { it.isNotBlank() }?.let { put("congregationId", it) }
        }
        val reply = try { syncApi.postJson("/v1/duplicates/check", body) } catch (_: Exception) { return emptyList() }
        if (reply.status != 200) return emptyList()
        return (reply.body?.get("matches") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseMatch) }
    }

    private fun parseMatch(o: JsonObject): DuplicateMatch? {
        fun text(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        val id = text("id") ?: return null
        return DuplicateMatch(
            id = id,
            name = text("name").orEmpty(),
            stage = text("stage") ?: "SEARCHING",
            province = text("province"),
            cityMunicipality = text("cityMunicipality"),
            barangay = text("barangay"),
            publisherName = text("publisherName"),
            confidence = text("confidence") ?: "possible",
            similarity = (o["similarity"] as? JsonPrimitive)?.intOrNull ?: ((o["similarity"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0),
            distanceMeters = (o["distanceMeters"] as? JsonPrimitive)?.intOrNull,
            reasons = (o["reasons"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
        )
    }
}
