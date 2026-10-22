package com.emfitsolutions.gopreach.data.sync

import android.util.Log
import com.emfitsolutions.gopreach.domain.UserSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * While a person is signed in and the device is online, runs one sync round (upload the queue, download what changed) every 20 seconds
 * while the app is on screen, and at once when it comes back to the front. The server has no live push, so this is how other people's
 * changes (chat messages, new reports, status moves) reach the screen within seconds.
 *
 * It also keeps going, more slowly (once a minute), while the app is minimized but its process is still alive: the notification sounds
 * ([com.emfitsolutions.gopreach.notifications.NotificationSoundCoordinator]) fire when new data lands in the local copy, so without this a
 * chat message or a new report would stay silent until the app was opened again. A swiped-away or system-killed app cannot poll; the
 * periodic background sync ([SyncScheduler]) is the only thing that wakes it then.
 */
class BackendPoller(
    private val userSession: UserSession,
    private val connectivityObserver: ConnectivityObserver,
    private val syncEngine: SyncEngine,
    private val appScope: CoroutineScope,
) {
    private var started = false
    private val round = Mutex() // the timed rounds and the change feed never run a sync at the same time

    @Volatile private var lastRun = 0L

    private suspend fun syncRound() = round.withLock {
        runCatching { syncEngine.syncOnce() }.onFailure { Log.w("BackendPoller", "sync round failed: ${it.message}") }
        lastRun = System.currentTimeMillis()
    }

    fun start() {
        if (!BackendConfig.enabled || started) return
        started = true
        // Timed rounds: the safety net, the upload of anything queued, and the catch-up whenever the app comes back to the front or the connection returns.
        appScope.launch {
            var wasVisible = false
            var wasOnline = true
            while (true) {
                val visible = AppForeground.visible
                val online = connectivityObserver.isOnline()
                val now = System.currentTimeMillis()
                val due = if (visible) !wasVisible || (online && !wasOnline) || now - lastRun >= VISIBLE_INTERVAL_MS else now - lastRun >= BACKGROUND_INTERVAL_MS || (online && !wasOnline)
                if (due && userSession.state.value.person != null && online) syncRound()
                wasVisible = visible
                wasOnline = online
                delay(CHECK_MS)
            }
        }
        // The change feed: while the app is on screen, ask the server what changed. It answers the moment another device saves, so a record added, edited or
        // deleted elsewhere reaches this screen within a second or two instead of at the next timed round. An older server (null) or a failure falls back to the timed rounds.
        appScope.launch {
            while (true) {
                if (AppForeground.visible && userSession.state.value.person != null && connectivityObserver.isOnline()) {
                    when (runCatching { syncEngine.waitForChange(WATCH_SECONDS) }.getOrNull()) {
                        true -> syncRound()
                        false -> Unit
                        null -> delay(WATCH_RETRY_MS)
                    }
                } else {
                    delay(CHECK_MS)
                }
            }
        }
    }

    private companion object {
        const val VISIBLE_INTERVAL_MS = 20_000L
        const val BACKGROUND_INTERVAL_MS = 60_000L
        const val CHECK_MS = 3_000L
        const val WATCH_SECONDS = 8 // shorter than the HTTP client's read timeout
        const val WATCH_RETRY_MS = 10_000L
    }
}
