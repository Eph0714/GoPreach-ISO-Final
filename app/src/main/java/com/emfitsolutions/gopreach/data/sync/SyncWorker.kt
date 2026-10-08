package com.emfitsolutions.gopreach.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * Runs one sync round with the GoPreach server whenever a network connection is available (see [SyncScheduler]): the offline queue is
 * uploaded in the order it was written, then what changed on the server is downloaded ([SyncEngine.syncOnce]). This is the other half
 * of offline-first: local writes always succeed instantly, and this worker is what eventually makes them durable on the server.
 *
 * It also drives the "SYNC TO SERVER" button's progress/summary UI (`SyncToServerButton`): the uploaded / failed counts are published
 * with [setProgress] so the button can show a real "Sync Complete — N uploaded, N failed" instead of a bare success/failure flag.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
    private val connectivityObserver: ConnectivityObserver,
    private val syncStatusCenter: SyncStatusCenter,
    private val syncEngine: SyncEngine,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_UPLOADED = "uploaded"
        const val KEY_FAILED = "failed"
        const val KEY_TOTAL = "total"
        const val KEY_FINISHED = "finished"
        /** Set whenever this run did nothing because [ConnectivityObserver] reported no usable internet. The manual "Sync to Server" UI
         * reads it to show its own "no internet connection" state instead of a misleading "Sync Complete" / "0 failed" summary. */
        const val KEY_SKIPPED_OFFLINE = "skippedOffline"
        /** Set only by [SyncScheduler.requestSyncNow] (the explicit "Sync to Server" button); every automatic trigger leaves it false,
         * which is what [SyncStatusCenter.onSyncFinished] reads to decide whether this run is worth a toast at all. */
        const val KEY_MANUAL = "manual"
    }

    override suspend fun doWork(): Result {
        val isManual = inputData.getBoolean(KEY_MANUAL, false)

        // Mandatory connectivity gate. WorkManager's own CONNECTED constraint only says "a network exists" (Wi-Fi with no internet
        // still passes), so the app's own validated-internet state is checked here too. Offline ends the run as a success (never a
        // retry): nothing is touched, every pending change stays pending, and the next attempt comes from a real reconnect or the
        // periodic floor, never from this worker re-arming its own backoff on a dead connection.
        if (!connectivityObserver.isOnline()) {
            setProgress(workDataOf(KEY_UPLOADED to 0, KEY_FAILED to 0, KEY_TOTAL to 0, KEY_FINISHED to true, KEY_SKIPPED_OFFLINE to true))
            return Result.success()
        }

        syncStatusCenter.onSyncStarted()
        val report = try { syncEngine.syncOnce() } finally { syncStatusCenter.onSyncEnded() }
        val failed = if (report.transportError != null) 1 else 0
        if (report.transportError == null) syncStatusCenter.onSyncFinished(report.uploaded, report.rejected, isManual)
        setProgress(workDataOf(KEY_UPLOADED to report.uploaded, KEY_FAILED to failed + report.rejected, KEY_TOTAL to report.uploaded + failed + report.rejected, KEY_FINISHED to true))
        return if (report.transportError != null) Result.retry() else Result.success()
    }
}
