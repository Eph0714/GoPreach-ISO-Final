package com.emfitsolutions.gopreach.data.sync

import com.emfitsolutions.gopreach.data.local.dao.SyncQueueDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Single, app-wide source of truth for "what is the sync queue doing right now" —
 * the plain-language system messages and the permanent-failure count both come
 * from here, rather than each screen guessing at queue state independently.
 *
 * Deliberately knows nothing about connectivity ("GoPreach — Fix Online/Offline
 * Status and Sync Indicator" spec §6: "do not use the synchronization result as
 * the online/offline indicator" — that's [ConnectivityObserver]'s job alone, and
 * this class used to combine the two into one [StateFlow] the real-time badge
 * rendered directly, which is exactly the conflation that spec calls out).
 *
 * "Do not show the system message if there are record[s] automatically
 * syncing" — [messages] only ever fires for a *manual* sync (the explicit
 * "Sync to Server" button, see [SyncWorker.KEY_MANUAL]) or an offline write
 * being queued; every automatic trigger (periodic floor, reconnect,
 * [SyncScheduler.triggerSyncIfOnline]) still runs silently, no toast.
 */
class SyncStatusCenter(
    private val syncQueueDao: SyncQueueDao,
    private val appScope: CoroutineScope,
) {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One-shot system notifications — collected exactly once, app-wide, by
     * [com.emfitsolutions.gopreach.ui.components.SyncMessageHost]. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Rows [SyncWorker] gave up retrying for good (spec: "keep the failed
     * operation for investigation... show a meaningful synchronization
     * error"), never silently dropped — drives the "Sync Error" recovery
     * affordance (see [com.emfitsolutions.gopreach.ui.components
     * .SyncStatusIndicator]), kept entirely separate from the Online/Offline
     * badge next to it. */
    val permanentFailureCount: StateFlow<Int> = syncQueueDao.observePermanentFailureCount()
        .stateIn(appScope, SharingStarted.Eagerly, 0)

    /** Called by [OfflineFirestoreRepository] right after a local write is queued.
     * Only worth announcing while offline — an online write is about to be
     * flushed almost immediately (see [SyncScheduler.triggerSyncIfOnline]), so a
     * "waiting for internet" message would be actively misleading. Spec §6's
     * exact wording. */
    fun onWriteQueued(deviceIsOnline: Boolean) {
        if (!deviceIsOnline) {
            _messages.tryEmit("Offline — Changes saved locally and will sync automatically when internet is available.")
        }
    }

    private val _isSyncing = MutableStateFlow(false)

    /** True only while [SyncWorker] is actually uploading queued rows — what lets a
     * record's own "Pending Sync" badge become "Syncing" for the moment it is in
     * flight (see [com.emfitsolutions.gopreach.data.repository.TerritoryDrawingRepository]).
     * Deliberately not an Online/Offline indicator — that's [ConnectivityObserver]'s job. */
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    /** Called by [SyncWorker] at the start of every flush attempt that has
     * something to upload, manual or automatic. */
    fun onSyncStarted() {
        _isSyncing.value = true
    }

    /** Called by [SyncWorker] once the upload loop has finished (or bailed out). */
    fun onSyncEnded() {
        _isSyncing.value = false
    }

    /** Called by [SyncWorker] once a flush attempt finishes (whether or not
     * everything in it succeeded). [isManual] gates the toast — see this
     * class's own doc comment: an automatic run never interrupts with one.
     * [failed] is transient/retryable failures only — a permanent failure is
     * tracked separately via [permanentFailureCount], never through this. */
    fun onSyncFinished(uploaded: Int, failed: Int, isManual: Boolean) {
        if (!isManual) return
        when {
            failed > 0 -> _messages.tryEmit("Sync temporarily unavailable — retrying automatically.")
            uploaded > 0 -> _messages.tryEmit("✓ Sync completed successfully.")
            // uploaded == 0 && failed == 0: nothing was pending — a silent no-op,
            // not worth a system message.
        }
    }
}
