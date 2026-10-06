package com.emfitsolutions.gopreach.data.sync

import com.emfitsolutions.gopreach.data.json.DocJson
import com.emfitsolutions.gopreach.data.local.CachedDocumentEntity
import com.emfitsolutions.gopreach.data.local.PendingSyncOperationEntity
import com.emfitsolutions.gopreach.data.local.dao.CacheDao
import com.emfitsolutions.gopreach.data.local.dao.SyncQueueDao
import com.emfitsolutions.gopreach.data.model.SyncOperationType
import com.emfitsolutions.gopreach.data.model.SyncState
import com.emfitsolutions.gopreach.data.remote.PullChange
import com.emfitsolutions.gopreach.data.remote.PushOp
import com.emfitsolutions.gopreach.data.remote.SyncApi
import com.emfitsolutions.gopreach.data.remote.SyncTransportException
import com.emfitsolutions.gopreach.platform.nowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class SyncReport(
    val uploaded: Int = 0,
    val rejected: Int = 0,
    val downloaded: Int = 0,
    /** Set when the network or server was unreachable; queued edits are kept for the next attempt. */
    val transportError: String? = null,
)

/**
 * Platform-independent sync against the Hostinger backend: upload the offline outbox, then download everything that
 * changed on the server since the last cursor. Same rules the Android Firestore path follows:
 *  - local edits are never overwritten by a download while they are still waiting to upload,
 *  - a rejected edit is kept (flagged as a permanent failure) instead of being silently dropped or retried forever,
 *  - a network problem keeps the queue exactly as it was.
 */
class SyncEngine(
    private val api: SyncApi,
    private val cacheDao: CacheDao,
    private val queueDao: SyncQueueDao,
    private val collections: List<String>? = null,
    private val batchSize: Int = 50,
) {
    suspend fun syncOnce(): SyncReport {
        val up = flushOutbox()
        if (up.transportError != null) return up
        val down = pull()
        return up.copy(downloaded = down.downloaded, transportError = down.transportError)
    }

    suspend fun flushOutbox(): SyncReport {
        var uploaded = 0
        var rejected = 0
        for (batch in queueDao.getAllPending().chunked(batchSize)) {
            val ops = batch.map { it.toPushOp() }
            val results = try {
                api.push(ops)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SyncTransportException) {
                batch.forEach { queueDao.recordFailure(it.id, e.message ?: "Network error") }
                return SyncReport(uploaded, rejected, transportError = e.message)
            }
            batch.forEachIndexed { i, pending ->
                val result = results.getOrNull(i)
                when {
                    result == null -> queueDao.recordFailure(pending.id, "No result from server")
                    result.isOk -> {
                        queueDao.remove(pending)
                        // Only mark synced if the user has not edited the document again since this upload was queued.
                        if (queueDao.getAllPending().none { it.collectionPath == pending.collectionPath && it.documentId == pending.documentId }) {
                            cacheDao.updateSyncState(pending.collectionPath, pending.documentId, SyncState.SYNCED.name)
                        }
                        uploaded++
                    }
                    else -> {
                        queueDao.markPermanentFailure(pending.id, "${result.status}: ${result.reason ?: "rejected"}")
                        rejected++
                    }
                }
            }
        }
        return SyncReport(uploaded, rejected)
    }

    suspend fun pull(): SyncReport {
        var downloaded = 0
        var cursor = readCursor()
        try {
            do {
                val page = api.pull(since = cursor, collections = collections)
                page.changes.forEach { if (apply(it)) downloaded++ }
                cursor = maxOf(cursor, page.cursor)
                writeCursor(cursor)
            } while (page.hasMore)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncTransportException) {
            return SyncReport(downloaded = downloaded, transportError = e.message)
        }
        return SyncReport(downloaded = downloaded)
    }

    /** Returns true if the change was written to the cache. */
    private suspend fun apply(change: PullChange): Boolean {
        val existing = cacheDao.get(change.collection, change.id)
        if (existing?.syncState == SyncState.PENDING.name) return false // an unsent local edit wins until it is uploaded
        if (change.deleted || change.data == null) {
            cacheDao.delete(change.collection, change.id)
            return true
        }
        cacheDao.upsert(
            CachedDocumentEntity(
                collectionPath = change.collection,
                documentId = change.id,
                payloadJson = DocJson.encodeToString(JsonElement.serializer(), withId(change.data, change.id)),
                syncState = SyncState.SYNCED.name,
                updatedAt = nowMillis(),
            ),
        )
        return true
    }

    // The server stores a document without its id (like Firestore); the app's models carry it as a field.
    private fun withId(data: JsonElement, id: String): JsonElement =
        if (data is JsonObject && "id" !in data) JsonObject(data + ("id" to JsonPrimitive(id))) else data

    private suspend fun readCursor(): Long =
        cacheDao.get(META_COLLECTION, CURSOR_DOC)?.payloadJson?.toLongOrNull() ?: 0L

    private suspend fun writeCursor(cursor: Long) {
        cacheDao.upsert(CachedDocumentEntity(META_COLLECTION, CURSOR_DOC, cursor.toString(), SyncState.SYNCED.name, nowMillis()))
    }

    private fun PendingSyncOperationEntity.toPushOp(): PushOp =
        if (operationType == SyncOperationType.DELETE.name) PushOp(collectionPath, documentId, "delete")
        else PushOp(collectionPath, documentId, "set", DocJson.parseToJsonElement(payloadJson ?: "{}") as? JsonObject ?: JsonObject(emptyMap()))

    private companion object {
        // Bookkeeping row in the cache table (never queued, so it is never uploaded).
        const val META_COLLECTION = "_sync"
        const val CURSOR_DOC = "cursor"
    }
}
