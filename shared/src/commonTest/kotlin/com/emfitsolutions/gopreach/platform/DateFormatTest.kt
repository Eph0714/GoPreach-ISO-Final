package com.emfitsolutions.gopreach.platform

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals

class DateFormatTest {
    private val tz = TimeZone.of("Asia/Manila")
    // Wed 18 Feb 2026, 15:07:09
    private val t = LocalDate(2026, 2, 18).atStartOfDayIn(tz).toEpochMilliseconds() + (15 * 3600 + 7 * 60 + 9) * 1000L
    private fun f(p: String) = formatDate(t, p, tz)

    @Test fun commonPatternsUsedByTheApp() {
        assertEquals("Feb 18, 2026", f("MMM d, yyyy"))
        assertEquals("February 2026", f("MMMM yyyy"))
        assertEquals("February 18, 2026", f("MMMM d, yyyy"))
        assertEquals("20260218", f("yyyyMMdd"))
        assertEquals("3:07 PM", f("h:mm a"))
        assertEquals("Wednesday, February 18, 2026", f("EEEE, MMMM d, yyyy"))
        assertEquals("02-18-2026", f("MM-dd-yyyy"))
        assertEquals("2026-02-18 15:07:09", f("yyyy-MM-dd HH:mm:ss"))
        assertEquals("20260218-150709", f("yyyyMMdd-HHmmss"))
    }

    @Test fun quotedTextAndMidnightNoon() {
        assertEquals("Feb 18, 2026 at 3:07 PM", f("MMM d, yyyy 'at' h:mm a"))
        val midnight = LocalDate(2026, 2, 18).atStartOfDayIn(tz).toEpochMilliseconds()
        assertEquals("12:00 AM", formatDate(midnight, "h:mm a", tz))
        assertEquals("12:00 PM", formatDate(midnight + 12 * 3600_000L, "h:mm a", tz))
    }
}
