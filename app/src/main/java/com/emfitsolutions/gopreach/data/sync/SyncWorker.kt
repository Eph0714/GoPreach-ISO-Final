package com.emfitsolutions.gopreach.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.emfitsolutions.gopreach.data.local.PendingSyncOperationEntity
import com.emfitsolutions.gopreach.data.local.dao.CacheDao
import com.emfitsolutions.gopreach.data.local.dao.SyncQueueDao
import com.emfitsolutions.gopreach.data.model.SyncOperationType
import com.emfitsolutions.gopreach.data.model.SyncState
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.tasks.await

/**
 * Flushes [PendingSyncOperationEntity] rows to Firestore, in the order they were
 * created, whenever a network connection is available (see [SyncScheduler]). This
 * is the other half of spec §6.5's offline-first requirement: local writes always
 * succeed instantly; this worker is what eventually makes them durable server-side.
 *
 * Also drives the "SYNC TO SERVER" button's progress/summary UI (`SyncToServerButton`):
 * [setProgress] is published as each record finishes, and the
 * final uploaded/failed counts are returned in [Result]'s output data so a caller
 * observing this unique work's [androidx.work.WorkInfo] can show a real "Sync
 * Complete — N uploaded, N failed" summary instead of a bare success/failure flag.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncQueueDao: SyncQueueDao,
    private val cacheDao: CacheDao,
    private val firestore: FirebaseFirestore,
    private val gson: Gson,
    private val syncStatusCenter: SyncStatusCenter,
    private val connectivityObserver: ConnectivityObserver,
    // "Make sync also pull the latest data down" — a device whose live
    // listener can't sustain a connection (see [RemoteSyncCoordinator
    // .retryIfNeeded]'s doc comment for a real one found this way) never
    // receives anyone else's My Planner/Credit Hours/Return Visits data no
    // matter how many times its own upload succeeds. Every sync run — manual
    // or automatic — now also does a one-shot [pullFirestoreCollectionOnce]
    // of exactly these collections (a plain request/response call, not a
    // held-open stream, so it doesn't depend on whatever keeps failing to
    // *sustain* one) as a fallback path alongside the live listeners
    // [RemoteSyncCoordinator] already keeps running. Shared with the
    // standalone "Refresh" button ([RefreshButton]) so both paths pull the
    // exact same set of collections the exact same way.
    private val dataRefresher: DataRefresher,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_UPLOADED = "uploaded"
        const val KEY_FAILED = "failed"
        const val KEY_TOTAL = "total"
        const val KEY_FINISHED = "finished"
        /** Set whenever this run did nothing because [ConnectivityObserver]
         * reported no usable internet — checked either before touching the
         * queue at all, or mid-run if connectivity dropped partway through.
         * The manual "Sync to Server" UI reads this to show its own
         * "Sync Failed — no internet connection" state instead of a
         * misleading "Sync Complete"/"0 failed" summary (see
         * [com.emfitsolutions.gopreach.ui.components.ManualSyncViewModel]). */
        const val KEY_SKIPPED_OFFLINE = "skippedOffline"
        /** "Do not show the system message if there are record[s]
         * automatically syncing" — set only by [SyncScheduler.requestSyncNow]
         * (the explicit "Sync to Server" button); every automatic trigger
         * (periodic floor, reconnect, [SyncScheduler.triggerSyncIfOnline])
         * leaves this false, which is what [SyncStatusCenter.onSyncFinished]
         * reads to decide whether this run is worth a toast at all. */
        const val KEY_MANUAL = "manual"

        /** Collections whose `@DocumentId` property isn't named "id" — must stay
         * in lockstep with every `@DocumentId val <name>` in `data/model` that
         * isn't `id`. See [applyOperation]'s doc comment for why this list has to
         * be exhaustive: missing an entry here reproduces the exact
         * "crashes/fails to mirror back down" bug this map exists to prevent. */
        private val DOCUMENT_ID_KEYS_BY_COLLECTION = mapOf(
            "sharedLocations" to "publisherPersonId",
            "locationSharingSettings" to "congregationId",
            "dashboardModuleLayouts" to "personId",
        )
    }

    override suspend fun doWork(): Result {
        val isManual = inputData.getBoolean(KEY_MANUAL, false)

        // Mandatory connectivity gate — the ONE check every path that can
        // ever reach Firestore funnels through, since manual ("Sync to
        // Server"), the automatic on-reconnect trigger, the periodic 15-
        // minute floor, and triggerSyncIfOnline's "just wrote something"
        // nudge all ultimately just enqueue this same worker (see
        // SyncScheduler). WorkManager's own `NetworkType.CONNECTED`
        // constraint on every one of those requests is a coarse, OS-level
        // pre-filter only — "a network exists," not "the internet actually
        // works" (Wi-Fi connected to a router with no upstream internet
        // still satisfies it) — so it alone let this worker start and throw
        // itself at Firestore on a genuinely dead connection. Every one of
        // those calls then failed with a plain network exception, which
        // [isPermanentFailure] correctly treats as retryable, so this
        // worker kept returning Result.retry() and WorkManager kept
        // re-arming its own backoff timer forever — that's the "keeps
        // trying every few seconds while Offline" symptom. Checking the
        // app's own single source of truth ([ConnectivityObserver], the
        // same validated-internet state the UI badge reads) here, before
        // touching anything, and bailing via Result.success() (never
        // retry()) closes that loop for good: nothing is touched (every
        // pending row stays exactly PENDING, nothing becomes FAILED for a
        // reason that has nothing to do with that specific record), zero
        // Firestore calls are made, and the next real attempt comes only
        // from an actual online-transition trigger or the periodic floor's
        // next natural tick — never from this worker rescheduling itself.
        if (!connectivityObserver.isOnline()) {
            setProgress(workDataOf(KEY_UPLOADED to 0, KEY_FAILED to 0, KEY_TOTAL to 0, KEY_FINISHED to true, KEY_SKIPPED_OFFLINE to true))
            return Result.success()
        }

        // Runs even when there's nothing local to upload below — this is the
        // "download" half, entirely independent of the pending-queue check
        // that follows. See this class's own constructor comment.
        dataRefresher.refreshNow()

        // Recovers on its own from an app/device crash mid-sync (spec's "Sync
        // Queue Recovery") without needing a separate SYNCING status to reset:
        // a row is never marked anything but PENDING until it's either
        // deleted (success) or flagged permanent below — there is no
        // in-between persisted state a crash could ever strand it in.
        val pending = syncQueueDao.getAllPending()
        if (pending.isEmpty()) {
            setProgress(workDataOf(KEY_UPLOADED to 0, KEY_FAILED to 0, KEY_TOTAL to 0, KEY_FINISHED to true))
            return Result.success()
        }

        syncStatusCenter.onSyncStarted()
        var uploaded = 0
        // Retryable (network/server-side, temporary) failures — these are
        // what actually justify Result.retry() below.
        var failed = 0
        // Bad-data/permission/path failures — retrying these changes nothing,
        // ever, so they don't count towards `failed` and don't get retried;
        // see [classifyFailure] and PendingSyncOperationEntity.isPermanentFailure's
        // own doc comment for why lumping these in with `failed` is exactly
        // what made "Sync Failed" look permanently stuck even once the
        // network — and every other pending change — was fine again.
        var permanentlyFailed = 0
        // Set the instant connectivity drops mid-run (spec: "Disconnect
        // Internet while syncing -> Sync stops safely") — the loop below
        // bails out of the remaining batch immediately rather than letting
        // every remaining operation individually time out against a now-dead
        // connection; whatever already reached the server above stays
        // synced, everything from here on stays exactly PENDING, untouched.
        var lostConnectivityMidRun = false
        try {
            for ((index, op) in pending.withIndex()) {
                if (!connectivityObserver.isOnline()) {
                    lostConnectivityMidRun = true
                    break
                }
                setProgress(workDataOf("done" to index, KEY_TOTAL to pending.size))
                val ok = runCatching { applyOperation(op) }
                val error = ok.exceptionOrNull()
                when {
                    ok.isSuccess -> {
                        syncQueueDao.remove(op)
                        cacheDao.updateSyncState(op.collectionPath, op.documentId, SyncState.SYNCED.name)
                        uploaded++
                    }
                    isPermanentFailure(error!!) -> {
                        permanentlyFailed++
                        syncQueueDao.markPermanentFailure(op.id, error.message ?: "Unknown error")
                        cacheDao.updateSyncState(op.collectionPath, op.documentId, SyncState.FAILED.name)
                    }
                    else -> {
                        failed++
                        syncQueueDao.recordFailure(op.id, error.message ?: "Unknown error")
                        cacheDao.updateSyncState(op.collectionPath, op.documentId, SyncState.FAILED.name)
                    }
                }
            }
        } finally {
            syncStatusCenter.onSyncEnded()
        }
        // Published via setProgress (not the terminal Result's output data) so the
        // manual "Sync to Server" UI gets an immediate summary of *this* attempt even
        // when the worker's own Result is retry() below — WorkInfo.outputData is only
        // populated for a truly terminal SUCCEEDED/FAILED state, which a retrying
        // worker on a partial failure never reaches for this attempt.
        setProgress(
            workDataOf(
                KEY_UPLOADED to uploaded, KEY_FAILED to failed, KEY_TOTAL to pending.size,
                KEY_FINISHED to true, KEY_SKIPPED_OFFLINE to lostConnectivityMidRun,
            ),
        )
        // Skip the usual toast/message when connectivity dropped mid-run —
        // whatever this run actually managed isn't "Sync Complete" (spec:
        // never claim "all changes synced" unless it genuinely finished),
        // and the manual UI already gets its own explicit "no internet"
        // state from KEY_SKIPPED_OFFLINE above instead.
        if (!lostConnectivityMidRun) syncStatusCenter.onSyncFinished(uploaded, failed, isManual)
        // Only a genuinely retryable failure while actually online asks
        // WorkManager to retry later (unchanged background reliability
        // behavior) — a permanent-only failure has nothing left that another
        // attempt could fix, so retrying it forever would just be wasted
        // battery/network for a result that will never change; the row stays
        // queued (as isPermanentFailure) for investigation instead of being
        // silently dropped. Losing connectivity mid-run never asks for a
        // retry either — same reasoning as the top-of-function gate: that
        // would just re-arm WorkManager's backoff for a reason another
        // attempt can't fix until the device is actually back online, which
        // the app's own reconnect trigger already handles.
        return if (failed > 0 && !lostConnectivityMidRun) Result.retry() else Result.success()
    }

    /** Spec §9 — "classify errors": a temporary network/server problem should
     * always be retried; a permanent, data-shaped problem never should be
     * (see [PendingSyncOperationEntity.isPermanentFailure]'s doc comment for
     * the exact bug this fixes). [FirebaseFirestoreException] carries the
     * server's own verdict via [FirebaseFirestoreException.Code] — everything
     * else (a plain [java.io.IOException]/`UnknownHostException`/timeout from
     * the transport layer itself, or any exception type this app has never
     * seen before) is treated as temporary, the safe default: never wrongly
     * giving up on a change that only needed the network to come back. */
    private fun isPermanentFailure(error: Throwable): Boolean {
        val code = (error as? FirebaseFirestoreException)?.code ?: return false
        return when (code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            FirebaseFirestoreException.Code.UNAUTHENTICATED,
            FirebaseFirestoreException.Code.INVALID_ARGUMENT,
            FirebaseFirestoreException.Code.NOT_FOUND,
            FirebaseFirestoreException.Code.ALREADY_EXISTS,
            FirebaseFirestoreException.Code.FAILED_PRECONDITION,
            FirebaseFirestoreException.Code.OUT_OF_RANGE,
            FirebaseFirestoreException.Code.UNIMPLEMENTED,
            FirebaseFirestoreException.Code.DATA_LOSS,
            -> true
            // UNAVAILABLE, DEADLINE_EXCEEDED, ABORTED, INTERNAL, CANCELLED,
            // RESOURCE_EXHAUSTED, UNKNOWN, and everything else — all
            // legitimately "try again later" outcomes.
            else -> false
        }
    }

    private suspend fun applyOperation(op: PendingSyncOperationEntity) {
        val docRef = firestore.collection(op.collectionPath).document(op.documentId)
        when (SyncOperationType.valueOf(op.operationType)) {
            SyncOperationType.CREATE, SyncOperationType.UPDATE -> {
                val mapType = object : TypeToken<Map<String, Any?>>() {}.type
                val fields: Map<String, Any?> = gson.fromJson(op.payloadJson, mapType)
                // Every model's @DocumentId property — "id" for nearly all of them,
                // but a few collections use their own natural key instead (see
                // DOCUMENT_ID_KEYS_BY_COLLECTION) — must never be written as a
                // literal stored field: Firestore's toObject() throws on read if
                // it finds one, since @DocumentId is supposed to repopulate that
                // property from the document reference alone. This raw Gson-map
                // upload path doesn't go through Firestore's POJO mapper (which
                // strips these automatically), so it has to know each
                // collection's @DocumentId key explicitly.
                // Confirmed root cause of a real production crash: this write path
                // only ever special-cased "sharedLocations" -> "publisherPersonId"
                // (originally stripping only "id"), leaving
                // "locationSharingSettings" (@DocumentId congregationId) and
                // "dashboardModuleLayouts" (@DocumentId personId) with the same
                // bug — every session's post-login mirror of any of these three
                // collections crashed/failed the instant it downloaded a corrupt
                // document written through this path back — see FirestoreMirror.kt.
                val documentIdKey = DOCUMENT_ID_KEYS_BY_COLLECTION[op.collectionPath] ?: "id"
                docRef.set(fields - documentIdKey).await()
            }
            SyncOperationType.DELETE -> docRef.delete().await()
        }
    }
}
