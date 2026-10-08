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

    /** A plain authenticated POST to one of the server's own endpoints (territory claims, ...): the status code and JSON body, never thrown for 4xx. */
    suspend fun postJson(path: String, body: JsonObject): ApiReply = throw UnsupportedOperationException("postJson is not available")

    /** Uploads [bytes] to the server's file store under [path] (replacing any file there) and returns the public download URL. */
    suspend fun uploadFile(path: String, bytes: ByteArray, mime: String): String = throw UnsupportedOperationException("uploadFile is not available")

    /** Best-effort delete of the file stored under [path]. */
    suspend fun deleteFile(path: String) { throw UnsupportedOperationException("deleteFile is not available") }

    /** The sign-in screen's public username lookup (no login yet): the person record with its id, or null. */
    suspend fun lookupUsername(username: String): JsonObject? = null
}

data class ApiReply(val status: Int, val body: JsonObject?)

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
data class PushRequest(val ops: List<PushOp>)

@Serializable
data class PushResponse(val results: List<PushResult> = emptyList())

/** Thrown for network problems and non-2xx answers; the engine treats these as "try again later", not as a bad record. */
class SyncTransportException(message: String, val statusCode: Int? = null, cause: Throwable? = null) : Exception(message, cause)
