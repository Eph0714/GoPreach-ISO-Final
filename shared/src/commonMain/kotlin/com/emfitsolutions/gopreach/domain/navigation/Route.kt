package com.emfitsolutions.gopreach.domain.navigation

import com.emfitsolutions.gopreach.domain.map.GeoPoint
import kotlin.math.roundToLong

/** The kind of manoeuvre at the start of a step; drives the icon. */
enum class Maneuver { START, STRAIGHT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, UTURN_LEFT, UTURN_RIGHT, RAMP, EXIT, MERGE, ROUNDABOUT, DESTINATION }

/**
 * One manoeuvre and the stretch of road after it. The manoeuvre happens at [beginIndex] of the route's points; [lengthMeters] is the distance
 * driven before the next one. [alert], [pre] and [post] are the spoken texts (said ahead of the turn, right at it, and after it).
 */
data class RouteStep(
    val maneuver: Maneuver,
    val instruction: String,
    val alert: String?,
    val pre: String?,
    val post: String?,
    val street: String?,
    val lengthMeters: Double,
    val durationSeconds: Double,
    val beginIndex: Int,
    val endIndex: Int,
)

data class Route(
    val points: List<GeoPoint>,
    val steps: List<RouteStep>,
    val distanceMeters: Double,
    val durationSeconds: Double,
)

enum class RoutingFailure { OFFLINE, TIMEOUT, NO_ROUTE, RATE_LIMITED, SERVER, BAD_RESPONSE }

class RoutingException(val failure: RoutingFailure, message: String, cause: Throwable? = null) : Exception(message, cause)

object RouteFormat {
    private fun roundTo(m: Double, step: Int): Int = ((m / step).roundToLong() * step).toInt()

    /** "30 m", "350 m", "1.2 km", "12 km" - rounded the way a driver reads it. */
    fun distance(meters: Double): String {
        val m = meters.coerceAtLeast(0.0)
        return when {
            m < 100 -> "${roundTo(m, 10)} m"
            m < 1000 -> "${roundTo(m, 50)} m"
            m < 10_000 -> "${(m / 100).roundToLong() / 10.0} km"
            else -> "${(m / 1000).roundToLong()} km"
        }
    }

    /** The distance as it is spoken: "200 meters", "1.2 kilometers". */
    fun spokenDistance(meters: Double): String {
        val m = meters.coerceAtLeast(0.0)
        return when {
            m < 100 -> "${maxOf(10, roundTo(m, 10))} meters"
            m < 1000 -> "${roundTo(m, 50)} meters"
            else -> "${(m / 100).roundToLong() / 10.0} kilometers"
        }
    }

    fun duration(seconds: Double): String {
        val min = (seconds / 60.0).roundToLong().toInt()
        return when {
            min < 1 -> "<1 min"
            min < 60 -> "$min min"
            else -> "${min / 60} h ${min % 60} min"
        }
    }
}

/** One route the driver can pick, with a label that is only ever drawn from the real numbers of the routes offered. */
data class RouteChoice(val route: Route, val label: String)

object RouteChoices {
    /**
     * The routes the provider returned, quickest first. "Fastest" goes to the least travel time and "Shortest" to the least distance (only when
     * they are different routes); everything else is "Alternative". One lone route is just "Route" - it is never called fastest or shortest
     * when there was nothing to compare it with.
     */
    fun label(routes: List<Route>): List<RouteChoice> {
        if (routes.size <= 1) return routes.map { RouteChoice(it, "Route") }
        val byTime = routes.sortedBy { it.durationSeconds }
        val fastest = byTime.first()
        val shortest = routes.minByOrNull { it.distanceMeters }
        return byTime.map { r ->
            val label = when {
                r === fastest && r === shortest -> "Fastest and shortest"
                r === fastest -> "Fastest"
                r === shortest -> "Shortest"
                else -> "Alternative"
            }
            RouteChoice(r, label)
        }
    }
}
