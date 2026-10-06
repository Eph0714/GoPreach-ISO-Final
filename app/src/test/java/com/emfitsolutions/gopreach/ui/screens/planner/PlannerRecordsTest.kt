package com.emfitsolutions.gopreach.ui.screens.planner

import com.emfitsolutions.gopreach.data.model.CreditHourRecord
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.PlannerDay
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.Visit
import com.emfitsolutions.gopreach.domain.DayBounds
import com.emfitsolutions.gopreach.domain.MinistryStatisticsService
import com.emfitsolutions.gopreach.domain.MonthBounds
import com.emfitsolutions.gopreach.domain.WeekBounds
import com.emfitsolutions.gopreach.domain.YearBounds
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class PlannerRecordsTest {

    private val publisher = "pub1"

    private fun day(year: Int, month: Int, dayOfMonth: Int, hour: Int = 0): Long = Calendar.getInstance().apply {
        set(year, month - 1, dayOfMonth, hour, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun legacyCreditRecordResolvesDateFromPlannerDayId() {
        val legacy = CreditHourRecord(plannerDayId = "${publisher}_20260915", hours = 1)
        assertEquals(day(2026, 9, 15), legacy.resolvedDayStart())
    }

    @Test
    fun storedDayStartWinsOverPlannerDayId() {
        val record = CreditHourRecord(plannerDayId = "${publisher}_20260915", dayStart = day(2026, 9, 20))
        assertEquals(day(2026, 9, 20), record.resolvedDayStart())
    }

    @Test
    fun malformedPlannerDayIdResolvesToZero() {
        assertEquals(0L, CreditHourRecord(plannerDayId = "garbage").resolvedDayStart())
    }

    /** The original bug: Week/Month/Year only counted Credit Hours whose day
     * also had a saved PlannerDay document. A credit-only day has none. */
    @Test
    fun creditOnDayWithoutPlannerDayStillCountsForWeekMonthYear() {
        val creditDay = day(2026, 9, 23)
        val credit = CreditHourRecord(
            id = "c1",
            publisherPersonId = publisher,
            plannerDayId = PlannerDay.idFor(publisher, creditDay),
            dayStart = creditDay,
            hours = 2,
            minutes = 30,
        )
        val records = listOf(credit)
        assertEquals(150, records.inPeriod(DayBounds.of(creditDay)).sumOf { it.totalMinutes })
        assertEquals(150, records.inPeriod(WeekBounds.of(creditDay)).sumOf { it.totalMinutes })
        assertEquals(150, records.inPeriod(MonthBounds.of(creditDay)).sumOf { it.totalMinutes })
        assertEquals(150, records.inPeriod(YearBounds.of(creditDay)).sumOf { it.totalMinutes })
        assertEquals(0, records.inPeriod(MonthBounds.of(day(2026, 10, 1))).size)

        val built = buildPeriodRecords(publisher, WeekBounds.of(creditDay), allDays = emptyList(), creditRecords = records, people = emptyList(), visits = emptyList())
        assertEquals(listOf(credit), built.creditRecords)
    }

    @Test
    fun returnVisitAndBibleStudyListsAreOneRowPerUniquePersonAndMatchCounts() {
        val people = listOf(
            InterestedPerson(id = "rv1", publisherPersonId = publisher, name = "Ana", pipelineStage = PipelineStage.RETURN_VISIT, status = RecordStatus.ACTIVE),
            InterestedPerson(id = "rv2", publisherPersonId = publisher, name = "Ben", pipelineStage = PipelineStage.RETURN_VISIT, status = RecordStatus.ACTIVE),
            InterestedPerson(id = "bs1", publisherPersonId = publisher, name = "Cara", pipelineStage = PipelineStage.BIBLE_STUDY, status = RecordStatus.ACTIVE),
            // Someone else's person must never be counted.
            InterestedPerson(id = "other", publisherPersonId = "pub2", name = "Dan", pipelineStage = PipelineStage.RETURN_VISIT, status = RecordStatus.ACTIVE),
        )
        val visits = listOf(
            // Ana visited three times in September (twice on the same day).
            Visit(id = "v1", interestedPersonId = "rv1", visitDate = day(2026, 9, 2, 9)),
            Visit(id = "v2", interestedPersonId = "rv1", visitDate = day(2026, 9, 2, 15)),
            Visit(id = "v3", interestedPersonId = "rv1", visitDate = day(2026, 9, 20)),
            // Ben once in September, once in October.
            Visit(id = "v4", interestedPersonId = "rv2", visitDate = day(2026, 9, 21)),
            Visit(id = "v5", interestedPersonId = "rv2", visitDate = day(2026, 10, 5)),
            // Cara: four sessions in September.
            Visit(id = "v6", interestedPersonId = "bs1", visitDate = day(2026, 9, 1)),
            Visit(id = "v7", interestedPersonId = "bs1", visitDate = day(2026, 9, 8)),
            Visit(id = "v8", interestedPersonId = "bs1", visitDate = day(2026, 9, 15)),
            Visit(id = "v9", interestedPersonId = "bs1", visitDate = day(2026, 9, 22)),
            Visit(id = "v10", interestedPersonId = "other", visitDate = day(2026, 9, 2)),
        )

        listOf(
            DayBounds.of(day(2026, 9, 2)),
            WeekBounds.of(day(2026, 9, 21)),
            MonthBounds.of(day(2026, 9, 1)),
            YearBounds.of(day(2026, 1, 1)),
        ).forEach { bounds ->
            val built = buildPeriodRecords(publisher, bounds, emptyList(), emptyList(), people, visits)
            assertEquals(
                MinistryStatisticsService.uniqueVisitedPersons(publisher, people, visits, PipelineStage.RETURN_VISIT, bounds),
                built.returnVisits.size,
            )
            assertEquals(
                MinistryStatisticsService.uniqueVisitedPersons(publisher, people, visits, PipelineStage.BIBLE_STUDY, bounds),
                built.bibleStudies.size,
            )
        }

        // Ana's two same-day visits are one person, two activities.
        val sept2 = buildPeriodRecords(publisher, DayBounds.of(day(2026, 9, 2)), emptyList(), emptyList(), people, visits)
        assertEquals(listOf("Ana"), sept2.returnVisits.map { it.name })
        assertEquals(2, sept2.returnVisits.single().periodActivities)

        // September: Ana + Ben = 2 unique Return Visits; Cara = 1 Bible Study with 4 sessions.
        val september = buildPeriodRecords(publisher, MonthBounds.of(day(2026, 9, 1)), emptyList(), emptyList(), people, visits)
        assertEquals(2, september.returnVisits.size)
        assertEquals(1, september.bibleStudies.size)
        assertEquals(4, september.bibleStudies.single().periodActivities)

        // Year: Ben's September + October visits still count him once.
        val year = buildPeriodRecords(publisher, YearBounds.of(day(2026, 1, 1)), emptyList(), emptyList(), people, visits)
        assertEquals(2, year.returnVisits.size)
        assertEquals(2, year.returnVisits.first { it.name == "Ben" }.periodActivities)
    }

    @Test
    fun dailyMinutesMapOnlyIncludesDaysWithMinutesInPeriodAndSumsToMonthlyTotal() {
        val days = listOf(
            PlannerDay(id = "a", publisherPersonId = publisher, dayStart = day(2026, 9, 1), totalMinutes = 60),
            PlannerDay(id = "b", publisherPersonId = publisher, dayStart = day(2026, 9, 2), totalMinutes = 30),
            PlannerDay(id = "c", publisherPersonId = publisher, dayStart = day(2026, 9, 3), totalMinutes = 0),
            PlannerDay(id = "d", publisherPersonId = publisher, dayStart = day(2026, 10, 1), totalMinutes = 45),
        )
        val bounds = MonthBounds.of(day(2026, 9, 1))
        val map = dailyMinutes(days, bounds)

        assertEquals(mapOf(day(2026, 9, 1) to 60, day(2026, 9, 2) to 30), map)
        // Spec §22 — "the monthly total must equal the sum of the daily
        // accumulated times."
        assertEquals(90, map.values.sum())
    }

    @Test
    fun dailyCreditMinutesMapKeepsCreditSeparateFromRegularHours() {
        val day23 = day(2026, 9, 23)
        val credits = listOf(
            CreditHourRecord(id = "c1", publisherPersonId = publisher, dayStart = day23, hours = 1, minutes = 0),
            CreditHourRecord(id = "c2", publisherPersonId = publisher, dayStart = day23, hours = 0, minutes = 30),
            CreditHourRecord(id = "c3", publisherPersonId = publisher, dayStart = day(2026, 10, 1), hours = 2, minutes = 0),
        )
        val map = dailyCreditMinutes(credits, MonthBounds.of(day23))

        // Two entries on the same day combine into one day-total (spec §20.B).
        assertEquals(mapOf(day23 to 90), map)
    }

    @Test
    fun dailyUniquePersonCountsAreIndependentPerDayAndExcludeZeroDays() {
        val people = listOf(
            InterestedPerson(id = "rv1", publisherPersonId = publisher, name = "John", pipelineStage = PipelineStage.RETURN_VISIT, status = RecordStatus.ACTIVE),
            InterestedPerson(id = "rv2", publisherPersonId = publisher, name = "Maria", pipelineStage = PipelineStage.RETURN_VISIT, status = RecordStatus.ACTIVE),
        )
        val sept23 = day(2026, 9, 23)
        val sept5 = day(2026, 9, 5)
        val visits = listOf(
            // John visited twice on Sep 23 — still 1 person that day (spec §32).
            Visit(id = "v1", interestedPersonId = "rv1", visitDate = sept23),
            Visit(id = "v2", interestedPersonId = "rv1", visitDate = sept23 + 1000),
            Visit(id = "v3", interestedPersonId = "rv2", visitDate = sept23),
            // John also visited on Sep 5 — a different day's own count.
            Visit(id = "v4", interestedPersonId = "rv1", visitDate = sept5),
        )
        val dayStarts = listOf(sept5, sept23, day(2026, 9, 24))

        val counts = dailyUniquePersonCounts(publisher, people, visits, PipelineStage.RETURN_VISIT, dayStarts)

        assertEquals(mapOf(sept5 to 1, sept23 to 2), counts)
    }

    @Test
    fun hourDaysOnlyIncludeDaysWithMinutesInsideThePeriod() {
        val days = listOf(
            PlannerDay(id = "a", publisherPersonId = publisher, dayStart = day(2026, 9, 1), totalMinutes = 90),
            PlannerDay(id = "b", publisherPersonId = publisher, dayStart = day(2026, 9, 2), totalMinutes = 0, note = "note only"),
            PlannerDay(id = "c", publisherPersonId = publisher, dayStart = day(2026, 10, 1), totalMinutes = 30),
        )
        val built = buildPeriodRecords(publisher, MonthBounds.of(day(2026, 9, 1)), days, emptyList(), emptyList(), emptyList())
        assertEquals(listOf("a"), built.hourDays.map { it.id })
    }
}
