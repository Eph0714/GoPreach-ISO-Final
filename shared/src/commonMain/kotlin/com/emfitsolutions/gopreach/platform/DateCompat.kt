package com.emfitsolutions.gopreach.platform

/**
 * Source-compatible stand-ins for the three `java.*` types UI code used only to print dates:
 * `Date(millis)`, `Locale.getDefault()` / `Locale.US` and `SimpleDateFormat(pattern, locale).format(date)`.
 * Swapping the import lines is all a screen needs to format dates on every platform. (English names only.)
 */
class Date(val time: Long = nowMillis())

class Locale private constructor() {
    companion object {
        fun getDefault(): Locale = Locale()
        val US: Locale = Locale()
        val ENGLISH: Locale = Locale()
    }
}

class SimpleDateFormat(private val pattern: String, @Suppress("UNUSED_PARAMETER") locale: Locale = Locale.getDefault()) {
    fun format(date: Date): String = formatDate(date.time, pattern)
    fun format(millis: Long): String = formatDate(millis, pattern)
}
