package com.emfitsolutions.gopreach.data.sync

import android.util.Log
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val TAG = "FirestoreMirror"

// Bug fix (2026-09-12, "PERMISSION_DENIED keeps firing over and over for the
// same collection, dozens of times, always 'attempt 1', never settling —
// reproduced live even with the debounce on RemoteSyncCoordinator's own
// uid stream"): whatever is ultimately forcing a fresh subscription this
// often (this app's own [RemoteSyncCoordinator.uidChanged] shows zero
// matching Firebase Auth state-change events during the exact window this
// happens, so the trigger is not fully understood yet), each fresh
// `mirrorFirestoreCollection` call was registering a brand new
// `addSnapshotListener` for the same collection *before* the previous one's
// `awaitClose { registration.remove() }` was guaranteed to have actually run
// (Flow cancellation across a `flatMapLatest` boundary is not synchronous),
// so multiple real listeners for the very same collection piled up
// concurrently — confirmed live by matching pairs/triples of the identical
// log line at the same millisecond. Every one of those overlapping
// registrations independently raced the same "token not attached yet"
// startup window, and each had its own fresh `retryCount`, so the retry
// backoff this file already has could never actually reach attempt 2+ before
// getting superseded by the next pile-on. This registry makes at most one
// live listener per collection path possible at any moment app-wide:
// registering a new one now synchronously removes whatever registration
// already existed for that same path first, so the pile-up itself — not
// just one symptom of it — is now structurally impossible, regardless of
// what's forcing the re-subscriptions.
private val activeRegistrations = ConcurrentHashMap<String, ListenerRegistration>()

// Bug fix ("Forwarded to Me never shows a request that genuinely exists" /
// "the same collection works on one launch and not the next, with nothing
// else different") — reproduced live on a real device: a snapshot listener
// registered at cold start (already signed in from a previous session, not a
// fresh sign-in) can hit a single transient PERMISSION_DENIED in the split
// second before the SDK's cached ID token is fully attached to outgoing
// requests. [RemoteSyncCoordinator] only ever re-subscribes on a genuine
// Firebase Auth *state change* (a real sign-out/sign-in) — a cold launch
// while already signed in never fires one, so that one collection's listener
// was permanently dead for the rest of the session while every other
// collection (registered a few milliseconds later, after the token
// settled) kept working fine. A capped, short-backoff retry here recovers
// automatically from that transient case; a *genuine* permission rejection
// (a restricted user missing the right grant, say) just keeps failing after
// the attempts run out, exactly as before this fix.
private const val MAX_RETRY_ATTEMPTS = 5
private const val RETRY_BASE_DELAY_MS = 2_000L

/**
 * Attaches a live Firestore snapshot listener on [query] and mirrors every
 * change into the offline cache via [offline], so every domain repository gets the
 * same "cache is always current when online, always available when offline"
 * behavior without re-implementing the listener each time (see e.g.
 * [com.emfitsolutions.gopreach.data.repository.PersonRepository.startRemoteSync]).
 *
 * [collectionPath] is used only for the local cache's own bookkeeping
 * (registration dedup, cache/delete keys) — the actual data fetched is
 * whatever [query] describes, which callers needing every publisher's own
 * data scoped to just their own records (see [com.emfitsolutions.gopreach
 * .data.repository.PlannerDayRepository.startRemoteSync] for why: this app's
 * "My Planner" collections deliberately restrict both the security rule and
 * the query to `publisherPersonId == <the signed-in Publisher>`, not "every
 * signed-in Publisher's data") build with `.whereEqualTo(...)` before passing
 * it in; a caller with no such restriction just passes the plain
 * `firestore.collection(collectionPath)`.
 */
fun <T : Any> mirrorFirestoreCollection(
    firestore: FirebaseFirestore,
    offline: OfflineFirestoreRepository,
    appScope: CoroutineScope,
    collectionPath: String,
    clazz: Class<T>,
    query: Query = firestore.collection(collectionPath),
    idOf: (T) -> String,
): Flow<Unit> = callbackFlow {
    var retryCount = 0

    fun attach() {
        // Synchronously supersede any prior registration for this exact
        // collection path — see this file's own top-of-file doc comment.
        activeRegistrations.remove(collectionPath)?.remove()
        val registration = query.addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) {
                if (error != null) {
                    Log.w(TAG, "Listener for '$collectionPath' failed (attempt ${retryCount + 1}): ${error.message}")
                    // See this file's own top-of-file doc comment for why a capped
                    // retry (rather than treating every failure as permanent) is
                    // correct here.
                    if (retryCount < MAX_RETRY_ATTEMPTS) {
                        retryCount++
                        appScope.launch {
                            delay(RETRY_BASE_DELAY_MS * retryCount)
                            attach()
                        }
                    }
                }
                return@addSnapshotListener
            }
            retryCount = 0
            appScope.launch {
                for (change in snapshot.documentChanges) {
                    try {
                        val model = change.document.toObject(clazz)
                        when (change.type) {
                            // Cache-only — never offline.save()/delete() here. Those enqueue a
                            // pending *upload*, which is wrong for a document that just came
                            // *from* the server: it was silently re-queuing every document a
                            // listener had ever seen (including its entire initial snapshot)
                            // as if the user had edited it, inflating "pending changes" by
                            // hundreds for data nobody touched.
                            DocumentChange.Type.REMOVED -> offline.deleteFromServer(collectionPath, idOf(model))
                            else -> offline.cacheFromServer(collectionPath, idOf(model), model)
                        }
                    } catch (e: Exception) {
                        // Reproduced, confirmed root cause of "the app closes right after a
                        // correct login, right when the Main Form should appear": this
                        // `appScope.launch` had no try/catch, so a single malformed document
                        // (e.g. a stale sharedLocations doc whose body contained a real
                        // 'publisherPersonId' field colliding with that property's
                        // @DocumentId annotation) threw here on an uncaught background
                        // coroutine and killed the whole process — for every session, since
                        // every collection is mirrored immediately after sign-in. One bad
                        // document is now skipped instead, and every other document (and
                        // every other collection) keeps syncing normally.
                        Log.e(TAG, "Skipping malformed document ${change.document.id} in '$collectionPath'", e)
                    }
                }
            }
            trySend(Unit)
        }
        activeRegistrations[collectionPath] = registration
    }
    attach()
    awaitClose { activeRegistrations.remove(collectionPath)?.remove() }
}

/**
 * A one-shot counterpart to [mirrorFirestoreCollection] — fetches
 * [collectionPath] with a single `get()` instead of a persistent
 * `addSnapshotListener()`. "Make sync also pull the latest data down, the
 * same way it already pushes local changes up": a live listener is a
 * long-running streamed connection, held open and re-authenticated behind
 * the scenes by Firestore's SDK for as long as it's subscribed — on a device
 * whose Google Play Services install can't sustain that (see
 * [RemoteSyncCoordinator.retryIfNeeded]'s doc comment for a real one found
 * this way), it can fail indefinitely even with a perfectly valid, freshly
 * refreshed sign-in. A single `get()` is a plain request/response call, not
 * a held-open stream, so it doesn't depend on whatever keeps failing to
 * *sustain* one — worth trying as a fallback specifically because it is a
 * meaningfully different code path, not just "try the same thing again."
 * [Source.SERVER] deliberately skips Firestore's local result cache, since
 * the whole point is finding out what the *server* actually has.
 */
suspend fun <T : Any> pullFirestoreCollectionOnce(
    firestore: FirebaseFirestore,
    offline: OfflineFirestoreRepository,
    collectionPath: String,
    clazz: Class<T>,
    query: Query = firestore.collection(collectionPath),
    idOf: (T) -> String,
) {
    val snapshot = query.get(Source.SERVER).await()
    for (document in snapshot.documents) {
        try {
            val model = document.toObject(clazz) ?: continue
            offline.cacheFromServer(collectionPath, idOf(model), model)
        } catch (e: Exception) {
            // Same "one bad document must never take down the rest" rule as
            // the live-listener path above.
            Log.e(TAG, "Skipping malformed document ${document.id} in '$collectionPath' during one-shot pull", e)
        }
    }
}
