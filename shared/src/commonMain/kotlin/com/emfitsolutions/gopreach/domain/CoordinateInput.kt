package com.emfitsolutions.gopreach.domain

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The one Coordinate textbox of a person's details: "Latitude, Longitude", for example `16.0612, 120.4075`. The record keeps the two numbers separately (gpsLat, gpsLng); this turns the
 * box into them and them into the box. Anything that is not two numbers separated by a comma, or is out of range, is refused with its own message: nothing is cut, guessed or swapped.
 * The website's PersonForm::parseCoordinate follows the same rule.
 */
object CoordinateInput {
    const val FORMAT_MESSAGE = "Enter the coordinate as Latitude, Longitude, for example 16.0612, 120.4075."
    const val RANGE_MESSAGE = "Latitude must be between -90 and 90, and Longitude between -180 and 180."

    data class Pair(val lat: Double, val lng: Double)

    private val pattern = Regex("^(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)$")

    /** The latitude and longitude of [text], or null when it is blank or not a valid coordinate (see [problem]). */
    fun parse(text: String): Pair? {
        val m = pattern.find(text.trim()) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lng = m.groupValues[2].toDoubleOrNull() ?: return null
        return if (lat in -90.0..90.0 && lng in -180.0..180.0) Pair(lat, lng) else null
    }

    /** Null when [text] is blank (the coordinate is optional) or a valid coordinate; otherwise what is wrong with it. */
    fun problem(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val m = pattern.find(t) ?: return FORMAT_MESSAGE
        val lat = m.groupValues[1].toDoubleOrNull()
        val lng = m.groupValues[2].toDoubleOrNull()
        return when {
            lat == null || lng == null -> FORMAT_MESSAGE
            lat !in -90.0..90.0 || lng !in -180.0..180.0 -> RANGE_MESSAGE
            else -> null
        }
    }

    /** "16.0612, 120.4075" for a stored latitude and longitude (up to 8 decimals, no exponent, no trailing zeros). */
    fun format(lat: Double, lng: Double): String = "${plain(lat)}, ${plain(lng)}"

    private fun plain(v: Double): String {
        val scaled = (abs(v) * 100_000_000.0).roundToLong()
        val whole = scaled / 100_000_000
        val fraction = (scaled % 100_000_000).toString().padStart(8, '0').trimEnd('0')
        val sign = if (v < 0 && scaled != 0L) "-" else ""
        return sign + whole + if (fraction.isEmpty()) "" else ".$fraction"
    }
}
