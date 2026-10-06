package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.BibleTextCategory
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val COLLECTION = "bibleTextCategories"

/** "My Bible Text Record" module spec §10-§11 — a Publisher's own personal
 * categories ("God's Promise", "Ministry", ...). Client-side scoping here
 * ([observeForPublisher]) is a convenience filter, not the security
 * boundary — the actual "Publisher A can't touch Publisher B's categories"
 * enforcement is server-side, in firestore.rules' own
 * `bibleTextCategories` match block (spec §20: never trust a
 * publisherPersonId supplied by the frontend). */
class BibleTextCategoryRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    fun observeForPublisher(publisherPersonId: String): Flow<List<BibleTextCategory>> =
        offline.observeCollection<BibleTextCategory>(COLLECTION)
            .map { list -> list.filter { it.publisherPersonId == publisherPersonId }.sortedBy { it.name } }

    suspend fun save(category: BibleTextCategory): BibleTextCategory {
        val id = category.id.ifBlank { remote.newId(COLLECTION) }
        val withId = category.copy(id = id)
        offline.save(COLLECTION, id, withId)
        return withId
    }

    suspend fun delete(categoryId: String) = offline.delete(COLLECTION, categoryId)

    /** Same fix as [BibleTextRecordRepository.startRemoteSync] — the query
     * now actually matches what firestore.rules already required. */
    fun startRemoteSync(publisherPersonId: String): Flow<Unit> =
        remote.mirror(COLLECTION, BibleTextCategory::class, equalTo = "publisherPersonId" to publisherPersonId) { it.id }

    suspend fun pullOnce(publisherPersonId: String) = remote.pullOnce(COLLECTION, BibleTextCategory::class, equalTo = "publisherPersonId" to publisherPersonId) { it.id }
}
