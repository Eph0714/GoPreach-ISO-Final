package com.emfitsolutions.gopreach.domain.navigation

import com.emfitsolutions.gopreach.domain.map.GeoPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Where the vehicle is along the route right now, and what to say about it. */
data class NavigationProgress(
    val stepIndex: Int,
    /** Distance still to drive before the next manoeuvre (to the destination on the last step). */
    val metersToManeuver: Double,
    val remainingMeters: Double,
    val remainingSeconds: Double,
    /** Distance from the route line. */
    val offRouteMeters: Double,
    val offRoute: Boolean,
    val arrived: Boolean,
    /** Spoken instructions that became due with this position, in order. Each is returned once. */
    val announcements: List<String>,
)

/**
 * Follows a [Route] with the vehicle's filtered GPS positions: which step it is on, how far to the next turn, whether it has left the route
 * and when to speak. Pure logic - a simulated drive can be replayed in a test.
 *
 * Matching is monotonic: the position is projected onto the route near where it last was, so a road that doubles back never makes progress jump
 * ahead or behind. Leaving the route needs [OFF_ROUTE_FIXES] consecutive fixes beyond the allowed distance (or one clearly far away), so a
 * single poor fix does not trigger a reroute.
 */
class NavigationEngine(val route: Route) {
    private val points = route.points
    private val cumulative = DoubleArray(points.size)
    private val stepStart: DoubleArray
    private val stepEnd: DoubleArray
    private var matchedSegment = 0
    private var traveled = 0.0
    private var offCount = 0
    private val spoken = HashSet<String>()
    private var started = false

    init {
        for (i in 1 until points.size) cumulative[i] = cumulative[i - 1] + meters(points[i - 1], points[i])
        stepStart = DoubleArray(route.steps.size) { cumulative.getOrElse(route.steps[it].beginIndex.coerceIn(0, points.lastIndex.coerceAtLeast(0))) { 0.0 } }
        stepEnd = DoubleArray(route.steps.size) { cumulative.getOrElse(route.steps[it].endIndex.coerceIn(0, points.lastIndex.coerceAtLeast(0))) { total } }
    }

    private val total: Double get() = cumulative.lastOrNull() ?: 0.0

    fun update(lat: Double, lng: Double, accuracyMeters: Float?, speedMps: Float?): NavigationProgress {
        val (segment, along, cross) = project(lat, lng)
        matchedSegment = segment
        // Progress never goes backwards: a noisy fix beside the road cannot undo distance already driven.
        if (along > traveled) traveled = along
        val allowed = max(35.0, (accuracyMeters ?: 0f) * 1.5)
        offCount = if (cross > allowed) offCount + 1 else 0
        val off = offCount >= OFF_ROUTE_FIXES || cross > max(150.0, allowed * 3)

        val remaining = (total - traveled).coerceAtLeast(0.0)
        val arrived = remaining <= max(ARRIVE_METERS, (accuracyMeters ?: 0f).toDouble()) && !off
        val idx = if (arrived) route.steps.lastIndex else stepIndexAt(traveled)
        val toManeuver = if (idx < route.steps.lastIndex) (stepEnd[idx] - traveled).coerceAtLeast(0.0) else remaining
        val seconds = if (total > 0) route.durationSeconds * (remaining / total) else 0.0

        val said = if (off) emptyList() else announcementsFor(idx, toManeuver, remaining, speedMps ?: 0f, arrived)
        return NavigationProgress(idx, toManeuver, remaining, seconds, cross, off, arrived, said)
    }

    private fun announcementsFor(idx: Int, toManeuver: Double, remaining: Double, speedMps: Float, arrived: Boolean): List<String> {
        val out = mutableListOf<String>()
        fun once(key: String, text: String?) { if (!text.isNullOrBlank() && spoken.add(key)) out += text }
        val steps = route.steps
        if (steps.isEmpty()) return out

        if (!started) { started = true; once("start", steps[0].pre ?: steps[0].instruction) }
        if (arrived) {
            once("arrived", steps.last().pre ?: "You have arrived at your destination.")
            return out
        }
        // After passing a manoeuvre: how far to the next one (only worth saying on a long stretch).
        val current = steps[idx]
        if (idx > 0 && current.lengthMeters >= LONG_STRETCH_METERS) once("post$idx", current.post)

        val next = steps.getOrNull(idx + 1) ?: return out
        val far = (speedMps * 22f).toDouble().coerceIn(180.0, 650.0)
        val near = (speedMps * 7f).toDouble().coerceIn(45.0, 140.0)
        if (toManeuver <= near) {
            once("pre${idx + 1}", next.pre ?: next.instruction)
        } else if (toManeuver <= far && current.lengthMeters > far + 60) {
            val text = next.alert ?: next.instruction
            once("alert${idx + 1}", "In ${RouteFormat.spokenDistance(toManeuver)}, ${text.replaceFirstChar { it.lowercase() }}")
        }
        return out
    }

    private fun stepIndexAt(distance: Double): Int {
        for (i in route.steps.indices.reversed()) if (distance >= stepStart[i] - 1.0) return i
        return 0
    }

    private data class Match(val segment: Int, val along: Double, val cross: Double)

    /** Nearest point of the route around the last match (a window ahead, a little behind), as along-route distance and cross-track distance. */
    private fun project(lat: Double, lng: Double): Match {
        if (points.size < 2) return Match(0, 0.0, 0.0)
        var best = Match(matchedSegment, cumulative[matchedSegment], Double.MAX_VALUE)
        val from = max(0, matchedSegment - 4)
        var to = min(points.size - 2, matchedSegment + WINDOW_SEGMENTS)
        // Far from where we last were (after a gap): search the whole route.
        if (offCount > 0) { to = points.size - 2 }
        val kLat = 110_540.0
        val kLng = 111_320.0 * cos(lat * PI / 180)
        for (i in (if (offCount > 0) 0 else from)..to) {
            val a = points[i]
            val b = points[i + 1]
            val ax = (a.lng - lng) * kLng; val ay = (a.lat - lat) * kLat
            val bx = (b.lng - lng) * kLng; val by = (b.lat - lat) * kLat
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx; val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            if (d < best.cross) best = Match(i, cumulative[i] + t * sqrt(len2), d)
        }
        return best
    }

    companion object {
        const val OFF_ROUTE_FIXES = 3
        const val ARRIVE_METERS = 25.0
        const val LONG_STRETCH_METERS = 400.0
        private const val WINDOW_SEGMENTS = 60

        fun meters(a: GeoPoint, b: GeoPoint): Double = DrivingTracker.distanceMeters(a.lat, a.lng, b.lat, b.lng)
    }
}
