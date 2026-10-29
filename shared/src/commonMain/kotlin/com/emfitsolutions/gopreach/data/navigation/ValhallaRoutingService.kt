package com.emfitsolutions.gopreach.data.navigation

import com.emfitsolutions.gopreach.domain.map.GeoPoint
import com.emfitsolutions.gopreach.domain.navigation.DrivingTracker
import com.emfitsolutions.gopreach.domain.navigation.Maneuver
import com.emfitsolutions.gopreach.domain.navigation.MatchedFix
import com.emfitsolutions.gopreach.domain.navigation.TrailPoint
import com.emfitsolutions.gopreach.domain.navigation.Route
import com.emfitsolutions.gopreach.domain.navigation.RouteStep
import com.emfitsolutions.gopreach.domain.navigation.RoutingException
import com.emfitsolutions.gopreach.domain.navigation.RoutingFailure
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException

/** Where a route comes from. An interface so the screen and the tests never depend on a particular provider. */
interface RoutingService {
    suspend fun route(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, walking: Boolean = false): Route

    /**
     * The best route first, then up to [alternatives] different ones when the provider finds them. A provider without alternatives returns just the one route.
     * Nothing here is invented: every route, distance and time is what the provider answered.
     */
    suspend fun routes(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, alternatives: Int = 2, walking: Boolean = false): List<Route> = listOf(route(fromLat, fromLng, toLat, toLng, walking))

    /** The road point (and the road's direction) at the end of a recent GPS trail, or null when unknown or unavailable. Best effort: it never throws. */
    suspend fun matchTrail(trail: List<TrailPoint>, walking: Boolean = false): MatchedFix? = null
}

/**
 * Turn-by-turn routes from a Valhalla server (open-source routing on OpenStreetMap data), which also writes the spoken instructions.
 * The default [baseUrl] is the public community instance run by FOSSGIS: it needs no key but is a shared, fair-use service with no
 * guaranteed availability - for production, point [baseUrl] at your own Valhalla server or a hosted provider.
 */
class ValhallaRoutingService(
    private val client: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val timeoutMillis: Long = 20_000L,
    /** Tried when [baseUrl] is down, busy or unreachable (not when it says there is no route); null for none. */
    private val fallbackBaseUrl: String? = null,
) : RoutingService {
    override suspend fun route(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, walking: Boolean): Route = routes(fromLat, fromLng, toLat, toLng, alternatives = 0, walking = walking).first()

    override suspend fun routes(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, alternatives: Int, walking: Boolean): List<Route> {
        val fallback = fallbackBaseUrl?.takeIf { it.trimEnd('/') != baseUrl.trimEnd('/') }
        return try {
            routesFrom(baseUrl, fromLat, fromLng, toLat, toLng, alternatives, walking)
        } catch (e: RoutingException) {
            if (fallback == null || e.failure == RoutingFailure.NO_ROUTE) throw e
            routesFrom(fallback, fromLat, fromLng, toLat, toLng, alternatives, walking)
        }
    }

    private suspend fun routesFrom(baseUrl: String, fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, alternatives: Int, walking: Boolean): List<Route> {
        val alt = if (alternatives > 0) ""","alternates":$alternatives""" else ""
        val body = """{"locations":[{"lat":$fromLat,"lon":$fromLng},{"lat":$toLat,"lon":$toLng}],"costing":"${if (walking) "pedestrian" else "auto"}","directions_options":{"units":"kilometers","language":"en-US","directions_type":"instructions"}$alt}"""
        val text = try {
            withTimeoutUnlessZero(timeoutMillis) {
                val response = client.post(baseUrl.trimEnd('/') + "/route") { contentType(ContentType.Application.Json); setBody(body) }
                val code = response.status.value
                val payload = response.bodyAsText()
                when {
                    code == 429 -> throw RoutingException(RoutingFailure.RATE_LIMITED, "The routing service is busy. Try again in a moment.")
                    code in 500..599 -> throw RoutingException(RoutingFailure.SERVER, "The routing service is unavailable right now.")
                    code == 400 || code == 404 -> throw RoutingException(RoutingFailure.NO_ROUTE, "No ${if (walking) "walking" else "driving"} route was found to this place.")
                    code !in 200..299 -> throw RoutingException(RoutingFailure.SERVER, "The routing service answered with an error ($code).")
                }
                payload
            }
        } catch (e: RoutingException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            throw RoutingException(RoutingFailure.TIMEOUT, "The routing service took too long to answer.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RoutingException(RoutingFailure.OFFLINE, "Could not reach the routing service. Check your internet connection.", e)
        }
        return parseAll(text)
    }

    override suspend fun matchTrail(trail: List<TrailPoint>, walking: Boolean): MatchedFix? {
        if (trail.size < 2) return null
        val shape = trail.joinToString(",") { "{\"lat\":${it.lat},\"lon\":${it.lng}}" }
        val costing = if (walking) "pedestrian" else "auto"
        val body = "{\"shape\":[$shape],\"costing\":\"$costing\",\"shape_match\":\"map_snap\",\"filters\":{\"attributes\":[\"matched.point\",\"matched.type\",\"matched.distance_from_trace_point\"],\"action\":\"include\"}}"
        for (base in listOfNotNull(baseUrl, fallbackBaseUrl?.takeIf { it.trimEnd('/') != baseUrl.trimEnd('/') })) {
            val text = try {
                withTimeoutUnlessZero(if (timeoutMillis > 0) MATCH_TIMEOUT_MILLIS else 0L) {
                    val response = client.post(base.trimEnd('/') + "/trace_attributes") { contentType(ContentType.Application.Json); setBody(body) }
                    if (response.status.value in 200..299) response.bodyAsText() else null
                }
            } catch (e: TimeoutCancellationException) {
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (text != null) return parseMatch(text)
        }
        return null
    }

    /** A timeout of 0 means none (tests run on a virtual clock that would fire any timeout at once). */
    private suspend fun <T> withTimeoutUnlessZero(millis: Long, block: suspend () -> T): T = if (millis <= 0L) block() else withTimeout(millis) { block() }

    companion object {
        const val DEFAULT_BASE_URL = "https://valhalla1.openstreetmap.de"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private const val MATCH_TIMEOUT_MILLIS = 4_000L

        /** The last matched point of a trace_attributes answer and the direction of the road there (from the last matched point at least 3 m before it); null when nothing matched. */
        fun parseMatch(text: String): MatchedFix? {
            val points = try {
                json.parseToJsonElement(text).jsonObject["matched_points"]?.jsonArray
            } catch (e: Exception) {
                null
            } ?: return null
            val matched = points.mapNotNull { p ->
                val o = p as? JsonObject ?: return@mapNotNull null
                if (o["type"]?.jsonPrimitive?.content != "matched") return@mapNotNull null
                val lat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lon = o["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                lat to lon
            }
            val end = matched.lastOrNull() ?: return null
            val before = matched.dropLast(1).lastOrNull { DrivingTracker.distanceMeters(it.first, it.second, end.first, end.second) >= 3.0 }
            return MatchedFix(end.first, end.second, before?.let { DrivingTracker.bearingBetween(it.first, it.second, end.first, end.second) })
        }

        fun parse(text: String): Route = parseAll(text).first()

        /** The main route, then each alternative the server sent (an alternative it could not read is left out, never guessed). */
        fun parseAll(text: String): List<Route> {
            val dto = try { json.decodeFromString(ValhallaResponse.serializer(), text) } catch (e: Exception) {
                throw RoutingException(RoutingFailure.BAD_RESPONSE, "The routing service sent a route this app could not read.", e)
            }
            val main = routeOf(dto.trip)
            val alternatives = dto.alternates.orEmpty().mapNotNull { runCatching { routeOf(it.trip) }.getOrNull() }
            return listOf(main) + alternatives
        }

        private fun routeOf(trip: ValhallaTrip?): Route {
            val leg = trip?.legs?.firstOrNull() ?: throw RoutingException(RoutingFailure.NO_ROUTE, "No driving route was found to this place.")
            val points = decodePolyline6(leg.shape ?: "")
            if (points.size < 2) throw RoutingException(RoutingFailure.NO_ROUTE, "No driving route was found to this place.")
            val steps = leg.maneuvers.orEmpty().map { m ->
                RouteStep(
                    maneuver = maneuverOf(m.type),
                    instruction = m.instruction.orEmpty(),
                    alert = m.alert, pre = m.pre, post = m.post,
                    street = m.streetNames?.firstOrNull(),
                    lengthMeters = (m.length ?: 0.0) * 1000.0,
                    durationSeconds = m.time ?: 0.0,
                    beginIndex = (m.begin ?: 0).coerceIn(0, points.lastIndex),
                    endIndex = (m.end ?: 0).coerceIn(0, points.lastIndex),
                )
            }
            if (steps.isEmpty()) throw RoutingException(RoutingFailure.BAD_RESPONSE, "The route had no driving instructions.")
            val summary = trip?.summary
            return Route(points, steps, (summary?.length ?: steps.sumOf { it.lengthMeters / 1000.0 }) * 1000.0, summary?.time ?: steps.sumOf { it.durationSeconds })
        }

        /** Valhalla's manoeuvre numbers, folded into the handful of icons the screen has. */
        internal fun maneuverOf(type: Int?): Maneuver = when (type) {
            1, 2, 3 -> Maneuver.START
            4, 5, 6 -> Maneuver.DESTINATION
            9 -> Maneuver.SLIGHT_RIGHT
            10 -> Maneuver.RIGHT
            11 -> Maneuver.SHARP_RIGHT
            12 -> Maneuver.UTURN_RIGHT
            13 -> Maneuver.UTURN_LEFT
            14 -> Maneuver.SHARP_LEFT
            15 -> Maneuver.LEFT
            16 -> Maneuver.SLIGHT_LEFT
            17, 18, 19 -> Maneuver.RAMP
            20, 21 -> Maneuver.EXIT
            25, 37, 38 -> Maneuver.MERGE
            26, 27 -> Maneuver.ROUNDABOUT
            else -> Maneuver.STRAIGHT // continue, becomes, stay straight/left/right
        }

        /** Google's encoded-polyline format at the 6-decimal precision Valhalla uses. */
        internal fun decodePolyline6(encoded: String): List<GeoPoint> {
            val out = ArrayList<GeoPoint>()
            var index = 0
            var lat = 0
            var lng = 0
            while (index < encoded.length) {
                var result = 0
                var shift = 0
                var b: Int
                do { b = encoded[index++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20 && index < encoded.length)
                lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
                result = 0; shift = 0
                if (index >= encoded.length) break
                do { b = encoded[index++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20 && index < encoded.length)
                lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
                out += GeoPoint(lat / 1e6, lng / 1e6)
            }
            return out
        }
    }
}

@Serializable
private data class ValhallaResponse(val trip: ValhallaTrip? = null, val alternates: List<ValhallaAlternate>? = null)

@Serializable
private data class ValhallaAlternate(val trip: ValhallaTrip? = null)

@Serializable
private data class ValhallaTrip(val legs: List<ValhallaLeg>? = null, val summary: ValhallaSummary? = null)

@Serializable
private data class ValhallaSummary(val length: Double? = null, val time: Double? = null)

@Serializable
private data class ValhallaLeg(val shape: String? = null, val maneuvers: List<ValhallaManeuver>? = null)

@Serializable
private data class ValhallaManeuver(
    val type: Int? = null,
    val instruction: String? = null,
    @SerialName("verbal_transition_alert_instruction") val alert: String? = null,
    @SerialName("verbal_pre_transition_instruction") val pre: String? = null,
    @SerialName("verbal_post_transition_instruction") val post: String? = null,
    @SerialName("street_names") val streetNames: List<String>? = null,
    val length: Double? = null,
    val time: Double? = null,
    @SerialName("begin_shape_index") val begin: Int? = null,
    @SerialName("end_shape_index") val end: Int? = null,
)
