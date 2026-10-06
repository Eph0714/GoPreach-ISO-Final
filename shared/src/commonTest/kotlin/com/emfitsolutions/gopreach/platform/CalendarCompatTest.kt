package com.emfitsolutions.gopreach.platform

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarCompatTest {
    private val tz = TimeZone.of("Asia/Manila")
    private fun ms(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) =
        LocalDate(y, m, d).atStartOfDayIn(tz).toEpochMilliseconds() + (h * 60L + min) * 60_000L
    private fun cal(millis: Long) = Calendar.getInstance(tz, millis)

    @Test fun readsFields() {
        val c = cal(ms(2026, 2, 18, 15, 7)) // Wednesday
        assertEquals(2026, c.get(Calendar.YEAR))
        assertEquals(1, c.get(Calendar.MONTH)) // 0-based like Java
        assertEquals(18, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(15, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(7, c.get(Calendar.MINUTE))
        assertEquals(Calendar.WEDNESDAY, c.get(Calendar.DAY_OF_WEEK))
        assertEquals(Calendar.SUNDAY, cal(ms(2026, 2, 22)).get(Calendar.DAY_OF_WEEK))
        assertEquals(Calendar.MONDAY, cal(ms(2026, 2, 16)).get(Calendar.DAY_OF_WEEK))
    }

    @Test fun setIsLenientLikeJava() {
        val c = cal(ms(2026, 2, 18, 15, 7))
        c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        assertEquals(ms(2026, 2, 1), c.timeInMillis)
        c.set(Calendar.MONTH, 12) // month 12 = January of next year
        assertEquals(ms(2027, 1, 1), c.timeInMillis)
        c.set(Calendar.DAY_OF_MONTH, 32)
        assertEquals(ms(2027, 2, 1), c.timeInMillis)
    }

    @Test fun addClampsAndRolls() {
        val c = cal(ms(2026, 1, 31, 9))
        c.add(Calendar.MONTH, 1)
        assertEquals(ms(2026, 2, 28, 9), c.timeInMillis) // clamped to the end of February
        c.add(Calendar.DAY_OF_MONTH, 1)
        assertEquals(ms(2026, 3, 1, 9), c.timeInMillis)
        c.add(Calendar.YEAR, -1)
        assertEquals(ms(2025, 3, 1, 9), c.timeInMillis)
        c.add(Calendar.MINUTE, 90)
        assertEquals(ms(2025, 3, 1, 10, 30), c.timeInMillis)
    }

    @Test fun actualMaximumAndClone() {
        assertEquals(28, cal(ms(2026, 2, 10)).getActualMaximum(Calendar.DAY_OF_MONTH))
        assertEquals(29, cal(ms(2024, 2, 10)).getActualMaximum(Calendar.DAY_OF_MONTH))
        assertEquals(31, cal(ms(2026, 12, 10)).getActualMaximum(Calendar.DAY_OF_MONTH))
        val original = cal(ms(2026, 2, 18))
        val copy = original.clone() as Calendar
        copy.add(Calendar.DAY_OF_MONTH, 5)
        assertEquals(ms(2026, 2, 18), original.timeInMillis)
    }

    @Test fun mondayStartOfWeekRule() {
        // The statistics code finds Monday with ((DAY_OF_WEEK - MONDAY) + 7) % 7
        val c = cal(ms(2026, 2, 22)) // Sunday -> 6 days after Monday
        assertEquals(6, ((c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY) + 7) % 7)
    }
}
