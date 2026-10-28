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
    /** Collections whose model keeps the document id in a field other than `id` (e.g. sharedLocations -> publisherPersonId). */
    private val idFieldByCollection: Map<String, String> = emptyMap(),
) {
    suspend fun syncOnce(): SyncReport {
        val up = flushOutbox()
        if (up.transportError != null) return up
        val down = pull()
        return up.copy(downloaded = down.downloaded, transportError = down.transportError)
    }

    /**
     * Waits (up to [timeoutSeconds]) for the server to say that something this person may read has changed since the last download, without downloading anything.
     * True: pull now. False: nothing yet. Null: the server cannot say (an older server, or a network problem) - the caller falls back to timed rounds.
     */
    suspend fun waitForChange(timeoutSeconds: Int): Boolean? {
        return try {
            val result = api.watch(readCursor(), timeoutSeconds, collections) ?: return null
            result.hasChanges
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncTransportException) {
            null
        }
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
        if (existing?.syncState == SyncState.PENDING.name) {
            // An unsent local edit wins over another device's EDIT until it is uploaded. But a record that was DELETED elsewhere must not come back from a stale local edit:
            // the edit stays in the queue (never thrown away) marked as needing attention, is not uploaded, and the record goes away here like it did everywhere else.
            if (change.deleted || change.data == null) {
                queueDao.getAllPending()
                    .filter { it.collectionPath == change.collection && it.documentId == change.id }
                    .forEach { queueDao.markPermanentFailure(it.id, "deleted: This record was deleted on another device, so your change was not applied.") }
                cacheDao.delete(change.collection, change.id)
                return true
            }
            return false
        }
        if (change.deleted || change.data == null) {
            cacheDao.delete(change.collection, change.id)
            return true
        }
        cacheDao.upsert(
            CachedDocumentEntity(
                collectionPath = change.collection,
                documentId = change.id,
                payloadJson = DocJson.encodeToString(JsonElement.serializer(), withId(change.data, change.collection, change.id)),
                syncState = SyncState.SYNCED.name,
                updatedAt = nowMillis(),
            ),
        )
        return true
    }

    // The server stores a document without its id (like Firestore); the app's models carry it as a field.
    private fun withId(data: JsonElement, collection: String, id: String): JsonElement {
        val field = idFieldByCollection[collection] ?: "id"
        return if (data is JsonObject && field !in data) JsonObject(data + (field to JsonPrimitive(id))) else data
    }

    /** The cursor only moves forward, so a row the server hid from this account at some earlier pull (a role assignment or a
     *  congregation's territory before the account's active role was known) would never come back. Bumping [CURSOR_EPOCH] makes every
     *  device re-pull from the start once; [resetCursor] does the same on demand. */
    private suspend fun readCursor(): Long {
        if (cacheDao.get(META_COLLECTION, EPOCH_DOC)?.payloadJson != CURSOR_EPOCH) {
            cacheDao.upsert(CachedDocumentEntity(META_COLLECTION, EPOCH_DOC, CURSOR_EPOCH, SyncState.SYNCED.name, nowMillis()))
            writeCursor(0L)
            return 0L
        }
        return cacheDao.get(META_COLLECTION, CURSOR_DOC)?.payloadJson?.toLongOrNull() ?: 0L
    }

    /** The next pull starts from the beginning (what this account may read changed, so rows skipped earlier must be fetched again). */
    suspend fun resetCursor() = writeCursor(0L)

    /**
     * Once per person per install: pulls everything again from the start. The very first download after a fresh sign-in can run before the server knows this
     * person's active congregation, so it skips that congregation's FS Groups and territories - and the cursor then moves past them for good (the Territory Map
     * showed one group and no boundaries). The marker lives in the cache, so a new install or a cleared app repeats the catch-up once and nothing more.
     */
    suspend fun repullOnceFor(personId: String) {
        val marker = "repull:$personId"
        if (cacheDao.get(META_COLLECTION, marker) != null) return
        cacheDao.upsert(CachedDocumentEntity(META_COLLECTION, marker, "1", SyncState.SYNCED.name, nowMillis()))
        writeCursor(0L)
    }

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
        const val EPOCH_DOC = "cursorEpoch"
        const val CURSOR_EPOCH = "3"
    }
}
