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

    /**
     * The change feed (`GET /v1/sync/watch`): holds the request up to [timeoutSeconds] until something this caller may read has changed after [since], and answers
     * which collections changed (never their contents). Returns null when the server has no change feed, so callers fall back to polling.
     */
    suspend fun watch(since: Long, timeoutSeconds: Int, collections: List<String>? = null): WatchResult? = null

    /** A plain authenticated POST to one of the server's own endpoints (territory claims, ...): the status code and JSON body, never thrown for 4xx. */
    suspend fun postJson(path: String, body: JsonObject): ApiReply = throw UnsupportedOperationException("postJson is not available")

    /** Uploads [bytes] to the server's file store under [path] (replacing any file there) and returns the public download URL. */
    suspend fun uploadFile(path: String, bytes: ByteArray, mime: String, onProgress: ((sent: Long, total: Long) -> Unit)? = null): String = throw UnsupportedOperationException("uploadFile is not available")

    /** Best-effort delete of the file stored under [path]. */
    suspend fun deleteFile(path: String) { throw UnsupportedOperationException("deleteFile is not available") }

    /** Uploads one attachment of a Circuit Overseer announcement; returns the server's { id, name, mime, size }. Throws with the server's reason when refused. */
    suspend fun uploadAnnouncementFile(announcementId: String, name: String, bytes: ByteArray, mime: String): JsonObject = throw UnsupportedOperationException("announcement files are not available")

    /** Downloads one attachment (the server checks that the caller may read the announcement). */
    suspend fun downloadAnnouncementFile(announcementId: String, fileId: String): ByteArray = throw UnsupportedOperationException("announcement files are not available")

    suspend fun deleteAnnouncementFile(announcementId: String, fileId: String) { throw UnsupportedOperationException("announcement files are not available") }

    /**
     * A plain authenticated call to one of the server's own endpoints with raw bytes either way: the Circuit Overseer's File Manager and Previous Manual Record
     * (`/v1/co/...`). [json] is sent as the JSON body, or [bytes] as the raw body of [mime]; [query] becomes the query string. Never thrown for 4xx/5xx.
     */
    suspend fun callServer(method: String, path: String, query: Map<String, String> = emptyMap(), json: JsonObject? = null, bytes: ByteArray? = null, mime: String? = null): ServerReply =
        throw UnsupportedOperationException("callServer is not available")

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

/** One collection that changed, as the change feed reports it (what changed, not the records themselves). */
@Serializable
data class WatchChange(val collection: String, val count: Int = 0, val deletes: Int = 0, val ids: List<String> = emptyList())

@Serializable
data class WatchResult(val changed: List<WatchChange> = emptyList(), val cursor: Long = 0) {
    val hasChanges get() = changed.isNotEmpty()
}

@Serializable
data class PushRequest(val ops: List<PushOp>)

@Serializable
data class PushResponse(val results: List<PushResult> = emptyList())

/** Thrown for network problems and non-2xx answers; the engine treats these as "try again later", not as a bad record. */
class SyncTransportException(message: String, val statusCode: Int? = null, cause: Throwable? = null) : Exception(message, cause)

/** The answer of [SyncApi.callServer]: the status code and the raw body (JSON text, or the bytes of a downloaded file). */
class ServerReply(val status: Int, val bytes: ByteArray) {
    val ok get() = status in 200..299
    val text get() = bytes.decodeToString()
}
