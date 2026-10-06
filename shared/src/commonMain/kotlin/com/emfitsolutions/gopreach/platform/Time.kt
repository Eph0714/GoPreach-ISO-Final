package com.emfitsolutions.gopreach.platform

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/** Current time in epoch milliseconds (replaces `System.currentTimeMillis()` in shared code). */
fun nowMillis(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()

/** Start of the given local day (device time zone) in epoch millis. */
fun startOfDayMillis(year: Int, month: Int, day: Int): Long =
    LocalDate(year, month, day).atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()

/** (year, month 1..12, day) of [millis] in the device time zone. */
fun ymdOf(millis: Long): Triple<Int, Int, Int> {
    val d = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault()).date
    return Triple(d.year, d.monthNumber, d.dayOfMonth)
}

enum class CalendarUnit { DAYS, MONTHS, YEARS }

/** [now] minus [value] calendar units (months/years are calendar-based, not 30/365-day approximations). */
fun minusCalendar(now: Long, value: Int, unit: CalendarUnit): Long {
    val u = when (unit) {
        CalendarUnit.DAYS -> DateTimeUnit.DAY
        CalendarUnit.MONTHS -> DateTimeUnit.MONTH
        CalendarUnit.YEARS -> DateTimeUnit.YEAR
    }
    return Instant.fromEpochMilliseconds(now).minus(value, u, TimeZone.currentSystemDefault()).toEpochMilliseconds()
}

/** Zero-padded to [width] digits, like `%0<width>d`. */
fun Int.padded(width: Int): String = toString().padStart(width, '0')
