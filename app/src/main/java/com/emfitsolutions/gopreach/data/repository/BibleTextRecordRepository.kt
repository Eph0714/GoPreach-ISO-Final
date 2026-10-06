package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.BibleTextRecord
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.mirrorFirestoreCollection
import com.emfitsolutions.gopreach.data.sync.pullFirestoreCollectionOnce
import com.emfitsolutions.gopreach.di.ApplicationScope
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val COLLECTION = "bibleTextRecords"

/** "My Bible Text Record" module spec §1-§9/§13-§20 — a Publisher's own
 * saved Bible references. Client-side scoping here ([observeForPublisher])
 * is a convenience filter, not the security boundary — the actual
 * "Publisher A can't read/edit/delete Publisher B's records" enforcement is
 * server-side, in firestore.rules' own `bibleTextRecords` match block (spec
 * §20: "Never trust a PublisherID supplied by the frontend... perform
 * ownership checks on the backend"). */
@Singleton
class BibleTextRecordRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    fun observeForPublisher(publisherPersonId: String): Flow<List<BibleTextRecord>> =
        offline.observeCollection<BibleTextRecord>(COLLECTION)
            .map { list -> list.filter { it.publisherPersonId == publisherPersonId } }

    suspend fun save(record: BibleTextRecord): BibleTextRecord {
        val id = record.id.ifBlank { firestore.collection(COLLECTION).document().id }
        val withId = record.copy(id = id)
        offline.save(COLLECTION, id, withId)
        return withId
    }

    suspend fun delete(recordId: String) = offline.delete(COLLECTION, recordId)

    /** Bug fix: firestore.rules' own `bibleTextRecords` match block already
     * required `resource.data.publisherPersonId == personIdFromToken()` —
     * this query never actually matched that (it fetched the *whole*
     * collection, every Publisher's records), which means Firestore could
     * never prove the unconstrained query only returns documents the rule
     * allows and rejected the entire listener with PERMISSION_DENIED. Not a
     * one-device problem — this affected every Publisher, on every device,
     * every time. Scoping the query itself to just this Publisher's own
     * records is what actually makes the existing rule satisfiable. */
    fun startRemoteSync(publisherPersonId: String): Flow<Unit> =
        mirrorFirestoreCollection(
            firestore, offline, appScope, COLLECTION, BibleTextRecord::class.java,
            query = firestore.collection(COLLECTION).whereEqualTo("publisherPersonId", publisherPersonId),
        ) { it.id }

    suspend fun pullOnce(publisherPersonId: String) = pullFirestoreCollectionOnce(
        firestore, offline, COLLECTION, BibleTextRecord::class.java,
        query = firestore.collection(COLLECTION).whereEqualTo("publisherPersonId", publisherPersonId),
    ) { it.id }
}
