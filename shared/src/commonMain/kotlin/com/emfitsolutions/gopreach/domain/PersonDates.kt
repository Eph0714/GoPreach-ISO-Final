package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.platform.nowMillis
import com.emfitsolutions.gopreach.platform.formatDate
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime


/**
 * Birthdate / baptismal date arithmetic. Dates are stored as UTC-midnight millis (what the date picker returns) and are
 * compared by calendar day, so no time zone can shift a birthday. Ages are always computed from today and are never stored.
 */
object PersonDates {
    private data class Day(val year: Int, val month: Int, val day: Int)

    private fun dayOf(utcMillis: Long): Day = dayIn(utcMillis, TimeZone.UTC)

    private fun dayIn(millis: Long, zone: TimeZone): Day {
        val d = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
        return Day(d.year, d.monthNumber - 1, d.dayOfMonth)
    }

    private fun today(nowMillis: Long): Day = dayIn(nowMillis, TimeZone.currentSystemDefault())

    private fun isAfter(a: Day, b: Day) = (a.year * 10000 + a.month * 100 + a.day) > (b.year * 10000 + b.month * 100 + b.day)

    private fun completedYears(from: Day, to: Day): Int {
        var years = to.year - from.year
        if (to.month < from.month || (to.month == from.month && to.day < from.day)) years -= 1
        return years
    }

    /** Completed years since [birthdate] as of today; null when there is no birthdate (or it is in the future). */
    fun age(birthdate: Long?, nowMillis: Long = nowMillis()): Int? {
        if (birthdate == null) return null
        val birth = dayOf(birthdate)
        val now = today(nowMillis)
        if (isAfter(birth, now)) return null
        return completedYears(birth, now).coerceAtLeast(0)
    }

    /** Completed age on the baptismal date; null unless both dates exist and the baptism is not before the birth. */
    fun baptismalAge(birthdate: Long?, baptismalDate: Long?): Int? {
        if (birthdate == null || baptismalDate == null) return null
        val birth = dayOf(birthdate)
        val baptism = dayOf(baptismalDate)
        if (isAfter(birth, baptism)) return null
        return completedYears(birth, baptism).coerceAtLeast(0)
    }

    /** "30 years" / "1 year"; empty when [years] is null (blank, never a placeholder). */
    fun yearsText(years: Int?): String = when (years) {
        null -> ""
        1 -> "1 year"
        else -> "$years years"
    }

    /** "October 15, 1995", or blank. */
    fun format(utcMillis: Long?): String {
        if (utcMillis == null) return ""
        return formatDate(utcMillis, "MMMM d, yyyy", TimeZone.UTC)
    }

    /** Null when the dates are acceptable; otherwise what is wrong. Blank dates are always fine. */
    fun problem(birthdate: Long?, baptismalDate: Long?, nowMillis: Long = nowMillis()): String? {
        val now = today(nowMillis)
        if (birthdate != null && isAfter(dayOf(birthdate), now)) return "Birthdate cannot be in the future."
        if (baptismalDate != null && isAfter(dayOf(baptismalDate), now)) return "Baptismal Date cannot be in the future."
        if (birthdate != null && baptismalDate != null && isAfter(dayOf(birthdate), dayOf(baptismalDate))) return "Baptismal Date cannot be earlier than Birthdate."
        return null
    }
}
