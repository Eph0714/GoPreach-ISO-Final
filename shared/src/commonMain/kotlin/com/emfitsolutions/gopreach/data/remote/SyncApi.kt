package com.emfitsolutions.gopreach.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The client side of the backend's sync contract (`backend/src/app.js`):
 *   POST /v1/sync/push  and  GET /v1/sync/pull. Platform-independent, so Android and iOS share one implementation.
 */
interface SyncApi {
    /** Uploads queued writes. One result per op, in order; one bad op never blocks the others. */
    suspend fun push(ops: List<PushOp>): List<PushResult>

    /** Everything changed after [since] (a server sequence number) that the caller may read. */
    suspend fun pull(since: Long, collections: List<String>? = null, limit: Int = 500): PullPage
}

@Serializable
data class PushOp(
    val collection: String,
    val id: String,
    /** "set" or "delete". */
    val op: String,
    val data: JsonObject? = null,
)

@Serializable
data class PushResult(
    val collection: String? = null,
    val id: String? = null,
    /** "ok", "denied" or "invalid". */
    val status: String,
    val version: Long? = null,
    val seq: Long? = null,
    val reason: String? = null,
) {
    val isOk get() = status == "ok"
}

@Serializable
data class PullChange(
    val collection: String,
    val id: String,
    val data: JsonElement? = null,
    val deleted: Boolean = false,
    val version: Long = 0,
    val seq: Long = 0,
)

@Serializable
data class PullPage(
    val changes: List<PullChange> = emptyList(),
    val cursor: Long = 0,
    val hasMore: Boolean = false,
)

@Serializable
internal data class PushRequest(val ops: List<PushOp>)

@Serializable
internal data class PushResponse(val results: List<PushResult> = emptyList())

/** Thrown for network problems and non-2xx answers; the engine treats these as "try again later", not as a bad record. */
class SyncTransportException(message: String, val statusCode: Int? = null, cause: Throwable? = null) : Exception(message, cause)
