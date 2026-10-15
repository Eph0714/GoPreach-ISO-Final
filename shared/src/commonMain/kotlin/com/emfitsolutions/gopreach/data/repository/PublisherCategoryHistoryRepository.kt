package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.CATEGORY_SOURCE_ADMIN_CONFIRMED
import com.emfitsolutions.gopreach.data.model.CATEGORY_SOURCE_BASELINE
import com.emfitsolutions.gopreach.data.model.CATEGORY_SOURCE_CHANGED
import com.emfitsolutions.gopreach.data.model.CATEGORY_SOURCE_ENROLLED
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.PublisherCategoryChange
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import com.emfitsolutions.gopreach.domain.MonthBounds
import com.emfitsolutions.gopreach.platform.nowMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

private const val COLLECTION = "publisherCategoryHistory"

/**
 * The append-only history of every publisher's service category, so a report for a past month can be prepared with the category
 * the publisher actually had in that month (see [com.emfitsolutions.gopreach.domain.PublisherCategoryResolver]). Entries are
 * written automatically whenever a publisher's category is enrolled or changed, and by an Admin confirming a month's category.
 * Nothing here is ever edited or deleted (the server refuses both).
 */
class PublisherCategoryHistoryRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    fun observeAll(): Flow<List<PublisherCategoryChange>> = offline.observeCollection(COLLECTION)

    private suspend fun add(entry: PublisherCategoryChange) {
        val id = entry.id.ifBlank { remote.newId(COLLECTION) }
        offline.save(COLLECTION, id, entry.copy(id = id))
    }

    /**
     * Called whenever a role assignment is saved: records a publisher's enrollment, or the change from [before]'s category to
     * [after]'s, effective from the first of the current month. The first change ever recorded for a person also stores the
     * category they held until then as a baseline, so the months before the change keep their category. Never throws - a
     * history problem must not block saving the assignment itself.
     */
    suspend fun recordAssignmentSave(before: RoleAssignment?, after: RoleAssignment) {
        runCatching {
            val newCategory = (after.resolvedRoleTypeOrNull() as? RoleType.Publisher)?.category ?: return
            val oldCategory = (before?.resolvedRoleTypeOrNull() as? RoleType.Publisher)?.category
            val congregationId = after.congregationId ?: before?.congregationId ?: return
            val existing = observeAll().first().filter { it.publisherPersonId == after.personId }
            val latest = existing.filter { it.effectiveThrough == null }.maxWithOrNull(compareBy({ it.effectiveFrom }, { it.recordedAt }))
            if (latest != null && latest.category == newCategory) return
            val now = nowMillis()
            val thisMonth = MonthBounds.of(now).startInclusive
            val by = after.lastEditedByPersonId ?: after.assignedByPersonId
            if (oldCategory == null) {
                add(PublisherCategoryChange(publisherPersonId = after.personId, congregationId = congregationId, category = newCategory, effectiveFrom = thisMonth, source = CATEGORY_SOURCE_ENROLLED, recordedByPersonId = by, recordedAt = now))
                return
            }
            if (oldCategory == newCategory) return
            if (existing.isEmpty()) {
                add(PublisherCategoryChange(publisherPersonId = after.personId, congregationId = congregationId, category = oldCategory, effectiveFrom = 0L, source = CATEGORY_SOURCE_BASELINE, recordedByPersonId = by, recordedAt = now - 1))
            }
            add(PublisherCategoryChange(publisherPersonId = after.personId, congregationId = congregationId, category = newCategory, effectiveFrom = thisMonth, source = CATEGORY_SOURCE_CHANGED, recordedByPersonId = by, recordedAt = now))
        }
    }

    /** An Admin confirmed [category] as the one [publisherPersonId] had in [periodMonth] (covers only that month). */
    suspend fun confirm(publisherPersonId: String, congregationId: String, periodMonth: Long, category: PublisherCategory, confirmedByPersonId: String, note: String? = null) {
        add(
            PublisherCategoryChange(
                publisherPersonId = publisherPersonId, congregationId = congregationId, category = category,
                effectiveFrom = periodMonth, effectiveThrough = periodMonth, source = CATEGORY_SOURCE_ADMIN_CONFIRMED,
                recordedByPersonId = confirmedByPersonId, recordedAt = nowMillis(), note = note,
            ),
        )
    }
}
