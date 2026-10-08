package com.emfitsolutions.gopreach.data.sync

import android.util.Log
import com.emfitsolutions.gopreach.data.remote.SyncApi
import com.emfitsolutions.gopreach.domain.UserSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "PresenceHeartbeat"

/**
 * A device is only counted "online" while its most recent heartbeat is within this window — 2.5 heartbeat intervals, close enough to
 * real-time that a session going offline disappears from the list within about a minute, while still tolerating one heartbeat landing a
 * little late (a brief network hiccup) without flapping a still-active user in and out of the list. The server applies the same window
 * (`ONLINE_WINDOW_MS` in backend/src/presence.js), and the Online Users screen re-checks it against the clock.
 */
const val PRESENCE_ONLINE_TIMEOUT_MS = 75_000L
private const val HEARTBEAT_INTERVAL_MS = 30_000L
private const val HEARTBEAT_CHECK_MS = 5_000L

/**
 * "Online Users": while a person is signed in, online and the app is on screen, tells the server "I am here" at once and then every
 * 30 seconds (`POST /v1/presence`). Who is online is kept in the server's memory, not in the database or the sync log.
 *
 * Nothing is sent while the app is in the background or the device is offline, and nothing is queued or retried: if the device is
 * offline this session genuinely is not online. A closed app, a crash or a lost connection simply stops the heartbeats, and the
 * [PRESENCE_ONLINE_TIMEOUT_MS] window is what makes that person read as offline everywhere else.
 */
class PresenceHeartbeat(
    private val userSession: UserSession,
    private val connectivityObserver: ConnectivityObserver,
    private val appScope: CoroutineScope,
    private val syncApi: SyncApi,
) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        appScope.launch {
            var lastBeat = 0L
            var wasVisible = false
            while (true) {
                val visible = AppForeground.visible
                val now = System.currentTimeMillis()
                if (visible && userSession.state.value.person != null && connectivityObserver.isOnline() &&
                    (!wasVisible || now - lastBeat >= HEARTBEAT_INTERVAL_MS)
                ) {
                    runCatching {
                        syncApi.postJson("/v1/presence", kotlinx.serialization.json.buildJsonObject { put("beat", kotlinx.serialization.json.JsonPrimitive(true)) })
                    }.onFailure { Log.w(TAG, "Heartbeat failed: ${it.message}") }
                    lastBeat = now
                }
                wasVisible = visible
                delay(HEARTBEAT_CHECK_MS)
            }
        }
    }
}
