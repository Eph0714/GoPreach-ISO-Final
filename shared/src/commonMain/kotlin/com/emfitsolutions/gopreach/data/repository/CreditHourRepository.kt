package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.CreditHourCategory
import com.emfitsolutions.gopreach.data.model.CreditHourRecord
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.platform.nowMillis
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val CATEGORIES_COLLECTION = "creditHourCategories"
private const val RECORDS_COLLECTION = "creditHourRecords"

/** Credit Hour Categories lookup table — a managed list (CRUD from the
 * Credit Hour Categories screen), never a hard-coded enum. The starting
 * list below is only ever *written into* the collection once; after that
 * every name/status is whatever an admin made it. */
class CreditHourCategoryRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    fun observeActive(): Flow<List<CreditHourCategory>> =
        observeAll().map { list -> list.filter { it.active } }

    fun observeAll(): Flow<List<CreditHourCategory>> =
        offline.observeCollection<CreditHourCategory>(CATEGORIES_COLLECTION).map { list -> list.sortedBy { it.name.lowercase() } }

    suspend fun save(category: CreditHourCategory): CreditHourCategory {
        val id = category.id.ifBlank { remote.newId(CATEGORIES_COLLECTION) }
        val now = nowMillis()
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
        remote.countWhere(RECORDS_COLLECTION, "categoryId", categoryId)

    fun startRemoteSync(): Flow<Unit> =
        remote.mirror(CATEGORIES_COLLECTION, CreditHourCategory::class) { it.id }

    suspend fun pullOnce() = remote.pullOnce(CATEGORIES_COLLECTION, CreditHourCategory::class) { it.id }

    private val seedLock = Mutex()
    private var seedChecked = false

    /** Writes [DEFAULT_CATEGORIES] once, only if the server confirms the
     * collection is genuinely empty. Fixed document ids make this safe to
     * race: two devices seeding at the same moment write the same documents,
     * never duplicates. Silently skipped when offline (retried next time). */
    suspend fun ensureDefaultCategories() = seedLock.withLock {
        if (seedChecked) return@withLock
        runCatching {
            if (!remote.hasAny(CATEGORIES_COLLECTION)) {
                val now = nowMillis()
                DEFAULT_CATEGORIES.forEach { (id, name) ->
                    remote.pushNow(CATEGORIES_COLLECTION, id, CreditHourCategory(name = name, active = true, createdAt = now, updatedAt = now))
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
class CreditHourRecordRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    fun observeForPublisher(publisherPersonId: String): Flow<List<CreditHourRecord>> =
        observeAll().map { list -> list.filter { it.publisherPersonId == publisherPersonId } }

    fun observeForPlannerDay(plannerDayId: String): Flow<List<CreditHourRecord>> =
        observeAll().map { list -> list.filter { it.plannerDayId == plannerDayId } }

    fun observeAll(): Flow<List<CreditHourRecord>> = offline.observeCollection(RECORDS_COLLECTION)

    suspend fun save(record: CreditHourRecord): CreditHourRecord {
        val id = record.id.ifBlank { remote.newId(RECORDS_COLLECTION) }
        val withId = record.copy(id = id)
        offline.save(RECORDS_COLLECTION, id, withId)
        return withId
    }

    suspend fun delete(recordId: String) = offline.delete(RECORDS_COLLECTION, recordId)

    fun startRemoteSync(publisherPersonId: String): Flow<Unit> =
        remote.mirror(RECORDS_COLLECTION, CreditHourRecord::class, equalTo = "publisherPersonId" to publisherPersonId) { it.id }

    suspend fun pullOnce(publisherPersonId: String) = remote.pullOnce(RECORDS_COLLECTION, CreditHourRecord::class, equalTo = "publisherPersonId" to publisherPersonId) { it.id }
}
