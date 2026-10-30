package com.emfitsolutions.gopreach.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * When a Publisher may SEND the Monthly Report: only on the last two calendar days of the reporting month, by the SERVER's clock in the service-month
 * time zone (Asia/Manila). Nothing is hardcoded: a 31-day month opens on the 30th, a 30-day month on the 29th, February on the 27th (28 days) or the
 * 28th (leap year). The API enforces the same rule (`backend/src/reportWindow.js`) and the website mirrors it, so a changed device date cannot get a
 * report through early.
 *
 * A report for a month that has already ended can still be sent late; a month that has not started cannot be reported. Preparing and editing a report is
 * never restricted, only sending it.
 */
object ReportSubmissionWindow {
    enum class State { BEFORE, OPEN, LATE, FUTURE }

    data class Window(val state: State, val firstDay: Int, val lastDay: Int, val monthName: String) {
        val open: Boolean get() = state == State.OPEN || state == State.LATE
        val message: String
            get() = if (state == State.FUTURE) "This month has not started, so its report cannot be sent yet."
            else "The report for this month can be sent on the last two days of the month ($monthName $firstDay and $lastDay). You can prepare it now."
    }

    private val manila = TimeZone.of("Asia/Manila")
    private const val WINDOW_DAYS = 2
    private val MONTH_NAMES = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    /** [periodMonthStart]: the start of the reporting month (any instant inside its first day, even from another zone, is accepted); [nowMillis]: the server time. */
    fun of(periodMonthStart: Long, nowMillis: Long): Window {
        // 12 hours in, so a month start stored in a neighbouring zone still lands on the right month
        val period = Instant.fromEpochMilliseconds(periodMonthStart + 12 * 3_600_000L).toLocalDateTime(manila).date
        val now = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(manila).date
        val total = daysInMonth(period.year, period.monthNumber)
        val first = total - WINDOW_DAYS + 1
        val ordinal = period.year * 12 + period.monthNumber
        val current = now.year * 12 + now.monthNumber
        val state = when {
            ordinal > current -> State.FUTURE
            ordinal < current -> State.LATE
            now.dayOfMonth >= first -> State.OPEN
            else -> State.BEFORE
        }
        return Window(state, first, total, MONTH_NAMES[period.monthNumber - 1])
    }

    fun daysInMonth(year: Int, month: Int): Int {
        val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        return when (month) {
            2 -> if (leap) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
    }
}
