package com.emfitsolutions.gopreach.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.emfitsolutions.gopreach.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectivityObserver: ConnectivityObserver,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    companion object {
        const val UNIQUE_WORK_NAME = "gopreach_sync_queue"

        /** Separate unique name from [UNIQUE_WORK_NAME] — periodic and
         * one-time work can't share a unique name in WorkManager, and this
         * keeps the automatic floor sync independent of whatever the manual
         * button's own request is currently doing. */
        private const val PERIODIC_AUTO_SYNC_WORK_NAME = "gopreach_auto_sync_periodic"

        /** WorkManager's own minimum interval for periodic work — this is
         * a floor for a long-running session that never actually saw an
         * offline→online *transition* (see [ensureAutomaticSyncStarted]'s
         * doc comment), not the primary trigger. */
        private const val AUTO_SYNC_INTERVAL_MINUTES = 15L
    }

    private var autoSyncStarted = false

    /** "Make the App Synchronize to server automatically if there are
     * internet or mobile data available" — supersedes this class's old
     * "Manual Sync Requirement" design (the button/[requestSyncNow] itself
     * still behaves exactly as documented there; this adds a second,
     * automatic trigger alongside it, not a replacement for it). Two
     * complementary triggers, both ultimately just enqueueing [SyncWorker]:
     *
     * 1. Immediately whenever [ConnectivityObserver] reports the device just
     *    came online — a real offline→online transition, not "was already
     *    online when this was called."
     * 2. A periodic WorkManager job (floor: every [AUTO_SYNC_INTERVAL_MINUTES]
     *    minutes, WorkManager's own minimum) with a `NetworkType.CONNECTED`
     *    constraint — covers a long session that never actually transitioned
     *    offline→online (connectivity was already up when the app launched,
     *    so trigger (1) never fires), and is a safety net if a connectivity
     *    callback is ever missed.
     *
     * Both are enqueued with [ExistingWorkPolicy.KEEP]/[ExistingPeriodicWorkPolicy.KEEP]
     * — an automatic run never cancels an in-flight sync the user is
     * actively watching progress on via [requestSyncNow]'s own tracked id
     * (nor does it need to: if one's already running, there's nothing this
     * automatic trigger would add by starting a second one). Called once
     * from [com.emfitsolutions.gopreach.GoPreachApp.onCreate] — repeat calls
     * are a no-op via [autoSyncStarted].
     */
    fun ensureAutomaticSyncStarted() {
        if (autoSyncStarted) return
        autoSyncStarted = true

        connectivityObserver.observe()
            .onEach { online -> if (online) enqueueAutomaticSyncNow() }
            .launchIn(appScope)

        val periodicRequest = PeriodicWorkRequestBuilder<SyncWorker>(AUTO_SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(networkConnectedConstraints())
            .setBackoffCriteria(standardBackoffPolicy, standardBackoffDelayMillis, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_AUTO_SYNC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, periodicRequest)
    }

    /** `NetworkType.CONNECTED` (not restricted to Wi-Fi) — spec §13: sync
     * works over Wi-Fi, mobile data, or any other connection Android itself
     * considers usable; nothing in this scheduler singles out Wi-Fi. */
    private fun networkConnectedConstraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Spec §3.9 — an explicit, appropriate backoff strategy rather than
     * relying on WorkManager's own unstated default: exponential, starting
     * at WorkManager's own minimum ([WorkRequest.MIN_BACKOFF_MILLIS], 10s),
     * doubling on each further [androidx.work.ListenableWorker.Result.retry]
     * up to WorkManager's own internal cap (~5h) — quick to recover from a
     * brief blip, without hammering Firebase/the network during a longer
     * outage. Applied to every [SyncWorker] request this scheduler enqueues
     * (the periodic safety net above, the automatic reconnect trigger below,
     * and the tracked manual "Sync to Server" request further down) so all
     * three retry the same way. */
    private val standardBackoffPolicy = BackoffPolicy.EXPONENTIAL
    private val standardBackoffDelayMillis = WorkRequest.MIN_BACKOFF_MILLIS

    private fun enqueueAutomaticSyncNow() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConnectedConstraints())
            .setBackoffCriteria(standardBackoffPolicy, standardBackoffDelayMillis, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** Called by [OfflineFirestoreRepository] right after a local write is
     * queued, so a change made *while already online* reaches the server
     * within moments instead of waiting for the next connectivity transition
     * or the [AUTO_SYNC_INTERVAL_MINUTES] periodic floor — closing the gap
     * where "automatic sync" otherwise only fired on offline→online edges.
     * A no-op while offline (the write just stays queued, as always).
     * [ExistingWorkPolicy.KEEP] means this never interrupts a run already in
     * flight (including one the user is watching via [requestSyncNow]). */
    fun triggerSyncIfOnline() {
        if (connectivityObserver.isOnline()) enqueueAutomaticSyncNow()
    }

    /** Enqueues a flush of the pending-operations queue and returns that specific
     * request's id — the *tracked* path, called by an explicit user action:
     * the "SYNC TO SERVER" button, the header
     * [com.emfitsolutions.gopreach.ui.components.SyncStatusButton] shortcut,
     * or a manual pull-to-refresh. [ensureAutomaticSyncStarted] is the other,
     * untracked path — the app also syncs automatically now (superseding
     * this class's old "never automatically" design), just through that
     * separate method rather than this one, since a caller here is expected
     * to actually watch [observeWorkInfo] for *this* id, which an automatic
     * background trigger has no UI to do anything with. Still carries a
     * network constraint so a request made while offline simply waits rather
     * than failing outright; [SyncToServerButton] checks connectivity itself
     * beforehand precisely so it can show the "No Network Connection" message
     * instead of silently queuing a run for whenever connectivity returns.
     *
     * [ExistingWorkPolicy.REPLACE], not `APPEND_OR_REPLACE` — the latter chains
     * every run under the same unique work name, so old completed/cancelled
     * requests can keep sitting alongside a new one under that name. REPLACE
     * cancels/discards whatever was there first. Still, a caller must observe
     * *this specific request's* id (see [observeWorkInfo]) — not "whatever the
     * unique work name currently returns" — since [WorkInfo.generation] does
     * **not** distinguish between separate `REPLACE`d requests the way an
     * earlier version of this code assumed (it only increments for a periodic
     * work request updated in place); picking "the highest generation" from
     * the unique-work list was still ambiguous and could still resolve to a
     * stale request. Tracking the request's own id sidesteps that ambiguity
     * entirely — this is the actual fix for "Sync to Server" getting
     * permanently stuck on "Checking for pending changes...". */
    fun requestSyncNow(): UUID {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConnectedConstraints())
            .setBackoffCriteria(standardBackoffPolicy, standardBackoffDelayMillis, TimeUnit.MILLISECONDS)
            // "Do not show the system message if there are record[s]
            // automatically syncing" — this flag is what lets SyncWorker/
            // SyncStatusCenter tell this, the one explicit "Sync to Server"
            // path, apart from every automatic trigger below.
            .setInputData(androidx.work.workDataOf(SyncWorker.KEY_MANUAL to true))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        return request.id
    }

    /** The live state of exactly the request [requestSyncNow] returned the id
     * for — unambiguous regardless of whatever else has ever run under
     * [UNIQUE_WORK_NAME]. [WorkInfo.progress] carries the in-progress upload
     * counters, [WorkInfo.state] the run/finished status (see
     * [SyncWorker.KEY_FINISHED]). */
    fun observeWorkInfo(id: UUID): Flow<WorkInfo?> =
        WorkManager.getInstance(context).getWorkInfoByIdFlow(id)
}
