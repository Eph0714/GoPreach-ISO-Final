package com.emfitsolutions.gopreach.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.Instant

/**
 * A `[start, end)` millis window one of [DayBounds]/[WeekBounds]/[MonthBounds]/[YearBounds] resolves to — lets the statistics
 * logic be written once across all reporting periods. Exclusive upper bound avoids any end-of-period rounding edge case, and
 * calendar arithmetic (not a hand-rolled day count) handles 28/29/30/31-day months. Uses the device's current time zone.
 */
interface TimeBounds {
    operator fun contains(millis: Long): Boolean
}

private fun dateOf(millis: Long, tz: TimeZone): LocalDate = Instant.fromEpochMilliseconds(millis).toLocalDateTime(tz).date
private fun startMillis(date: LocalDate, tz: TimeZone): Long = date.atStartOfDayIn(tz).toEpochMilliseconds()

/** `[start, end)` for the single day containing [dayStart]. */
data class DayBounds(val startInclusive: Long, val endExclusive: Long) : TimeBounds {
    override fun contains(millis: Long): Boolean = millis >= startInclusive && millis < endExclusive

    companion object {
        fun of(dayStart: Long, tz: TimeZone = TimeZone.currentSystemDefault()): DayBounds {
            val d = dateOf(dayStart, tz)
            return DayBounds(startMillis(d, tz), startMillis(d.plus(1, DateTimeUnit.DAY), tz))
        }
    }
}

/** `[start, end)` Monday..Sunday for the week containing the given instant. */
data class WeekBounds(val startInclusive: Long, val endExclusive: Long) : TimeBounds {
    override fun contains(millis: Long): Boolean = millis >= startInclusive && millis < endExclusive

    companion object {
        fun of(anyMillisInWeek: Long, tz: TimeZone = TimeZone.currentSystemDefault()): WeekBounds {
            val d = dateOf(anyMillisInWeek, tz)
            val monday = d.minus(d.dayOfWeek.ordinal, DateTimeUnit.DAY) // DayOfWeek.MONDAY.ordinal == 0
            return WeekBounds(startMillis(monday, tz), startMillis(monday.plus(7, DateTimeUnit.DAY), tz))
        }
    }
}

/** `[start, end)` first-of-month..first-of-next-month for the month containing [periodMonthStart]. */
data class MonthBounds(val startInclusive: Long, val endExclusive: Long) : TimeBounds {
    override fun contains(millis: Long): Boolean = millis >= startInclusive && millis < endExclusive

    companion object {
        fun of(periodMonthStart: Long, tz: TimeZone = TimeZone.currentSystemDefault()): MonthBounds {
            val d = dateOf(periodMonthStart, tz)
            val first = LocalDate(d.year, d.month, 1)
            return MonthBounds(startMillis(first, tz), startMillis(first.plus(1, DateTimeUnit.MONTH), tz))
        }
    }
}

/** `[start, end)` for the calendar year containing [yearStart]. */
data class YearBounds(val startInclusive: Long, val endExclusive: Long) : TimeBounds {
    override fun contains(millis: Long): Boolean = millis >= startInclusive && millis < endExclusive

    companion object {
        fun of(yearStart: Long, tz: TimeZone = TimeZone.currentSystemDefault()): YearBounds {
            val d = dateOf(yearStart, tz)
            val first = LocalDate(d.year, 1, 1)
            return YearBounds(startMillis(first, tz), startMillis(first.plus(1, DateTimeUnit.YEAR), tz))
        }
    }
}
