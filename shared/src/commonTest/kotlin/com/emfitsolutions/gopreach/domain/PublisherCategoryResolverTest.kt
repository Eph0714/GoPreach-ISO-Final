package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.CATEGORY_SOURCE_ADMIN_CONFIRMED
import com.emfitsolutions.gopreach.data.model.CATEGORY_SOURCE_BASELINE
import com.emfitsolutions.gopreach.data.model.CATEGORY_SOURCE_CHANGED
import com.emfitsolutions.gopreach.data.model.CategorySource
import com.emfitsolutions.gopreach.data.model.MonthlyReport
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.PublisherCategoryChange
import com.emfitsolutions.gopreach.data.model.ReportStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublisherCategoryResolverTest {
    private val jul = 7_000L
    private val aug = 8_000L
    private val sep = 9_000L
    private val oct = 10_000L

    private fun change(category: PublisherCategory, from: Long, through: Long? = null, source: String = CATEGORY_SOURCE_CHANGED, at: Long = from) =
        PublisherCategoryChange(publisherPersonId = "p", category = category, effectiveFrom = from, effectiveThrough = through, source = source, recordedAt = at)

    private fun report(month: Long, category: PublisherCategory, status: ReportStatus = ReportStatus.SUBMITTED) =
        MonthlyReport(publisherPersonId = "p", periodMonth = month, category = category, status = status, submittedAt = month)

    @Test
    fun eachMonthFollowsTheCategoryOfThatMonth() {
        val history = listOf(
            change(PublisherCategory.REGULAR_PUBLISHER, 0, source = CATEGORY_SOURCE_BASELINE),
            change(PublisherCategory.AUXILIARY_PIONEER, aug),
            change(PublisherCategory.REGULAR_PIONEER, sep),
        )
        assertEquals(PublisherCategory.REGULAR_PUBLISHER, PublisherCategoryResolver.resolve("p", jul, emptyList(), history).category)
        assertEquals(PublisherCategory.AUXILIARY_PIONEER, PublisherCategoryResolver.resolve("p", aug, emptyList(), history).category)
        assertEquals(PublisherCategory.REGULAR_PIONEER, PublisherCategoryResolver.resolve("p", sep, emptyList(), history).category)
    }

    @Test
    fun aLaterChangeNeverRewritesAnEarlierMonth() {
        val before = listOf(change(PublisherCategory.REGULAR_PUBLISHER, 0, source = CATEGORY_SOURCE_BASELINE))
        val after = before + change(PublisherCategory.REGULAR_PIONEER, oct)
        assertEquals(
            PublisherCategoryResolver.resolve("p", aug, emptyList(), before),
            PublisherCategoryResolver.resolve("p", aug, emptyList(), after),
        )
    }

    @Test
    fun theMonthsOwnSubmittedReportWinsOverHistory() {
        val history = listOf(change(PublisherCategory.REGULAR_PUBLISHER, 0, source = CATEGORY_SOURCE_BASELINE))
        val resolved = PublisherCategoryResolver.resolve("p", aug, listOf(report(aug, PublisherCategory.AUXILIARY_PIONEER)), history)
        assertEquals(PublisherCategory.AUXILIARY_PIONEER, resolved.category)
        assertEquals(CategorySource.MONTH_REPORT, resolved.source)
    }

    @Test
    fun aDraftReportIsNotEvidenceOfTheCategory() {
        val resolved = PublisherCategoryResolver.resolve("p", aug, listOf(report(aug, PublisherCategory.AUXILIARY_PIONEER, ReportStatus.DRAFT)), emptyList())
        assertNull(resolved.category)
        assertTrue(resolved.needsConfirmation)
    }

    @Test
    fun withNothingOnRecordTheCurrentProfileIsNotGuessed() {
        val resolved = PublisherCategoryResolver.resolve("p", aug, emptyList(), emptyList())
        assertNull(resolved.category)
        assertEquals(CategorySource.UNCONFIRMED, resolved.source)
    }

    @Test
    fun aMonthBeforeTheFirstEntryNeedsConfirmation() {
        val history = listOf(change(PublisherCategory.REGULAR_PUBLISHER, aug))
        assertTrue(PublisherCategoryResolver.resolve("p", jul, emptyList(), history).needsConfirmation)
    }

    @Test
    fun anAdminConfirmationCoversExactlyThatMonth() {
        val history = listOf(change(PublisherCategory.AUXILIARY_PIONEER, aug, through = aug, source = CATEGORY_SOURCE_ADMIN_CONFIRMED))
        val resolved = PublisherCategoryResolver.resolve("p", aug, emptyList(), history)
        assertEquals(PublisherCategory.AUXILIARY_PIONEER, resolved.category)
        assertEquals(CategorySource.ADMIN_CONFIRMED, resolved.source)
        assertTrue(PublisherCategoryResolver.resolve("p", sep, emptyList(), history).needsConfirmation)
    }

    @Test
    fun anotherPublishersHistoryIsIgnored() {
        val history = listOf(change(PublisherCategory.REGULAR_PIONEER, 0).copy(publisherPersonId = "someoneElse"))
        assertTrue(PublisherCategoryResolver.resolve("p", aug, emptyList(), history).needsConfirmation)
    }
}
