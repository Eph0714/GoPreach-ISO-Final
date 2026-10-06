package com.emfitsolutions.gopreach.platform

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * A small, source-compatible stand-in for `java.util.Calendar`, covering exactly what the screens use: `getInstance()`,
 * `timeInMillis`, `get`/`set`/`add` for year, month, day, hour, minute, second, millisecond and weekday, `getActualMaximum`,
 * and `clone()`. Like Java's (lenient) Calendar, `set` accepts out-of-range values and rolls them over
 * (`set(MONTH, 12)` is January of the next year). Uses the device time zone.
 */
class Calendar private constructor(millis: Long, private val tz: TimeZone) {
    var timeInMillis: Long = millis

    private fun local() = Instant.fromEpochMilliseconds(timeInMillis).toLocalDateTime(tz)

    fun get(field: Int): Int {
        val dt = local()
        return when (field) {
            YEAR -> dt.year
            MONTH -> dt.monthNumber - 1
            DAY_OF_MONTH -> dt.dayOfMonth
            HOUR_OF_DAY -> dt.hour
            MINUTE -> dt.minute
            SECOND -> dt.second
            MILLISECOND -> dt.nanosecond / 1_000_000
            DAY_OF_WEEK -> (dt.dayOfWeek.ordinal + 1) % 7 + 1 // Java: Sunday = 1 .. Saturday = 7
            else -> throw IllegalArgumentException("Unsupported Calendar field $field")
        }
    }

    fun set(field: Int, value: Int) {
        val dt = local()
        if (field == DAY_OF_WEEK) {
            timeInMillis += (value - get(DAY_OF_WEEK)) * DAY_MS
            return
        }
        var year = dt.year
        var month0 = dt.monthNumber - 1
        var day = dt.dayOfMonth
        var hour = dt.hour
        var minute = dt.minute
        var second = dt.second
        var milli = dt.nanosecond / 1_000_000
        when (field) {
            YEAR -> year = value
            MONTH -> month0 = value
            DAY_OF_MONTH -> day = value
            HOUR_OF_DAY -> hour = value
            MINUTE -> minute = value
            SECOND -> second = value
            MILLISECOND -> milli = value
            else -> throw IllegalArgumentException("Unsupported Calendar field $field")
        }
        // Lenient, like java.util.Calendar: build from day 1 of January and roll months, days and the time of day forward.
        val date = LocalDate(year, 1, 1).plus(month0, DateTimeUnit.MONTH).plus(day - 1, DateTimeUnit.DAY)
        val timeOfDay = ((hour * 60L + minute) * 60L + second) * 1000L + milli
        timeInMillis = date.atStartOfDayIn(tz).toEpochMilliseconds() + timeOfDay
    }

    fun add(field: Int, amount: Int) {
        when (field) {
            YEAR, MONTH, DAY_OF_MONTH -> {
                val dt = local()
                val unit = when (field) {
                    YEAR -> DateTimeUnit.YEAR
                    MONTH -> DateTimeUnit.MONTH
                    else -> DateTimeUnit.DAY
                }
                val moved = dt.date.plus(amount, unit)
                val timeOfDay = ((dt.hour * 60L + dt.minute) * 60L + dt.second) * 1000L + dt.nanosecond / 1_000_000
                timeInMillis = moved.atStartOfDayIn(tz).toEpochMilliseconds() + timeOfDay
            }
            HOUR_OF_DAY -> timeInMillis += amount * 3_600_000L
            MINUTE -> timeInMillis += amount * 60_000L
            SECOND -> timeInMillis += amount * 1000L
            MILLISECOND -> timeInMillis += amount
            else -> throw IllegalArgumentException("Unsupported Calendar field $field")
        }
    }

    fun getActualMaximum(field: Int): Int {
        require(field == DAY_OF_MONTH) { "Unsupported Calendar field $field" }
        val dt = local()
        val first = LocalDate(dt.year, dt.monthNumber, 1)
        return first.plus(1, DateTimeUnit.MONTH).toEpochDays().toInt() - first.toEpochDays().toInt()
    }

    /** Matches `java.util.Calendar.clone()` (returns Any; callers cast). */
    fun clone(): Any = Calendar(timeInMillis, tz)

    companion object {
        const val YEAR = 1
        const val MONTH = 2
        const val DAY_OF_MONTH = 5
        const val DAY_OF_WEEK = 7
        const val HOUR_OF_DAY = 11
        const val MINUTE = 12
        const val SECOND = 13
        const val MILLISECOND = 14

        const val SUNDAY = 1
        const val MONDAY = 2
        const val TUESDAY = 3
        const val WEDNESDAY = 4
        const val THURSDAY = 5
        const val FRIDAY = 6
        const val SATURDAY = 7

        const val JANUARY = 0
        const val FEBRUARY = 1
        const val MARCH = 2
        const val APRIL = 3
        const val MAY = 4
        const val JUNE = 5
        const val JULY = 6
        const val AUGUST = 7
        const val SEPTEMBER = 8
        const val OCTOBER = 9
        const val NOVEMBER = 10
        const val DECEMBER = 11

        private const val DAY_MS = 86_400_000L

        fun getInstance(): Calendar = Calendar(nowMillis(), TimeZone.currentSystemDefault())

        /** For tests and callers that need a fixed zone. */
        fun getInstance(timeZone: TimeZone, millis: Long = nowMillis()): Calendar = Calendar(millis, timeZone)
    }
}
