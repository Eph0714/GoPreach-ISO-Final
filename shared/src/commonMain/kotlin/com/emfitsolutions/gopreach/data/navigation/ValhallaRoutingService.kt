package com.emfitsolutions.gopreach.data.navigation

import com.emfitsolutions.gopreach.domain.map.GeoPoint
import com.emfitsolutions.gopreach.domain.navigation.Maneuver
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
import kotlin.coroutines.cancellation.CancellationException

/** Where a route comes from. An interface so the screen and the tests never depend on a particular provider. */
interface RoutingService {
    suspend fun route(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Route
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
) : RoutingService {
    override suspend fun route(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Route {
        val body = """{"locations":[{"lat":$fromLat,"lon":$fromLng},{"lat":$toLat,"lon":$toLng}],"costing":"auto","directions_options":{"units":"kilometers","language":"en-US","directions_type":"instructions"}}"""
        val text = try {
            withTimeout(timeoutMillis) {
                val response = client.post(baseUrl.trimEnd('/') + "/route") { contentType(ContentType.Application.Json); setBody(body) }
                val code = response.status.value
                val payload = response.bodyAsText()
                when {
                    code == 429 -> throw RoutingException(RoutingFailure.RATE_LIMITED, "The routing service is busy. Try again in a moment.")
                    code in 500..599 -> throw RoutingException(RoutingFailure.SERVER, "The routing service is unavailable right now.")
                    code == 400 || code == 404 -> throw RoutingException(RoutingFailure.NO_ROUTE, "No driving route was found to this place.")
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
        return parse(text)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://valhalla1.openstreetmap.de"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(text: String): Route {
            val dto = try { json.decodeFromString(ValhallaResponse.serializer(), text) } catch (e: Exception) {
                throw RoutingException(RoutingFailure.BAD_RESPONSE, "The routing service sent a route this app could not read.", e)
            }
            val leg = dto.trip?.legs?.firstOrNull() ?: throw RoutingException(RoutingFailure.NO_ROUTE, "No driving route was found to this place.")
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
            val summary = dto.trip?.summary
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
private data class ValhallaResponse(val trip: ValhallaTrip? = null)

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
