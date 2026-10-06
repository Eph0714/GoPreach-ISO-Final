package com.emfitsolutions.gopreach.data.location

import kotlin.math.abs
import kotlin.math.roundToInt

/** "Shared Location Reports" spec's example format:
 * `8°51'24"N 2°29'57"E` — degrees/minutes/seconds, not plain decimal. */
fun formatCoordinatesDms(lat: Double, lng: Double): String =
    "${toDms(lat, 'N', 'S')} ${toDms(lng, 'E', 'W')}"

private fun toDms(value: Double, positiveHemisphere: Char, negativeHemisphere: Char): String {
    val hemisphere = if (value >= 0) positiveHemisphere else negativeHemisphere
    val absolute = abs(value)
    val degrees = absolute.toInt()
    val minutesFull = (absolute - degrees) * 60
    var minutes = minutesFull.toInt()
    var seconds = ((minutesFull - minutes) * 60).roundToInt()
    // A rounded-up 60" seconds carries into the next minute (and, rarely,
    // minutes into the next degree) rather than ever displaying "60"".
    if (seconds == 60) {
        seconds = 0
        minutes += 1
    }
    return "$degrees°$minutes'$seconds\"$hemisphere"
}
