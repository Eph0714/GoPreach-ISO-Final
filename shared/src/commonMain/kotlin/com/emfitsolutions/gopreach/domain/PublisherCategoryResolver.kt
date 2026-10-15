package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.CATEGORY_SOURCE_ADMIN_CONFIRMED
import com.emfitsolutions.gopreach.data.model.CategorySource
import com.emfitsolutions.gopreach.data.model.MonthlyReport
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.PublisherCategoryChange

/** The service category that applies to one publisher in one service month, and where that answer came from. */
data class ResolvedCategory(val category: PublisherCategory?, val source: CategorySource) {
    val needsConfirmation: Boolean get() = category == null
}

/**
 * Finds the category a publisher had IN a given service month, so a report for that month follows that month's rules and a
 * later change of category never rewrites an earlier month. In order of trust:
 *
 *  1. The publisher's own submitted Monthly Report for that month (it records the category it was made under).
 *  2. An Admin's confirmation for that exact month.
 *  3. The category history: the open-ended entry with the latest effective date that is not after the month.
 *  4. Otherwise nothing: [CategorySource.UNCONFIRMED] - the current profile is deliberately NOT used.
 */
object PublisherCategoryResolver {
    fun resolve(
        publisherPersonId: String,
        periodMonth: Long,
        reports: List<MonthlyReport>,
        history: List<PublisherCategoryChange>,
    ): ResolvedCategory {
        reports.filter { it.publisherPersonId == publisherPersonId && it.periodMonth == periodMonth && it.isSubmittedOrPosted }
            .maxByOrNull { it.submittedAt ?: it.lastEditedAt ?: 0L }
            ?.let { return ResolvedCategory(it.category, CategorySource.MONTH_REPORT) }

        val own = history.filter { it.publisherPersonId == publisherPersonId }
        own.filter { it.effectiveThrough == periodMonth && it.effectiveFrom == periodMonth }
            .maxByOrNull { it.recordedAt }
            ?.let { return ResolvedCategory(it.category, CategorySource.ADMIN_CONFIRMED) }

        own.filter { it.effectiveThrough == null && it.effectiveFrom <= periodMonth }
            .maxWithOrNull(compareBy<PublisherCategoryChange>({ it.effectiveFrom }, { it.recordedAt }))
            ?.let { return ResolvedCategory(it.category, if (it.source == CATEGORY_SOURCE_ADMIN_CONFIRMED) CategorySource.ADMIN_CONFIRMED else CategorySource.HISTORY) }

        return ResolvedCategory(null, CategorySource.UNCONFIRMED)
    }
}
