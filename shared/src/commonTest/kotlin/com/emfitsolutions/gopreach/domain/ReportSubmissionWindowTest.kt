package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.domain.ReportSubmissionWindow.State
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportSubmissionWindowTest {
    /** Manila noon of a calendar date (UTC+8, no daylight saving) as a server instant. */
    private fun at(y: Int, m: Int, d: Int, hour: Int = 12): Long = kotlinx.datetime.LocalDateTime(y, m, d, hour, 0).let {
        it.toInstant(kotlinx.datetime.TimeZone.of("Asia/Manila")).toEpochMilliseconds()
    }

    private fun state(y: Int, m: Int, day: Int, hour: Int = 12) = ReportSubmissionWindow.of(at(y, m, 1, 0), at(y, m, day, hour)).state

    @Test fun a31DayMonthOpensOnThe30thAndThe31stOnly() {
        assertEquals(State.BEFORE, state(2026, 10, 29))
        assertEquals(State.OPEN, state(2026, 10, 30))
        assertEquals(State.OPEN, state(2026, 10, 31))
    }

    @Test fun a30DayMonthOpensOnThe29thAndThe30thOnly() {
        assertEquals(State.BEFORE, state(2026, 11, 28))
        assertEquals(State.OPEN, state(2026, 11, 29))
        assertEquals(State.OPEN, state(2026, 11, 30))
    }

    @Test fun februaryFollowsTheRealCalendar() {
        assertEquals(State.BEFORE, state(2027, 2, 26))
        assertEquals(State.OPEN, state(2027, 2, 27))
        assertEquals(State.OPEN, state(2027, 2, 28))
        assertEquals(State.BEFORE, state(2028, 2, 27))
        assertEquals(State.OPEN, state(2028, 2, 28))
        assertEquals(State.OPEN, state(2028, 2, 29))
        assertEquals(29, ReportSubmissionWindow.daysInMonth(2000, 2))
        assertEquals(28, ReportSubmissionWindow.daysInMonth(2100, 2))
    }

    @Test fun theDayIsTheDayInManilaNotInUtc() {
        assertEquals(State.BEFORE, ReportSubmissionWindow.of(at(2026, 10, 1, 0), at(2026, 10, 29, 23) + 30 * 60_000L).state)
        assertEquals(State.OPEN, ReportSubmissionWindow.of(at(2026, 10, 1, 0), at(2026, 10, 30, 0) + 60_000L).state)
    }

    @Test fun aMonthThatHasEndedIsStillSentLateAndAFutureMonthIsNot() {
        val late = ReportSubmissionWindow.of(at(2026, 9, 1, 0), at(2026, 10, 5))
        assertEquals(State.LATE, late.state)
        assertTrue(late.open)
        val future = ReportSubmissionWindow.of(at(2026, 11, 1, 0), at(2026, 10, 31))
        assertEquals(State.FUTURE, future.state)
        assertFalse(future.open)
    }

    @Test fun theMessageNamesTheDays() {
        assertTrue(ReportSubmissionWindow.of(at(2028, 2, 1, 0), at(2028, 2, 3)).message.contains("February 28 and 29"))
        assertTrue(ReportSubmissionWindow.of(at(2026, 10, 1, 0), at(2026, 10, 3)).message.contains("October 30 and 31"))
    }
}
