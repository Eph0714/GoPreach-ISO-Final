package com.emfitsolutions.gopreach.data.sync

import android.util.Log
import com.emfitsolutions.gopreach.domain.UserSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    fun start() {
        if (!BackendConfig.enabled || started) return
        started = true
        appScope.launch {
            var wasVisible = false
            var lastRun = 0L
            while (true) {
                val visible = AppForeground.visible
                val now = System.currentTimeMillis()
                val due = if (visible) !wasVisible || now - lastRun >= VISIBLE_INTERVAL_MS else now - lastRun >= BACKGROUND_INTERVAL_MS
                if (due && userSession.state.value.person != null && connectivityObserver.isOnline()) {
                    runCatching { syncEngine.syncOnce() }.onFailure { Log.w("BackendPoller", "sync round failed: ${it.message}") }
                    lastRun = System.currentTimeMillis()
                }
                wasVisible = visible
                delay(CHECK_MS)
            }
        }
    }

    private companion object {
        const val VISIBLE_INTERVAL_MS = 20_000L
        const val BACKGROUND_INTERVAL_MS = 60_000L
        const val CHECK_MS = 3_000L
    }
}
