package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.CreditHourCategory
import com.emfitsolutions.gopreach.data.model.CreditHourRecord
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.mirrorFirestoreCollection
import com.emfitsolutions.gopreach.data.sync.pullFirestoreCollectionOnce
import com.emfitsolutions.gopreach.di.ApplicationScope
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val CATEGORIES_COLLECTION = "creditHourCategories"
private const val RECORDS_COLLECTION = "creditHourRecords"

/** Credit Hour Categories lookup table — a managed list (CRUD from the
 * Credit Hour Categories screen), never a hard-coded enum. The starting
 * list below is only ever *written into* the collection once; after that
 * every name/status is whatever an admin made it. */
@Singleton
class CreditHourCategoryRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    fun observeActive(): Flow<List<CreditHourCategory>> =
        observeAll().map { list -> list.filter { it.active } }

    fun observeAll(): Flow<List<CreditHourCategory>> =
        offline.observeCollection<CreditHourCategory>(CATEGORIES_COLLECTION).map { list -> list.sortedBy { it.name.lowercase() } }

    suspend fun save(category: CreditHourCategory): CreditHourCategory {
        val id = category.id.ifBlank { firestore.collection(CATEGORIES_COLLECTION).document().id }
        val now = System.currentTimeMillis()
        val withId = category.copy(id = id, createdAt = category.createdAt.takeIf { it > 0L } ?: now, updatedAt = now)
        offline.save(CATEGORIES_COLLECTION, id, withId)
        return withId
    }

    suspend fun delete(categoryId: String) = offline.delete(CATEGORIES_COLLECTION, categoryId)

    /** How many Credit Hour entries (any Publisher's) reference [categoryId],
     * asked of the server itself — this device's cache only ever holds its
     * own user's entries, so it can't answer this. Throws when the server
     * can't be reached; callers must treat that as "possibly in use". */
    suspend fun countRecordsUsing(categoryId: String): Int =
        firestore.collection(RECORDS_COLLECTION)
            .whereEqualTo("categoryId", categoryId)
            .limit(1)
            .get(Source.SERVER)
            .await()
            .size()

    fun startRemoteSync(): Flow<Unit> =
        mirrorFirestoreCollection(firestore, offline, appScope, CATEGORIES_COLLECTION, CreditHourCategory::class.java) { it.id }

    suspend fun pullOnce() = pullFirestoreCollectionOnce(firestore, offline, CATEGORIES_COLLECTION, CreditHourCategory::class.java) { it.id }

    private val seedLock = Mutex()
    private var seedChecked = false

    /** Writes [DEFAULT_CATEGORIES] once, only if the server confirms the
     * collection is genuinely empty. Fixed document ids make this safe to
     * race: two devices seeding at the same moment write the same documents,
     * never duplicates. Silently skipped when offline (retried next time). */
    suspend fun ensureDefaultCategories() = seedLock.withLock {
        if (seedChecked) return@withLock
        runCatching {
            val existing = firestore.collection(CATEGORIES_COLLECTION).limit(1).get(Source.SERVER).await()
            if (existing.isEmpty) {
                val now = System.currentTimeMillis()
                DEFAULT_CATEGORIES.forEach { (id, name) ->
                    firestore.collection(CATEGORIES_COLLECTION).document(id)
                        .set(CreditHourCategory(name = name, active = true, createdAt = now, updatedAt = now))
                        .await()
                }
            }
            seedChecked = true
        }
    }

    companion object {
        /** The starting Credit Hour list, in display order, with fixed ids. */
        val DEFAULT_CATEGORIES: List<Pair<String, String>> = listOf(
            "default_ldc" to "LDC",
            "default_hcl" to "HCL",
            "default_bethel" to "Bethel",
            "default_disaster_relief" to "Disaster Relief",
            "default_prison_witnessing" to "Prison Witnessing",
            "default_pioneer_service_school" to "Pioneer Service School",
            "default_elder_school" to "Elder School",
            "default_other_school" to "Other School",
            "default_assembly" to "Assembly",
        )
    }
}

/** One or more Credit Hour entries per Publisher per day, each linked to its
 * category by [CreditHourRecord.categoryId] (never the category's display
 * text), so renaming or deactivating a category never breaks history. */
@Singleton
class CreditHourRecordRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    fun observeForPublisher(publisherPersonId: String): Flow<List<CreditHourRecord>> =
        observeAll().map { list -> list.filter { it.publisherPersonId == publisherPersonId } }

    fun observeForPlannerDay(plannerDayId: String): Flow<List<CreditHourRecord>> =
        observeAll().map { list -> list.filter { it.plannerDayId == plannerDayId } }

    fun observeAll(): Flow<List<CreditHourRecord>> = offline.observeCollection(RECORDS_COLLECTION)

    suspend fun save(record: CreditHourRecord): CreditHourRecord {
        val id = record.id.ifBlank { firestore.collection(RECORDS_COLLECTION).document().id }
        val withId = record.copy(id = id)
        offline.save(RECORDS_COLLECTION, id, withId)
        return withId
    }

    suspend fun delete(recordId: String) = offline.delete(RECORDS_COLLECTION, recordId)

    fun startRemoteSync(publisherPersonId: String): Flow<Unit> =
        mirrorFirestoreCollection(
            firestore, offline, appScope, RECORDS_COLLECTION, CreditHourRecord::class.java,
            query = firestore.collection(RECORDS_COLLECTION).whereEqualTo("publisherPersonId", publisherPersonId),
        ) { it.id }

    suspend fun pullOnce(publisherPersonId: String) = pullFirestoreCollectionOnce(
        firestore, offline, RECORDS_COLLECTION, CreditHourRecord::class.java,
        query = firestore.collection(RECORDS_COLLECTION).whereEqualTo("publisherPersonId", publisherPersonId),
    ) { it.id }
}
