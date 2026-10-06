package com.emfitsolutions.gopreach.data.repository

import android.util.Log
import com.emfitsolutions.gopreach.data.model.Visit
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.mirrorFirestoreCollection
import com.emfitsolutions.gopreach.di.ApplicationScope
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VisitRepository"

private fun visitsPath(interestedPersonId: String) = "interestedPeople/$interestedPersonId/visits"

/** Spec §6.3 — one or more preaching visits per [com.emfitsolutions.gopreach.data.model.InterestedPerson]. */
@Singleton
class VisitRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    fun observeForInterestedPerson(interestedPersonId: String): Flow<List<Visit>> =
        offline.observeCollection(visitsPath(interestedPersonId))

    /** Unlike the app's other collections, visits are scoped per-parent-document,
     * so the live listener is started per interested person on demand (e.g. when
     * opening their detail screen) rather than once app-wide. */
    fun startRemoteSync(interestedPersonId: String): Flow<Unit> =
        mirrorFirestoreCollection(firestore, offline, appScope, visitsPath(interestedPersonId), Visit::class.java) { it.id }

    /** "Pioneer – My Return Visits" spec §8/§10 — every Visit across *every*
     * Interested Person belonging to [publisherPersonId], not just whichever
     * ones the user has personally opened the detail screen for this
     * session. Reads from the same local-cache path shape
     * [observeForInterestedPerson] already uses ("visits" under each person),
     * kept current by [startRemoteSyncForPublisher]'s collection-group
     * listener — this is a read-only aggregation, no new write path. */
    fun observeAllForPublisher(publisherPersonId: String): Flow<List<Visit>> =
        observeAllVisits().map { list -> list.filter { it.publisherPersonId == publisherPersonId } }

    /** "Consolidated Monthly Report" — every Visit across every Interested
     * Person, unfiltered by publisher, for the multi-publisher congregation
     * view. Kept current by [startRemoteSyncAllForCongregationView] instead
     * of per-publisher listeners (this app's scale — a congregation's
     * publishers, not millions of rows — makes one broad listener simpler
     * and cheaper than one collection-group listener per publisher shown on
     * the report). */
    fun observeAllVisits(): Flow<List<Visit>> = offline.observeCollectionsMatching("interestedPeople/%/visits")

    /** The one collection-group query in this app — needed because Visits are
     * a subcollection of a variable, per-person parent, so there's no single
     * fixed path [mirrorFirestoreCollection] could listen to for "all of one
     * Publisher's visits at once." Mirrors into the exact same cache path
     * each document's own [Visit.interestedPersonId] implies, so this reads
     * back correctly through both [observeForInterestedPerson] (one person)
     * and [observeAllForPublisher] (every person) without divergence.
     *
     * Operational note: a Firestore collection-group query needs that field
     * indexed for collection-group scope, not just per-collection — if this
     * is the very first collection-group query ever run against this
     * project, Firestore's error will include a direct link to create that
     * index in the Console; that's a one-time setup step, not a bug. */
    fun startRemoteSyncForPublisher(publisherPersonId: String): Flow<Unit> =
        mirrorVisitsCollectionGroup(firestore.collectionGroup("visits").whereEqualTo("publisherPersonId", publisherPersonId))

    /** "Consolidated Monthly Report" spec — a Service Overseer/Coordinator
     * Elder/Admin/Super-Admin needs every publisher's Visits in their scope
     * at once, not just one publisher's; unfiltered collection-group query,
     * started only while that report screen is open (see
     * ConsolidatedReportViewModel), not app-wide at every login. */
    fun startRemoteSyncAllForCongregationView(): Flow<Unit> = mirrorVisitsCollectionGroup(firestore.collectionGroup("visits"))

    private fun mirrorVisitsCollectionGroup(query: Query): Flow<Unit> = callbackFlow {
        val registration = query.addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) {
                if (error != null) Log.w(TAG, "Collection-group listener for visits failed: ${error.message}")
                return@addSnapshotListener
            }
            appScope.launch {
                for (change in snapshot.documentChanges) {
                    try {
                        val visit = change.document.toObject(Visit::class.java)
                        val path = visitsPath(visit.interestedPersonId)
                        when (change.type) {
                            DocumentChange.Type.REMOVED -> offline.deleteFromServer(path, visit.id)
                            else -> offline.cacheFromServer(path, visit.id, visit)
                        }
                    } catch (e: Exception) {
                        // Same defensive containment as FirestoreMirror's —
                        // one malformed document must never take the whole
                        // listener (or the app) down with it.
                        Log.e(TAG, "Skipping malformed visit document ${change.document.id}", e)
                    }
                }
            }
            trySend(Unit)
        }
        awaitClose { registration.remove() }
    }

    suspend fun save(visit: Visit): Visit {
        val path = visitsPath(visit.interestedPersonId)
        val id = visit.id.ifBlank { firestore.collection(path).document().id }
        val withId = visit.copy(id = id)
        offline.save(path, id, withId)
        return withId
    }

    suspend fun delete(interestedPersonId: String, visitId: String) =
        offline.delete(visitsPath(interestedPersonId), visitId)
}
