package com.emfitsolutions.gopreach.data.sync

import com.emfitsolutions.gopreach.data.local.CachedDocumentEntity
import com.emfitsolutions.gopreach.data.local.PendingSyncOperationEntity
import com.emfitsolutions.gopreach.data.local.dao.CacheDao
import com.emfitsolutions.gopreach.data.local.dao.SyncQueueDao
import com.emfitsolutions.gopreach.data.model.SyncOperationType
import com.emfitsolutions.gopreach.data.model.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.emfitsolutions.gopreach.data.json.DocJson
import com.emfitsolutions.gopreach.data.json.decode
import com.emfitsolutions.gopreach.data.json.encode
import com.emfitsolutions.gopreach.platform.nowMillis

/**
 * Offline-first read/write path shared by every domain repository (Congregations,
 * Publishers, Territories, Bible Studies, ...) so the "queue-and-sync applies to
 * all CRUD app-wide" requirement (spec §6.5) is implemented once, not per feature.
 *
 * Write path: save to the local cache immediately (state PENDING) and enqueue the
 * operation — that's it. This method itself never asks [SyncScheduler] to run;
 * the queued row just sits there, tracked by a "pending sync" indicator
 * ([SyncQueueDao.observePendingCount]), until something actually flushes the
 * queue — either the user explicitly tapping
 * [com.emfitsolutions.gopreach.ui.components.SyncToServerButton] (or the older
 * [com.emfitsolutions.gopreach.ui.components.SyncStatusButton]/pull-to-refresh),
 * or [SyncScheduler.ensureAutomaticSyncStarted]'s own automatic
 * connectivity/periodic triggers — this per-write path doesn't need to know
 * or care which one eventually does it.
 *
 * Read path: callers observe the local cache (always available offline); the cache
 * itself is kept current by Firestore snapshot listeners set up per collection where
 * live updates matter (added alongside each domain repository) — those downloads
 * are a separate concern from this file's upload queue and are unaffected by the
 * manual-sync-only requirement, which is specifically about *this device's own*
 * pending edits.
 */
class OfflineFirestoreRepository(
    // Non-private: the reified inline functions below (observeCollection, get) need
    // to reach these from call sites in other modules, which public inline functions
    // can only do via @PublishedApi-internal, not private, members.
    @PublishedApi internal val cacheDao: CacheDao,
    private val syncQueueDao: SyncQueueDao,
    private val writeQueued: WriteQueuedListener,
    private val serverSyncClock: ServerSyncClock,
) {
    @PublishedApi internal inline fun <reified T> decodeOrNull(row: CachedDocumentEntity): T? =
        try { DocJson.decode<T>(row.payloadJson) } catch (e: Exception) { null } // one malformed row must never break a whole list

    inline fun <reified T> observeCollection(collectionPath: String): Flow<List<T>> =
        cacheDao.observeCollection(collectionPath).map { rows ->
            rows.mapNotNull { decodeOrNull<T>(it) }
        }

    /** See [CacheDao.observeCollectionsMatching] — for a variable-parent
     * subcollection (e.g. Visits across every Interested Person) rather than
     * one fixed [collectionPath]. */
    inline fun <reified T> observeCollectionsMatching(pathPattern: String): Flow<List<T>> =
        cacheDao.observeCollectionsMatching(pathPattern).map { rows ->
            rows.mapNotNull { decodeOrNull<T>(it) }
        }

    suspend inline fun <reified T> get(collectionPath: String, documentId: String): T? =
        cacheDao.get(collectionPath, documentId)?.let { decodeOrNull<T>(it) }

    suspend inline fun <reified T> save(collectionPath: String, documentId: String, data: T) {
        saveRawJson(collectionPath, documentId, DocJson.encode(data))
    }

    /** Same write path as [save], but for a payload that's already serialized —
     * used by [com.emfitsolutions.gopreach.data.repository.BackupRepository] to
     * restore entries straight from a backup file without a round-trip through a
     * typed model. */
    suspend fun saveRawJson(collectionPath: String, documentId: String, json: String) {
        val now = nowMillis()
        cacheDao.upsert(
            CachedDocumentEntity(
                collectionPath = collectionPath,
                documentId = documentId,
                payloadJson = json,
                syncState = SyncState.PENDING.name,
                updatedAt = now,
            )
        )
        // A newer edit to the same document supersedes any earlier one still
        // sitting unsynced — never let two queued operations for the same
        // document pile up (see removeForDocument's doc comment).
        syncQueueDao.removeForDocument(collectionPath, documentId)
        syncQueueDao.enqueue(
            // CREATE and UPDATE both resolve to a Firestore set(), so the queue
            // doesn't need to distinguish them once enqueued.
            PendingSyncOperationEntity(
                collectionPath = collectionPath,
                documentId = documentId,
                operationType = SyncOperationType.UPDATE.name,
                payloadJson = json,
                createdAt = now,
            )
        )
        onWriteQueued()
    }

    /** Common tail of every write that enqueues a pending upload ([saveRawJson],
     * [delete]) — reports the queue-worthy event to [SyncStatusCenter] (for the
     * "saved locally, waiting for internet" message) and, when the device is
     * actually online right now, asks [SyncScheduler] to flush the queue almost
     * immediately rather than waiting for the next connectivity transition or
     * periodic floor. */
    private fun onWriteQueued() {
        writeQueued.onWriteQueued()
    }

    /** Marks a document as already on the server and drops its queued upload (used by the Android `saveNow` extension). */
    suspend fun markSynced(collectionPath: String, documentId: String) {
        cacheDao.updateSyncState(collectionPath, documentId, SyncState.SYNCED.name)
        syncQueueDao.removeForDocument(collectionPath, documentId)
    }

    suspend fun delete(collectionPath: String, documentId: String) {
        cacheDao.delete(collectionPath, documentId)
        syncQueueDao.removeForDocument(collectionPath, documentId)
        syncQueueDao.enqueue(
            PendingSyncOperationEntity(
                collectionPath = collectionPath,
                documentId = documentId,
                operationType = SyncOperationType.DELETE.name,
                payloadJson = null,
                createdAt = nowMillis(),
            )
        )
        onWriteQueued()
    }

    /** Cache-only write that — unlike [save] — never enqueues a pending
     * upload at all, not even one that just sits there until the next sync.
     * "Do not include user logs in server synchronization": used
     * **exclusively** by [com.emfitsolutions.gopreach.data.repository
     * .AuditLogRepository.log] so a fresh audit-log entry never becomes
     * something either the manual "Sync to Server" button or the automatic
     * background sync ([SyncScheduler.ensureAutomaticSyncStarted]) would
     * push to Firestore — it stays on this device, full stop. Marked
     * PENDING like [save] (not SYNCED — that would misleadingly claim this
     * document actually reached the server, which it never will via this
     * path) purely so [SyncQueueDao.observePendingCount]'s indicator still
     * reflects reality if anything ever inspects this row directly; it's
     * simply never placed in the upload queue in the first place, which is
     * what actually keeps it out of every sync run. */
    suspend inline fun <reified T> saveLocalOnly(collectionPath: String, documentId: String, data: T) {
        cacheDao.upsert(
            CachedDocumentEntity(
                collectionPath = collectionPath,
                documentId = documentId,
                payloadJson = DocJson.encode(data),
                syncState = SyncState.PENDING.name,
                updatedAt = nowMillis(),
            )
        )
    }

    /** Cache-only write — used **exclusively** by [mirrorFirestoreCollection] to
     * reflect a document that just arrived *from* the server. This must never
     * enqueue a pending upload: doing so was a real, serious bug (every document
     * downloaded by a collection's live listener — including the *entire*
     * initial snapshot the very first time it attaches — was being queued right
     * back up as if the user had just edited it, inflating "pending changes" by
     * hundreds for data nobody ever touched). Marked SYNCED, not PENDING, since
     * it's already exactly what the server has. */
    suspend inline fun <reified T> cacheFromServer(collectionPath: String, documentId: String, data: T) =
        cacheFromServer(kotlinx.serialization.serializer<T>(), collectionPath, documentId, data)

    /** Same as the reified overload, for callers that only have a runtime serializer (the Firestore mirror). */
    suspend fun <T> cacheFromServer(serializer: kotlinx.serialization.KSerializer<T>, collectionPath: String, documentId: String, data: T) {
        cacheDao.upsert(
            CachedDocumentEntity(
                collectionPath = collectionPath,
                documentId = documentId,
                payloadJson = DocJson.encodeToString(serializer, data),
                syncState = SyncState.SYNCED.name,
                updatedAt = nowMillis(),
            )
        )
    }

    /** The live listener just received a snapshot from the server (not from the local cache). */
    fun noteServerSync() = serverSyncClock.mark()

    /** Cache-only delete — the [mirrorFirestoreCollection] counterpart to
     * [cacheFromServer] for a document removed on the server. Never enqueues a
     * pending delete for the same reason [cacheFromServer] never enqueues a
     * pending upload. */
    suspend fun deleteFromServer(collectionPath: String, documentId: String) {
        cacheDao.delete(collectionPath, documentId)
    }

    fun observePendingSyncCount(): Flow<Int> = syncQueueDao.observePendingCount()

    /** Bug fix — "Sync Error" used to be a dead end: the status badge showed
     * it, but nothing in the app let the Publisher see which record failed,
     * why, or do anything about it. This is what actually backs that recovery
     * UI (see [com.emfitsolutions.gopreach.ui.components.SyncStatusIndicator]). */
    fun observePermanentSyncFailures(): Flow<List<PendingSyncOperationEntity>> = syncQueueDao.observePermanentFailures()

    /** Puts a permanently-failed operation back into the normal retry queue —
     * does not touch [PendingSyncOperationEntity.lastError]/`retryCount`, so
     * what actually happened is never lost, just no longer a dead end. */
    suspend fun retryPermanentSyncFailure(operation: PendingSyncOperationEntity) {
        syncQueueDao.retryPermanentFailure(operation.id)
    }

    /** Puts every permanently-failed operation back into the retry queue. */
    suspend fun retryAllPermanentSyncFailures() {
        syncQueueDao.retryAllPermanentFailures()
    }

    /** Discards a permanently-failed operation outright — the one case this
     * app *does* delete a pending operation without it ever reaching the
     * server, and only after the Publisher explicitly chose to (never
     * automatic — see [SyncQueueDao.markPermanentFailure]'s own doc comment
     * on why silently dropping one was the wrong default). */
    suspend fun discardPermanentSyncFailure(operation: PendingSyncOperationEntity) {
        syncQueueDao.remove(operation)
    }
}
