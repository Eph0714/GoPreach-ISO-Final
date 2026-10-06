package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.BibleTextCategory
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

private const val COLLECTION = "bibleTextCategories"

/** "My Bible Text Record" module spec §10-§11 — a Publisher's own personal
 * categories ("God's Promise", "Ministry", ...). Client-side scoping here
 * ([observeForPublisher]) is a convenience filter, not the security
 * boundary — the actual "Publisher A can't touch Publisher B's categories"
 * enforcement is server-side, in firestore.rules' own
 * `bibleTextCategories` match block (spec §20: never trust a
 * publisherPersonId supplied by the frontend). */
@Singleton
class BibleTextCategoryRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    fun observeForPublisher(publisherPersonId: String): Flow<List<BibleTextCategory>> =
        offline.observeCollection<BibleTextCategory>(COLLECTION)
            .map { list -> list.filter { it.publisherPersonId == publisherPersonId }.sortedBy { it.name } }

    suspend fun save(category: BibleTextCategory): BibleTextCategory {
        val id = category.id.ifBlank { firestore.collection(COLLECTION).document().id }
        val withId = category.copy(id = id)
        offline.save(COLLECTION, id, withId)
        return withId
    }

    suspend fun delete(categoryId: String) = offline.delete(COLLECTION, categoryId)

    /** Same fix as [BibleTextRecordRepository.startRemoteSync] — the query
     * now actually matches what firestore.rules already required. */
    fun startRemoteSync(publisherPersonId: String): Flow<Unit> =
        mirrorFirestoreCollection(
            firestore, offline, appScope, COLLECTION, BibleTextCategory::class.java,
            query = firestore.collection(COLLECTION).whereEqualTo("publisherPersonId", publisherPersonId),
        ) { it.id }

    suspend fun pullOnce(publisherPersonId: String) = pullFirestoreCollectionOnce(
        firestore, offline, COLLECTION, BibleTextCategory::class.java,
        query = firestore.collection(COLLECTION).whereEqualTo("publisherPersonId", publisherPersonId),
    ) { it.id }
}
