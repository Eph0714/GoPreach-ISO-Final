package com.emfitsolutions.gopreach.data.sync

import android.util.Log
import com.emfitsolutions.gopreach.domain.UserSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hostinger mode only: while the app is on screen, a person is signed in and the device is online, runs one sync round (upload the queue,
 * download what changed) every 20 seconds, and at once when the app comes back to the front. The server has no live push, so this is
 * how other people's changes (chat messages, new reports, status moves) reach the screen within seconds. Nothing runs in the background.
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
                val due = visible && (!wasVisible || now - lastRun >= INTERVAL_MS)
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
        const val INTERVAL_MS = 20_000L
        const val CHECK_MS = 3_000L
    }
}
