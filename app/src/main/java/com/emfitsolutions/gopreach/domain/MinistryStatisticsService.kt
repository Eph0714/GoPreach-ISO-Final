package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.Visit
import java.util.Calendar

/** A `[start, end)` millis window one of [DayBounds]/[MonthBounds]/[YearBounds]
 * resolves to — lets [MinistryStatisticsService]'s counting logic be written
 * once and shared across all three reporting periods instead of three
 * near-identical copies. [MonthBounds] (in [MonthlyReportCalculator]) already
 * existed before this file and now implements this too, unchanged otherwise. */
interface TimeBounds {
    operator fun contains(millis: Long): Boolean
}

/** `[start, end)` for the single day containing [dayStart] — same shape as
 * [MonthBounds], one level down. */
data class DayBounds(val startInclusive: Long, val endExclusive: Long) : TimeBounds {
    override fun contains(millis: Long): Boolean = millis >= startInclusive && millis < endExclusive

    companion object {
        fun of(dayStart: Long): DayBounds {
            val start = (Calendar.getInstance().clone() as Calendar).apply {
                timeInMillis = dayStart
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
            return DayBounds(start.timeInMillis, end.timeInMillis)
        }
    }
}

/** `[start, end)` Monday..Sunday for the week containing [startInclusive] —
 * same shape as [DayBounds]/[MonthBounds]/[YearBounds], one level between
 * [DayBounds] and [MonthBounds]. Same Monday-start rule as
 * [com.emfitsolutions.gopreach.ui.components.DateRange.thisWeek], just an
 * exclusive-end `[start, end)` window instead of an inclusive end-of-day one
 * — matches every other bounds type in this file. */
data class WeekBounds(val startInclusive: Long, val endExclusive: Long) : TimeBounds {
    override fun contains(millis: Long): Boolean = millis >= startInclusive && millis < endExclusive

    companion object {
        fun of(anyMillisInWeek: Long): WeekBounds {
            val start = (Calendar.getInstance().clone() as Calendar).apply {
                timeInMillis = anyMillisInWeek
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                val daysSinceMonday = ((get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY) + 7) % 7
                add(Calendar.DAY_OF_MONTH, -daysSinceMonday)
            }
            val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 7) }
            return WeekBounds(start.timeInMillis, end.timeInMillis)
        }
    }
}

/** `[start, end)` for the single calendar year containing [yearStart] — same
 * shape as [MonthBounds], one level up. */
data class YearBounds(val startInclusive: Long, val endExclusive: Long) : TimeBounds {
    override fun contains(millis: Long): Boolean = millis >= startInclusive && millis < endExclusive

    companion object {
        fun of(yearStart: Long): YearBounds {
            val start = (Calendar.getInstance().clone() as Calendar).apply {
                timeInMillis = yearStart
                set(Calendar.MONTH, Calendar.JANUARY)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val end = (start.clone() as Calendar).apply { add(Calendar.YEAR, 1) }
            return YearBounds(start.timeInMillis, end.timeInMillis)
        }
    }
}

/**
 * Account/Ministry Statistics spec §1-§15/§51 — "Return Visit and Bible Study
 * statistics are PERSON COUNTS, not VISIT COUNTS," independently recomputed
 * per reporting period (never summed from a smaller one: a Publisher visited
 * five times in a month is still exactly 1, and being 1 in September and 1 in
 * October never becomes 2 for the year — the yearly functions below
 * independently re-scan the whole year, they never add up monthly results).
 *
 * This is the one place every screen that shows a Return Visit/Bible Study
 * number must call through (spec §15) — before this file existed, the exact
 * same "count distinct interestedPersonId" logic was duplicated (correctly,
 * but separately) in [com.emfitsolutions.gopreach.ui.screens.home
 * .PublisherDashboardViewModel] and [com.emfitsolutions.gopreach.ui.screens
 * .reports.ConsolidatedReportViewModel]; both now call these functions
 * instead of re-deriving it themselves, so a future change to the rule only
 * ever needs to happen once.
 *
 * Every function takes already-loaded lists (this app's offline-first Room
 * mirror, never a live network query per call — same calling convention
 * [MonthlyReportCalculator] already uses) and does its own
 * [InterestedPerson.publisherPersonId] filtering internally, matching the
 * spec's own `getDailyUniqueReturnVisits(publisherId, date)`-shaped naming
 * exactly (spec §15) rather than pushing that filter onto every call site.
 */
object MinistryStatisticsService {

    /** Shared by both Return Visit and Bible Study counting — a person in
     * [stage] with at least one [Visit] whose [Visit.visitDate] falls in
     * [bounds], counted once per person no matter how many qualifying visits
     * they have (spec §5-§11's worked examples all key off *visit* dates, not
     * when the person first entered the stage — matches
     * [MonthlyReportCalculator.countBibleStudiesConducted]'s existing
     * definition, which this supersedes/centralizes rather than
     * [com.emfitsolutions.gopreach.ui.screens.home.PublisherDashboardViewModel]'s
     * older Bible Study card, which counted by [InterestedPerson.stageEnteredAt]
     * instead — that dashboard card is updated to call this too, so both
     * numbers agree). [RecordStatus.INACTIVE] (deleted) people are excluded,
     * same as every other qualifying-record rule in this app.
     *
     * Not `private`: also called directly with an arbitrary [TimeBounds]
     * (e.g. [com.emfitsolutions.gopreach.ui.components.DateRange], which
     * implements this same interface) by screens like the Publisher Dashboard
     * that use the app-wide Today/This Week/This Month/This Year/custom
     * selector rather than one of the three fixed Day/Month/Year functions
     * below — same underlying rule either way, just not anchored to a
     * calendar-aligned period. */
    fun uniqueVisitedPersons(
        publisherPersonId: String,
        people: List<InterestedPerson>,
        visits: List<Visit>,
        stage: PipelineStage,
        bounds: TimeBounds,
    ): Int {
        val qualifyingPersonIds = people
            .asSequence()
            .filter { it.publisherPersonId == publisherPersonId && it.pipelineStage == stage && it.status == RecordStatus.ACTIVE }
            .map { it.id }
            .toSet()
        return visits
            .asSequence()
            .filter { it.interestedPersonId in qualifyingPersonIds && bounds.contains(it.visitDate) }
            .map { it.interestedPersonId }
            .toSet()
            .size
    }

    fun getDailyUniqueReturnVisits(publisherPersonId: String, people: List<InterestedPerson>, visits: List<Visit>, date: Long): Int =
        uniqueVisitedPersons(publisherPersonId, people, visits, PipelineStage.RETURN_VISIT, DayBounds.of(date))

    fun getMonthlyUniqueReturnVisits(publisherPersonId: String, people: List<InterestedPerson>, visits: List<Visit>, periodMonthStart: Long): Int =
        uniqueVisitedPersons(publisherPersonId, people, visits, PipelineStage.RETURN_VISIT, MonthBounds.of(periodMonthStart))

    fun getWeeklyUniqueReturnVisits(publisherPersonId: String, people: List<InterestedPerson>, visits: List<Visit>, weekStart: Long): Int =
        uniqueVisitedPersons(publisherPersonId, people, visits, PipelineStage.RETURN_VISIT, WeekBounds.of(weekStart))

    fun getYearlyUniqueReturnVisits(publisherPersonId: String, people: List<InterestedPerson>, visits: List<Visit>, yearStart: Long): Int =
        uniqueVisitedPersons(publisherPersonId, people, visits, PipelineStage.RETURN_VISIT, YearBounds.of(yearStart))

    fun getDailyUniqueBibleStudies(publisherPersonId: String, people: List<InterestedPerson>, visits: List<Visit>, date: Long): Int =
        uniqueVisitedPersons(publisherPersonId, people, visits, PipelineStage.BIBLE_STUDY, DayBounds.of(date))

    fun getMonthlyUniqueBibleStudies(publisherPersonId: String, people: List<InterestedPerson>, visits: List<Visit>, periodMonthStart: Long): Int =
        uniqueVisitedPersons(publisherPersonId, people, visits, PipelineStage.BIBLE_STUDY, MonthBounds.of(periodMonthStart))

    fun getWeeklyUniqueBibleStudies(publisherPersonId: String, people: List<InterestedPerson>, visits: List<Visit>, weekStart: Long): Int =
        uniqueVisitedPersons(publisherPersonId, people, visits, PipelineStage.BIBLE_STUDY, WeekBounds.of(weekStart))

    fun getYearlyUniqueBibleStudies(publisherPersonId: String, people: List<InterestedPerson>, visits: List<Visit>, yearStart: Long): Int =
        uniqueVisitedPersons(publisherPersonId, people, visits, PipelineStage.BIBLE_STUDY, YearBounds.of(yearStart))

    /** Spec §21/§22 — "Return Visit → Edit"/"Bible Study → Edit": one row per
     * unique qualifying person, their *total* recorded visits/activities
     * (not just the ones inside [bounds] — spec's own worked example shows
     * "Visits/Activities: 10" for a person who was visited many times over
     * many months, alongside "Counted: 1" for the period actually being
     * viewed), and [counted] always `1` (spec: "the count is based only on
     * unique Person IDs" — never anything else). The individual visit
     * records themselves are never touched by this — this is a read-only
     * summary over the same data [uniqueVisitedPersons] already counts. */
    data class PersonActivitySummary(
        val interestedPersonId: String,
        val name: String,
        val totalActivities: Int,
        val counted: Int = 1,
        /** Visits/sessions with this person that fall inside the queried
         * bounds — shown beside the name in My Planner's record lists; the
         * person still only ever counts once ([counted]). */
        val periodActivities: Int = 0,
        val lastVisitDateInPeriod: Long? = null,
        val periodMinutes: Int = 0,
    )

    fun personActivitySummaries(
        publisherPersonId: String,
        people: List<InterestedPerson>,
        visits: List<Visit>,
        stage: PipelineStage,
        bounds: TimeBounds,
    ): List<PersonActivitySummary> {
        val qualifying = people.filter {
            it.publisherPersonId == publisherPersonId && it.pipelineStage == stage && it.status == RecordStatus.ACTIVE
        }
        val visitsByPerson = visits.groupBy { it.interestedPersonId }
        return qualifying
            .filter { person -> visitsByPerson[person.id].orEmpty().any { bounds.contains(it.visitDate) } }
            .map { person ->
                val personVisits = visitsByPerson[person.id].orEmpty()
                val inPeriod = personVisits.filter { bounds.contains(it.visitDate) }
                PersonActivitySummary(
                    interestedPersonId = person.id,
                    name = person.name,
                    totalActivities = personVisits.size,
                    periodActivities = inPeriod.size,
                    lastVisitDateInPeriod = inPeriod.maxOfOrNull { it.visitDate },
                    periodMinutes = inPeriod.sumOf { it.timeConsumedMinutes },
                )
            }
            .sortedBy { it.name }
    }
}
