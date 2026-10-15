package com.emfitsolutions.gopreach.data.model

import com.emfitsolutions.gopreach.platform.DocumentId

const val CATEGORY_SOURCE_ENROLLED = "ENROLLED"
const val CATEGORY_SOURCE_CHANGED = "CHANGED"
/** The category a publisher held before the first change that was ever recorded for them (effective from the beginning of time). */
const val CATEGORY_SOURCE_BASELINE = "BASELINE"
/** An Admin confirmed the category for one specific month while preparing a report. */
const val CATEGORY_SOURCE_ADMIN_CONFIRMED = "ADMIN_CONFIRMED"

/**
 * One entry of a publisher's service-category history (`publisherCategoryHistory/{id}`). Entries are only ever added, never
 * edited or deleted, so the history stays a faithful record: the category that applies to a service month is found from these
 * entries (see [com.emfitsolutions.gopreach.domain.PublisherCategoryResolver]) and never from the publisher's current profile.
 *
 *  - An open-ended entry ([effectiveThrough] = null) applies from [effectiveFrom] until a later entry takes over.
 *  - An Admin-confirmed entry covers exactly one month ([effectiveFrom] = [effectiveThrough]).
 *
 * [effectiveFrom] and [effectiveThrough] are first-of-month epoch millis; a [CATEGORY_SOURCE_BASELINE] entry uses 0.
 */
@kotlinx.serialization.Serializable
data class PublisherCategoryChange(
    @field:DocumentId val id: String = "",
    val publisherPersonId: String = "",
    val congregationId: String = "",
    val category: PublisherCategory = PublisherCategory.REGULAR_PUBLISHER,
    val effectiveFrom: Long = 0L,
    val effectiveThrough: Long? = null,
    val source: String = CATEGORY_SOURCE_CHANGED,
    val recordedByPersonId: String = "",
    val recordedAt: Long = 0L,
    val note: String? = null,
)

/** How the category used for one publisher in one month's report snapshot was determined. */
enum class CategorySource(val label: String) {
    /** The publisher's own Monthly Report for that month states the category it was made under. */
    MONTH_REPORT("Own monthly report"),
    /** Found in the recorded category history. */
    HISTORY("Category history"),
    /** An Admin confirmed it for that month. */
    ADMIN_CONFIRMED("Confirmed by admin"),
    /** Nothing on record: the Admin must confirm it (it is never guessed from the current profile). */
    UNCONFIRMED("Needs confirmation"),
}
