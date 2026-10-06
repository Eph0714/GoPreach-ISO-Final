package com.emfitsolutions.gopreach.platform

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
private val DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

/**
 * Formats [millis] with a `SimpleDateFormat`-style [pattern] (English names; the app is English-only), in the device time zone
 * by default. Supports y, M (M/MM/MMM/MMMM), d, E (EEE/EEEE), h, H, m, s, a, `'quoted text'`, and passes any other character
 * through. Replaces `SimpleDateFormat(pattern, Locale).format(Date(millis))` so formatting works on every platform.
 */
fun formatDate(millis: Long, pattern: String, timeZone: TimeZone = TimeZone.currentSystemDefault()): String =
    formatDateTime(Instant.fromEpochMilliseconds(millis).toLocalDateTime(timeZone), pattern)

fun formatDateTime(dt: LocalDateTime, pattern: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        if (c == '\'') {
            if (i + 1 < pattern.length && pattern[i + 1] == '\'') { out.append('\''); i += 2; continue }
            val end = pattern.indexOf('\'', i + 1).let { if (it < 0) pattern.length else it }
            out.append(pattern, i + 1, end)
            i = end + 1
            continue
        }
        if (!c.isLetter()) { out.append(c); i++; continue }
        var n = 1
        while (i + n < pattern.length && pattern[i + n] == c) n++
        val hour12 = (dt.hour % 12).let { if (it == 0) 12 else it }
        out.append(
            when (c) {
                'y' -> if (n == 2) (dt.year % 100).toString().padStart(2, '0') else dt.year.toString().padStart(n, '0')
                'M' -> when {
                    n >= 4 -> MONTHS[dt.monthNumber - 1]
                    n == 3 -> MONTHS[dt.monthNumber - 1].take(3)
                    else -> dt.monthNumber.toString().padStart(n, '0')
                }
                'd' -> dt.dayOfMonth.toString().padStart(n, '0')
                'E' -> DAYS[dt.dayOfWeek.ordinal].let { if (n >= 4) it else it.take(3) }
                'h' -> hour12.toString().padStart(n, '0')
                'H' -> dt.hour.toString().padStart(n, '0')
                'm' -> dt.minute.toString().padStart(n, '0')
                's' -> dt.second.toString().padStart(n, '0')
                'a' -> if (dt.hour < 12) "AM" else "PM"
                else -> c.toString().repeat(n)
            },
        )
        i += n
    }
    return out.toString()
}
