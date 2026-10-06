package com.emfitsolutions.gopreach.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimeBoundsTest {
    private val tz = TimeZone.of("Asia/Manila")
    private fun ms(y: Int, m: Int, d: Int) = LocalDate(y, m, d).atStartOfDayIn(tz).toEpochMilliseconds()
    private val midday = ms(2026, 2, 18) + 12 * 3_600_000L // Wed 18 Feb 2026

    @Test fun dayCoversExactlyOneDay() {
        val b = DayBounds.of(midday, tz)
        assertEquals(ms(2026, 2, 18), b.startInclusive)
        assertEquals(ms(2026, 2, 19), b.endExclusive)
        assertTrue(b.startInclusive in b && (b.endExclusive - 1) in b)
        assertFalse(b.endExclusive in b)
    }

    @Test fun weekRunsMondayToSunday() {
        val b = WeekBounds.of(midday, tz)
        assertEquals(ms(2026, 2, 16), b.startInclusive) // Monday
        assertEquals(ms(2026, 2, 23), b.endExclusive)
        assertEquals(b, WeekBounds.of(ms(2026, 2, 22) + 1000, tz)) // Sunday belongs to the same week
    }

    @Test fun monthHandlesShortAndLeapMonths() {
        assertEquals(ms(2026, 3, 1), MonthBounds.of(midday, tz).endExclusive) // February 2026 has 28 days
        assertEquals(ms(2024, 3, 1), MonthBounds.of(ms(2024, 2, 10), tz).endExclusive) // leap year
        assertEquals(ms(2027, 1, 1), MonthBounds.of(ms(2026, 12, 31), tz).endExclusive)
    }

    @Test fun yearCoversTheCalendarYear() {
        val b = YearBounds.of(midday, tz)
        assertEquals(ms(2026, 1, 1), b.startInclusive)
        assertEquals(ms(2027, 1, 1), b.endExclusive)
    }
}
