package com.emfitsolutions.gopreach.data.sync

import com.emfitsolutions.gopreach.data.local.CachedDocumentEntity
import com.emfitsolutions.gopreach.data.local.PendingSyncOperationEntity
import com.emfitsolutions.gopreach.data.local.dao.CacheDao
import com.emfitsolutions.gopreach.data.local.dao.SyncQueueDao
import com.emfitsolutions.gopreach.data.model.SyncOperationType
import com.emfitsolutions.gopreach.data.model.SyncState
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

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
@Singleton
class OfflineFirestoreRepository @Inject constructor(
    // Non-private: the reified inline functions below (observeCollection, get) need
    // to reach these from call sites in other modules, which public inline functions
    // can only do via @PublishedApi-internal, not private, members.
    @PublishedApi internal val cacheDao: CacheDao,
    private val syncQueueDao: SyncQueueDao,
    @PublishedApi internal val gson: Gson,
    private val syncScheduler: SyncScheduler,
    private val syncStatusCenter: SyncStatusCenter,
    private val connectivityObserver: ConnectivityObserver,
) {
    inline fun <reified T> observeCollection(collectionPath: String): Flow<List<T>> =
        cacheDao.observeCollection(collectionPath).map { rows ->
            rows.map { gson.fromJson(it.payloadJson, T::class.java) }
        }

    /** See [CacheDao.observeCollectionsMatching] — for a variable-parent
     * subcollection (e.g. Visits across every Interested Person) rather than
     * one fixed [collectionPath]. */
    inline fun <reified T> observeCollectionsMatching(pathPattern: String): Flow<List<T>> =
        cacheDao.observeCollectionsMatching(pathPattern).map { rows ->
            rows.map { gson.fromJson(it.payloadJson, T::class.java) }
        }

    suspend inline fun <reified T> get(collectionPath: String, documentId: String): T? =
        cacheDao.get(collectionPath, documentId)?.let { gson.fromJson(it.payloadJson, T::class.java) }

    suspend fun <T> save(collectionPath: String, documentId: String, data: T) {
        saveRawJson(collectionPath, documentId, gson.toJson(data))
    }

    /** Same write path as [save], but for a payload that's already serialized —
     * used by [com.emfitsolutions.gopreach.data.repository.BackupRepository] to
     * restore entries straight from a backup file without a round-trip through a
     * typed model. */
    suspend fun saveRawJson(collectionPath: String, documentId: String, json: String) {
        val now = System.currentTimeMillis()
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
        syncStatusCenter.onWriteQueued(connectivityObserver.isOnline())
        syncScheduler.triggerSyncIfOnline()
    }

    /**
     * Same local-cache write as [save], but also pushes the document straight
     * to Firestore immediately and marks it synced — for the rare write that
     * can't wait for the user's next manual "Sync to Server" tap.
     *
     * Bug fix: [com.emfitsolutions.gopreach.data.repository.AuthRepository
     * .createAccountWithTempCredentials] used to only call [save] for the new
     * account's Person/RoleAssignment documents — fine for this app's normal
     * "manual sync only" design (spec §17), except a freshly enrolled user's
     * very next action is almost always signing in **on their own device**,
     * where [com.emfitsolutions.gopreach.data.repository.AuthRepository
     * .findPersonByUsername] queries Firestore directly. Until the enrolling
     * admin's device happened to sync (which could be indefinitely, since
     * sync is manual-only), that lookup found nothing and every brand-new
     * account — Coordinator Elder, Service Overseer, Regular Elder,
     * Publisher, Admin, alike, since they all share that one enrollment
     * method — failed to log in with "Invalid username or password" despite
     * correct temp credentials. Only safe to call when the caller already
     * knows the device is online (i.e. it just made a successful, unrelated
     * network call itself, as account creation always does) — this is not a
     * general-purpose replacement for [save].
     */
    suspend fun saveNow(firestore: FirebaseFirestore, collectionPath: String, documentId: String, data: Any) {
        // Cache-first, same as always — this part can't fail in a way that
        // should stop the caller. The immediate push below is a best-effort
        // improvement on top of it, not a replacement: if it throws for any
        // reason (a transient network drop right after the online-only
        // operation that justified calling this in the first place), the
        // document is still safely queued and will reach the server on the
        // next normal sync exactly as it always would have.
        save(collectionPath, documentId, data)
        runCatching {
            firestore.collection(collectionPath).document(documentId).set(data).await()
            cacheDao.updateSyncState(collectionPath, documentId, SyncState.SYNCED.name)
            syncQueueDao.removeForDocument(collectionPath, documentId)
        }
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
                createdAt = System.currentTimeMillis(),
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
    suspend fun <T> saveLocalOnly(collectionPath: String, documentId: String, data: T) {
        cacheDao.upsert(
            CachedDocumentEntity(
                collectionPath = collectionPath,
                documentId = documentId,
                payloadJson = gson.toJson(data),
                syncState = SyncState.PENDING.name,
                updatedAt = System.currentTimeMillis(),
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
    suspend fun <T> cacheFromServer(collectionPath: String, documentId: String, data: T) {
        cacheDao.upsert(
            CachedDocumentEntity(
                collectionPath = collectionPath,
                documentId = documentId,
                payloadJson = gson.toJson(data),
                syncState = SyncState.SYNCED.name,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

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
