package com.emfitsolutions.gopreach.data.sync

import android.util.Log
import com.emfitsolutions.gopreach.di.ApplicationScope
import com.emfitsolutions.gopreach.domain.UserSession
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PresenceHeartbeat"

/** Firestore collection this writes/reads — `presence/{personId}`, one document per
 * currently-signed-in session. See [com.emfitsolutions.gopreach.ui.components
 * .OnlineUsersIndicator] for the read side and `firestore.rules`' own `presence`
 * match block for the congregation-scoped access rule this depends on. */
const val PRESENCE_COLLECTION = "presence"

/** A device is only counted "online" while its most recent heartbeat is within this
 * window — 2.5 heartbeat intervals, close enough to real-time that a session going
 * offline disappears from the list within seconds, while still tolerating one
 * heartbeat landing a little late (a brief network hiccup) without flapping a
 * still-active user in and out of the list. There is no Cloud Functions / Realtime
 * Database `onDisconnect` in this app (see this file's own doc comment on
 * [PresenceHeartbeat] for why an explicit delete on sign-out is still only a
 * best-effort nicety, never the actual detection mechanism) — this timeout is what
 * actually makes "closed the app / lost connection / crashed" read as Offline
 * everywhere else, and [com.emfitsolutions.gopreach.ui.components
 * .OnlineUsersViewModel]'s own 2-second ticker is what re-evaluates it against the
 * clock continuously rather than only when a new heartbeat happens to arrive. */
const val PRESENCE_ONLINE_TIMEOUT_MS = 20_000L
private const val HEARTBEAT_INTERVAL_MS = 8_000L

/**
 * "GoPreach App — Add Online Users Indicator": keeps exactly one `presence`
 * document current for the signed-in session, for as long as it's both signed in
 * and online — nothing else in the app writes to this collection.
 *
 * Deliberately NOT routed through [OfflineFirestoreRepository]'s offline queue —
 * a presence heartbeat has no meaning to preserve while offline (there's nothing
 * to "sync later"; if the device is offline, this session genuinely isn't
 * online, full stop), so it writes straight to Firestore, best-effort, and simply
 * stops entirely while [ConnectivityObserver] reports no internet — never queued,
 * never retried, exactly the "no repeated network attempts while Offline" rule
 * the rest of the sync system already follows.
 *
 * What this can and can't guarantee, honestly: a graceful sign-out deletes this
 * session's own presence document immediately (best-effort). An app kill, crash,
 * or force-quit cannot run that cleanup — this app has no Realtime Database
 * `onDisconnect` hook and deliberately no Cloud Functions (same trade-off
 * documented elsewhere in this codebase, e.g. `firestore.rules`' own file-level
 * note on what a rules-only backend can't do). [PRESENCE_ONLINE_TIMEOUT_MS] is
 * what actually catches that case — every reader treats a stale `lastSeen` as
 * Offline regardless of whether this document was ever explicitly deleted.
 */
@Singleton
class PresenceHeartbeat @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val userSession: UserSession,
    private val connectivityObserver: ConnectivityObserver,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        appScope.launch {
            userSession.state
                .map { it.person?.id }
                .distinctUntilChanged()
                .collectLatest { personId ->
                    if (personId == null) return@collectLatest
                    try {
                        runForSignedInPerson(personId)
                    } finally {
                        // Best-effort only — see this class's own doc comment.
                        // NonCancellable: this runs *during* cancellation (sign-out,
                        // or switching to a different personId), which would
                        // otherwise cancel this cleanup call before it completes.
                        withContext(NonCancellable) {
                            runCatching { firestore.collection(PRESENCE_COLLECTION).document(personId).delete().await() }
                                .onFailure { Log.w(TAG, "Failed to clear presence for $personId: ${it.message}") }
                        }
                    }
                }
        }
    }

    /** Runs for as long as [personId] stays signed in — starts/stops the actual
     * heartbeat loop as [UserSession]'s own active congregation or
     * [ConnectivityObserver]'s online state changes, without ever deleting the
     * presence document just for a transient offline blip (only the outer
     * [start] does that, and only on an actual sign-out/account switch). */
    private suspend fun runForSignedInPerson(personId: String) {
        combine(
            userSession.state.map { it.person?.activeCongregationId }.distinctUntilChanged(),
            connectivityObserver.observe(),
        ) { congregationId, online -> congregationId to online }
            .distinctUntilChanged()
            .collectLatest { (congregationId, online) ->
                if (!online) return@collectLatest
                heartbeatLoop(personId, congregationId)
            }
    }

    private suspend fun heartbeatLoop(personId: String, congregationId: String?) {
        while (true) {
            runCatching {
                firestore.collection(PRESENCE_COLLECTION).document(personId)
                    .set(mapOf("congregationId" to congregationId, "lastSeen" to System.currentTimeMillis()))
                    .await()
            }.onFailure { Log.w(TAG, "Heartbeat failed for $personId: ${it.message}") }
            delay(HEARTBEAT_INTERVAL_MS)
        }
    }
}
