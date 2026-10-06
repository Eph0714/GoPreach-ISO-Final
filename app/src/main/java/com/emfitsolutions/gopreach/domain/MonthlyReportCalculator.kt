package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.PreachingTimeRecord
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.Visit
import java.util.Calendar

/** First-of-month..first-of-next-month `[start, end)` millis for the month
 * containing [periodMonthStart] (itself already a first-of-month epoch, same
 * shape as [com.emfitsolutions.gopreach.data.model.MonthlyReport.periodMonth]).
 * Exclusive upper bound rather than "23:59:59.999" avoids any leap-
 * second/millisecond-rounding edge case at the exact month boundary — spec
 * §1/§4/§11's "handle 28/29/30/31-day months correctly" is exactly what
 * [Calendar.MONTH] arithmetic (not a hand-rolled day count) already does for
 * free. */
data class MonthBounds(val startInclusive: Long, val endExclusive: Long) : TimeBounds {
    override fun contains(millis: Long): Boolean = millis >= startInclusive && millis < endExclusive

    companion object {
        fun of(periodMonthStart: Long): MonthBounds {
            val start = (Calendar.getInstance().clone() as Calendar).apply {
                timeInMillis = periodMonthStart
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
            return MonthBounds(start.timeInMillis, end.timeInMillis)
        }
    }
}

/** Result of [MonthlyReportCalculator.calculate] — every value this app can
 * actually derive automatically for one Publisher/congregation/month, plus
 * whether the source data needed to compute it was even available (spec §24:
 * "distinguish no records found = 0 from data retrieval failed = error" —
 * [dataAvailable] is what a caller checks before trusting a `0`/`false`). */
data class MonthlyReportCalculation(
    val bibleStudiesConducted: Int,
    /** My Planner / Reporting upgrade spec §30 — auto-filled the same way
     * [bibleStudiesConducted] already was, via [MinistryStatisticsService
     * .getMonthlyUniqueReturnVisits]; feeds [com.emfitsolutions.gopreach
     * .data.model.MonthlyReport.returnVisitsCount]. */
    val returnVisitsConducted: Int,
    val participatedInPreaching: Boolean,
    /** Pioneer categories only — `null` for every other [PublisherCategory],
     * same as [com.emfitsolutions.gopreach.data.model.MonthlyReport
     * .systemCalculatedHours]. */
    val systemCalculatedHours: Double?,
    val dataAvailable: Boolean,
)

/**
 * "Update Monthly Report Submission — Automatic Bible Study Count and
 * Preaching Participation" — the one place this calculation happens, shared
 * by [com.emfitsolutions.gopreach.ui.screens.monthlyreport
 * .MonthlyReportViewModel] (the Publisher's own submission) so the exact same
 * rule always produces the exact same number, never two slightly-different
 * copies of this logic drifting apart.
 *
 * Every list parameter is expected already scoped to one Publisher (and,
 * transitively through [InterestedPerson.congregationId]/
 * [PreachingTimeRecord.congregationId], their own congregation) — this
 * function does no ownership filtering of its own (spec §5/§6's ownership
 * and congregation checks happen once, at the call site, the same way every
 * other per-publisher aggregation in this app already scopes its inputs
 * before handing them to a pure calculation).
 */
object MonthlyReportCalculator {

    /**
     * Spec §18 — "at least one qualifying preaching record exists during the
     * selected month." Mirrors the exact proxy
     * [com.emfitsolutions.gopreach.data.sync.ReminderWorker] already
     * established for "did this Publisher preach this month" before this
     * task existed: a Pioneer's own [PreachingTimeRecord] hours (any active
     * entry with hours > 0), a Non-Pioneer's own [Visit] activity at any
     * pipeline stage (any visit at all, regardless of outcome — attempting
     * the visit is participating in the ministry whether or not the person
     * was home). [allVisitsForPublisher] is every Visit the Publisher owns
     * across every stage, not just [PipelineStage.BIBLE_STUDY] — participation
     * isn't limited to Bible Study activity the way [countBibleStudiesConducted]
     * is.
     */
    fun didParticipateInPreaching(
        isPioneer: Boolean,
        allVisitsForPublisher: List<Visit>,
        preachingTimeRecords: List<PreachingTimeRecord>,
        bounds: MonthBounds,
    ): Boolean = if (isPioneer) {
        preachingTimeRecords.any { it.status == RecordStatus.ACTIVE && it.date in bounds && it.hoursConsumed > 0.0 }
    } else {
        allVisitsForPublisher.any { it.visitDate in bounds }
    }

    /** Spec §9/§11/§19 — "My Total Hours," scoped to [bounds], reusing the
     * exact same fields/qualification ([RecordStatus.ACTIVE] only) [com
     * .emfitsolutions.gopreach.ui.screens.preachingtime.PreachingTimeRecordScreen]'s
     * own total already uses. Pioneer-only; callers never call this for a
     * Non-Pioneer (see [calculate]). */
    fun sumPreachingHours(preachingTimeRecords: List<PreachingTimeRecord>, bounds: MonthBounds): Double =
        preachingTimeRecords.filter { it.status == RecordStatus.ACTIVE && it.date in bounds }.sumOf { it.hoursConsumed }

    /** "Add Special Pioneer publisher status category" — Special Pioneer must
     * receive exactly [PublisherCategory.REGULAR_PIONEER]'s treatment
     * wherever pioneer eligibility is checked; since this function already
     * groups Regular and Auxiliary Pioneer together, adding Special Pioneer
     * here achieves that without changing Auxiliary Pioneer's own existing
     * behavior at all (same reasoning the feature's own spec gives for a
     * "Regular Pioneer OR Auxiliary Pioneer" check: "update it to Regular
     * Pioneer OR Special Pioneer OR Auxiliary Pioneer"). This is the one
     * central "is this Publisher a Pioneer for report-calculation purposes"
     * check [calculate] itself uses, so Bible-Study-count eligibility,
     * preaching-participation eligibility, and My-Total-Hours-sourced
     * `systemCalculatedHours` all inherit this automatically. */
    fun isPioneerCategory(category: PublisherCategory?): Boolean =
        category == PublisherCategory.REGULAR_PIONEER ||
            category == PublisherCategory.SPECIAL_PIONEER ||
            category == PublisherCategory.AUXILIARY_PIONEER

    /** Convenience wrapper over the three functions above — what
     * [com.emfitsolutions.gopreach.ui.screens.monthlyreport.MonthlyReportViewModel]
     * actually calls once per (Publisher, congregation, month) combination.
     * [dataAvailable] is always `true` here since every input is already a
     * plain, already-loaded list (this app's offline-first Room mirror never
     * itself distinguishes "empty" from "failed to load" — see this file's
     * own doc comment on [MonthlyReportCalculation.dataAvailable] for where a
     * caller would set it `false` instead, e.g. if the underlying Flow threw). */
    fun calculate(
        publisherPersonId: String,
        category: PublisherCategory?,
        /** All of this Publisher's own pipeline people, already congregation-
         * scoped (spec §5/§6) — not just Bible Study stage; [MinistryStatisticsService]
         * does its own stage filtering for both Bible Studies and Return Visits. */
        ownPeople: List<InterestedPerson>,
        allVisitsForPublisher: List<Visit>,
        preachingTimeRecords: List<PreachingTimeRecord>,
        periodMonthStart: Long,
    ): MonthlyReportCalculation {
        val bounds = MonthBounds.of(periodMonthStart)
        val isPioneer = isPioneerCategory(category)
        return MonthlyReportCalculation(
            // Centralized in MinistryStatisticsService (spec §15) — this used
            // to be this class's own bespoke countBibleStudiesConducted();
            // same definition, one shared implementation now.
            bibleStudiesConducted = MinistryStatisticsService.getMonthlyUniqueBibleStudies(
                publisherPersonId, ownPeople, allVisitsForPublisher, periodMonthStart,
            ),
            returnVisitsConducted = MinistryStatisticsService.getMonthlyUniqueReturnVisits(
                publisherPersonId, ownPeople, allVisitsForPublisher, periodMonthStart,
            ),
            participatedInPreaching = didParticipateInPreaching(isPioneer, allVisitsForPublisher, preachingTimeRecords, bounds),
            systemCalculatedHours = if (isPioneer) sumPreachingHours(preachingTimeRecords, bounds) else null,
            dataAvailable = true,
        )
    }
}
