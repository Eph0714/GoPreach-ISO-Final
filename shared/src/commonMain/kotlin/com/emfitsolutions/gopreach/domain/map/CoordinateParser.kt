package com.emfitsolutions.gopreach.domain.map

import kotlin.math.abs
import kotlin.math.round

/** Where a candidate came from: text read in the picture, or the GPS tags the camera stored in the photo file. Never mixed up. */
enum class CoordinateSource { VISIBLE_TEXT, PHOTO_GPS_METADATA }

enum class CoordinateFormat(val label: String) {
    DECIMAL("Decimal degrees"),
    DEGREES_MINUTES_SECONDS("Degrees, minutes, seconds"),
    DEGREES_DECIMAL_MINUTES("Degrees, decimal minutes"),
    PHOTO_GPS("Photo GPS metadata"),
}

/** One possible location. [matchedText] is exactly what was read, so the person can compare it with the picture. */
data class CoordinateCandidate(
    val lat: Double,
    val lng: Double,
    val format: CoordinateFormat,
    val source: CoordinateSource,
    val matchedText: String,
)

/**
 * Finds latitude / longitude pairs in text read from a picture (OCR). It only reports what is written: a lone number, a pair
 * outside the valid ranges, or text with no coordinates gives an empty list, and nothing is ever guessed or filled in.
 * The Web App's parser follows the same rules and the same test cases.
 */
object CoordinateParser {
    private const val NUM = """\d{1,3}(?:[.,]\d+)?"""
    private const val SEP = """\s*[,;/]?\s*"""
    private const val DEG = """\s*[°º˚o]?\s*"""
    private const val MIN = """\s*['’′´]\s*"""
    private const val SEC = """\s*(?:["”″]|''|’’|′′)\s*"""
    private const val HEM = """\s*([NSEWnsew](?![A-Za-z]))?"""

    // 14°35'58.2"N 120°59'03"E
    private val dms = Regex("""(-?\d{1,3})$DEG(\d{1,2})$MIN(\d{1,2}(?:[.,]\d+)?)$SEC$HEM$SEP(-?\d{1,3})$DEG(\d{1,2})$MIN(\d{1,2}(?:[.,]\d+)?)$SEC$HEM""")
    // 14°35.97'N 120°59.05'E
    private val ddm = Regex("""(-?\d{1,3})$DEG(\d{1,2}(?:[.,]\d+)?)$MIN$HEM$SEP(-?\d{1,3})$DEG(\d{1,2}(?:[.,]\d+)?)$MIN$HEM""")
    // 14.5995, 120.9842   /   14.5995° N, 120.9842° E   /   N 14.5995 E 120.9842
    private val decimal = Regex("""(?:([NSns])\s*)?(-?\d{1,3}[.,]\d{3,})$DEG$HEM$SEP(?:([EWew])\s*)?(-?\d{1,3}[.,]\d{3,})$DEG([NSEWnsew](?![A-Za-z]))?""")

    private fun num(s: String) = s.replace(',', '.').toDouble()

    private fun signed(value: Double, hemisphere: String?, negative: Boolean): Double {
        val v = abs(value)
        val south = hemisphere != null && hemisphere.uppercase() in setOf("S", "W")
        return if (south || negative) -v else v
    }

    private fun valid(lat: Double, lng: Double) = lat in -90.0..90.0 && lng in -180.0..180.0

    private fun r6(v: Double) = round(v * 1_000_000) / 1_000_000

    fun findCandidates(text: String): List<CoordinateCandidate> {
        val found = mutableListOf<CoordinateCandidate>()
        val taken = mutableListOf<IntRange>()
        fun overlaps(r: IntRange) = taken.any { it.first <= r.last && r.first <= it.last }
        fun add(c: CoordinateCandidate, r: IntRange) {
            if (overlaps(r) || !valid(c.lat, c.lng)) return
            if (found.none { abs(it.lat - c.lat) < 1e-5 && abs(it.lng - c.lng) < 1e-5 }) found += c
            taken += r
        }
        val flat = text.replace('\n', ' ')

        for (m in dms.findAll(flat)) {
            val g = m.groupValues
            val minA = g[2].toInt(); val secA = num(g[3]); val minB = g[6].toInt(); val secB = num(g[7])
            if (minA > 59 || secA >= 60 || minB > 59 || secB >= 60) continue
            val a = g[1].toInt(); val b = g[5].toInt()
            // A hemisphere letter says which one is the latitude (N/S) and which the longitude (E/W); without letters it is lat, lng.
            val (lat, lng) = orient(
                abs(a) + minA / 60.0 + secA / 3600.0, g[4], a < 0,
                abs(b) + minB / 60.0 + secB / 3600.0, g[8], b < 0,
            ) ?: continue
            add(CoordinateCandidate(r6(lat), r6(lng), CoordinateFormat.DEGREES_MINUTES_SECONDS, CoordinateSource.VISIBLE_TEXT, m.value.trim()), m.range)
        }
        for (m in ddm.findAll(flat)) {
            val g = m.groupValues
            val minA = num(g[2]); val minB = num(g[5])
            if (minA >= 60 || minB >= 60) continue
            val a = g[1].toInt(); val b = g[4].toInt()
            val (lat, lng) = orient(abs(a) + minA / 60.0, g[3], a < 0, abs(b) + minB / 60.0, g[6], b < 0) ?: continue
            add(CoordinateCandidate(r6(lat), r6(lng), CoordinateFormat.DEGREES_DECIMAL_MINUTES, CoordinateSource.VISIBLE_TEXT, m.value.trim()), m.range)
        }
        for (m in decimal.findAll(flat)) {
            val g = m.groupValues
            val a = num(g[2]); val b = num(g[5])
            val hemA = g[1].ifEmpty { g[3] }
            val hemB = g[4].ifEmpty { g[6] }
            val (lat, lng) = orient(abs(a), hemA, a < 0, abs(b), hemB, b < 0) ?: continue
            add(CoordinateCandidate(r6(lat), r6(lng), CoordinateFormat.DECIMAL, CoordinateSource.VISIBLE_TEXT, m.value.trim()), m.range)
        }
        return found
    }

    /** Puts two read values in latitude / longitude order using their hemisphere letters, or null when the letters contradict each other. */
    private fun orient(a: Double, hemA: String, negA: Boolean, b: Double, hemB: String, negB: Boolean): Pair<Double, Double>? {
        val ha = hemA.uppercase(); val hb = hemB.uppercase()
        val aIsLng = ha in setOf("E", "W"); val bIsLat = hb in setOf("N", "S")
        val aIsLat = ha in setOf("N", "S"); val bIsLng = hb in setOf("E", "W")
        if ((aIsLat && bIsLat) || (aIsLng && bIsLng)) return null
        return if (aIsLng || bIsLat) signed(b, hemB, negB) to signed(a, hemA, negA) else signed(a, hemA, negA) to signed(b, hemB, negB)
    }
}
