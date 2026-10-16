package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.domain.PlannerNarration.Month
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlannerNarrationTest {
    @Test
    fun durationsAreSpokenNaturally() {
        assertEquals("no time", PlannerNarration.duration(0))
        assertEquals("1 hour", PlannerNarration.duration(60))
        assertEquals("1 hour and 1 minute", PlannerNarration.duration(61))
        assertEquals("12 hours and 30 minutes", PlannerNarration.duration(750))
        assertEquals("45 minutes", PlannerNarration.duration(45))
    }

    @Test
    fun aRangeIsSummarisedWithoutAddingPeopleCountsAcrossMonths() {
        val s = PlannerNarration.script("May 2026 to June 2026", listOf(Month("May 2026", 600, 4, 1), Month("June 2026", 900, 6, 2)))
        assertEquals("Planner summary for May 2026 to June 2026, 2 months.", s[0])
        assertTrue(s.contains("Total time in the field: 25 hours."))
        assertTrue(s.contains("That is an average of 12 hours and 30 minutes a month."))
        assertTrue(s.contains("Your busiest month was June 2026, with 15 hours."))
        assertTrue(s.contains("May 2026: 10 hours, 4 return visits and 1 Bible study."))
        assertTrue(s.contains("Most return visits in a month: 6, in June 2026."))
        assertTrue(s.none { it.contains("10 return visits") }, "monthly person counts are never summed")
        assertTrue(s.last().contains("not added together"))
    }

    private fun period(unit: String = "month", minutes: Int = 0, credit: Int = 0, rv: Int = 0, bs: Int = 0, goal: Int = 0, remaining: Int = 0, surplus: Int = 0) =
        PlannerNarration.Period(unit, "October 2026", minutes, credit, rv, bs, goal, remaining, surplus)

    @Test
    fun aSinglePeriodIsReadInTheOrderTheScreenShowsIt() {
        val s = PlannerNarration.periodScript(period(minutes = 600, credit = 90, rv = 3, bs = 1, goal = 20, remaining = 600))
        assertEquals("Your planner for October 2026.", s[0])
        assertTrue(s.contains("Field service time for the month: 10 hours."))
        assertTrue(s.contains("Credit hours: 1 hour and 30 minutes."))
        assertTrue(s.contains("Return visits: 3 people."))
        assertTrue(s.contains("Bible studies: 1 person."))
        assertTrue(s.contains("Your hours goal is 20 hours. 10 hours remaining."))
        assertTrue(s.last().contains("not added up from smaller periods"))
        assertTrue(PlannerNarration.periodScript(period(minutes = 1500, goal = 20, surplus = 300)).any { it.contains("reached it, with 5 hours more") })
        assertTrue(PlannerNarration.periodScript(period(minutes = 60)).contains("No hours goal is set for this month."))
    }

    @Test
    fun hiddenSectionsAreNotReadAndEmptyPeriodsSaySo() {
        val hidden = PlannerNarration.periodScript(period(minutes = 600, rv = 3).copy(showReturnVisits = false, showCredit = false))
        assertTrue(hidden.none { it.contains("Return visits") || it.contains("Credit") })
        assertTrue(PlannerNarration.periodScript(period(unit = "day")).contains("Nothing is recorded for this day yet."))
        assertTrue(PlannerNarration.periodScript(period().copy(showHours = false, showCredit = false, showReturnVisits = false, showBibleStudies = false)).any { it.contains("hidden") })
        val year = PlannerNarration.periodScript(period(unit = "year", minutes = 3000).copy(monthRows = listOf(Month("May 2026", 600, 0, 0), Month("June 2026", 900, 0, 0))))
        assertTrue(year.contains("Your busiest month was June 2026, with 15 hours."))
    }

    @Test
    fun emptyRangesAndComparisonsAreHandled() {
        assertTrue(PlannerNarration.script("May 2026", emptyList()).contains("There is nothing recorded in this range yet."))
        assertTrue(PlannerNarration.script("May 2026", listOf(Month("May 2026", 0, 0, 0))).contains("Nothing is recorded in this range yet."))
        val c = PlannerNarration.compareScript("A", listOf(Month("x", 600, 2, 1)), "B", listOf(Month("y", 900, 4, 1), Month("z", 900, 2, 0)))
        assertTrue(c.contains("That is 5 hours more a month in the second range."))
        assertTrue(c.contains("Return visits average 2.0 a month in the first range and 3.0 in the second."))
        assertTrue(PlannerNarration.compareScript("A", emptyList(), "B", listOf(Month("y", 60, 0, 0))).any { it.contains("cannot be compared") })
    }
}
