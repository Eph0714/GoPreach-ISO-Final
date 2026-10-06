package com.emfitsolutions.gopreach.domain

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Birthdate / baptismal date arithmetic. Dates are stored as UTC-midnight millis (what the date picker returns) and are
 * compared by calendar day, so no time zone can shift a birthday. Ages are always computed from today and are never stored.
 */
object PersonDates {
    private data class Day(val year: Int, val month: Int, val day: Int)

    private fun dayOf(utcMillis: Long): Day {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
        return Day(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
    }

    private fun today(nowMillis: Long): Day {
        val c = Calendar.getInstance().apply { timeInMillis = nowMillis }
        return Day(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
    }

    private fun isAfter(a: Day, b: Day) = (a.year * 10000 + a.month * 100 + a.day) > (b.year * 10000 + b.month * 100 + b.day)

    private fun completedYears(from: Day, to: Day): Int {
        var years = to.year - from.year
        if (to.month < from.month || (to.month == from.month && to.day < from.day)) years -= 1
        return years
    }

    /** Completed years since [birthdate] as of today; null when there is no birthdate (or it is in the future). */
    fun age(birthdate: Long?, nowMillis: Long = System.currentTimeMillis()): Int? {
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
        return SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(utcMillis))
    }

    /** Null when the dates are acceptable; otherwise what is wrong. Blank dates are always fine. */
    fun problem(birthdate: Long?, baptismalDate: Long?, nowMillis: Long = System.currentTimeMillis()): String? {
        val now = today(nowMillis)
        if (birthdate != null && isAfter(dayOf(birthdate), now)) return "Birthdate cannot be in the future."
        if (baptismalDate != null && isAfter(dayOf(baptismalDate), now)) return "Baptismal Date cannot be in the future."
        if (birthdate != null && baptismalDate != null && isAfter(dayOf(birthdate), dayOf(baptismalDate))) return "Baptismal Date cannot be earlier than Birthdate."
        return null
    }
}
