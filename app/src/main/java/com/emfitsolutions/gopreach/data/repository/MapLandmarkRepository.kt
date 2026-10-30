package com.emfitsolutions.gopreach.data.repository

import android.util.Log
import com.emfitsolutions.gopreach.data.remote.SyncApi
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** One named place of the Territory Map's landmark layer (church, school, clinic, bridge, river, ...). The same record the website draws (`GET /v1/map/landmarks`). */
data class MapLandmark(
    val id: String,
    val name: String,
    val cat: String,
    /** 1 = shown from zoom 12, 2 = from zoom 14, 3 = from zoom 16. */
    val tier: Int = 2,
    val lat: Double,
    val lng: Double,
    val address: String? = null,
    val phone: String? = null,
    val website: String? = null,
    val hours: String? = null,
)

private class LandmarkReply(val landmarks: List<MapLandmark>, val partial: Boolean)

/**
 * The landmarks around the part of the map on screen. The server answers from whole zoom-13 tiles (about 5 km) that it keeps for a week; this class keeps what it
 * was told so panning back, zooming or rotating never asks again: a tile that was answered completely is not requested twice in this session.
 */
class MapLandmarkRepository(private val syncApi: SyncApi) {
    private val mutex = Mutex()
    private val loadedTiles = HashSet<Long>()
    private val byId = LinkedHashMap<String, MapLandmark>()

    /** Makes sure the tiles under the box are loaded, then returns every known landmark inside it (at most [limit]; the most important first). */
    suspend fun inBounds(south: Double, west: Double, north: Double, east: Double, limit: Int = 1200): List<MapLandmark> {
        if (north - south > MAX_SPAN_DEG || east - west > MAX_SPAN_DEG) return emptyList()
        mutex.withLock {
            val x0 = tileX(west); val x1 = tileX(east); val y0 = tileY(north); val y1 = tileY(south)
            val missing = ArrayList<Pair<Int, Int>>()
            for (x in x0..x1) for (y in y0..y1) if (key(x, y) !in loadedTiles) missing += x to y
            if (missing.isNotEmpty() && missing.size <= MAX_TILES) load(missing)
        }
        return mutex.withLock {
            byId.values.filter { it.lat in south..north && it.lng in west..east }.sortedBy { it.tier }.take(limit)
        }
    }

    private suspend fun load(missing: List<Pair<Int, Int>>) {
        val s = latOf(missing.maxOf { it.second } + 1); val n = latOf(missing.minOf { it.second })
        val w = lngOf(missing.minOf { it.first }); val e = lngOf(missing.maxOf { it.first } + 1)
        val reply = try {
            syncApi.callServer("GET", "/v1/map/landmarks", mapOf("s" to "$s", "w" to "$w", "n" to "$n", "e" to "$e"))
        } catch (ex: kotlin.coroutines.cancellation.CancellationException) {
            throw ex
        } catch (ex: Exception) {
            Log.w(TAG, "Landmarks not loaded: ${ex.message}")
            return
        }
        if (!reply.ok) { Log.w(TAG, "Landmarks: the server answered ${reply.status}"); return }
        val parsed = runCatching { parse(reply.text) }.getOrNull() ?: return
        parsed.landmarks.forEach { byId[it.id] = it }
        // A partial answer (a tile the server could not get) is asked for again the next time the map stops there.
        if (!parsed.partial) missing.forEach { loadedTiles += key(it.first, it.second) }
        while (byId.size > MAX_KEPT) byId.remove(byId.keys.first())
    }

    private fun parse(text: String): LandmarkReply {
        val root = Json.parseToJsonElement(text).jsonObject
        fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        val list = (root["landmarks"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            MapLandmark(
                id = o.str("id") ?: return@mapNotNull null, name = o.str("name") ?: return@mapNotNull null, cat = o.str("cat") ?: "landmark",
                tier = (o["tier"] as? JsonPrimitive)?.intOrNull ?: 2,
                lat = (o["lat"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null,
                lng = (o["lng"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null,
                address = o.str("address"), phone = o.str("phone"), website = o.str("website"), hours = o.str("hours"),
            )
        }
        return LandmarkReply(list, (root["partial"] as? JsonPrimitive)?.booleanOrNull ?: false)
    }

    private fun key(x: Int, y: Int) = (x.toLong() shl 32) or y.toLong()
    private fun tileX(lng: Double) = floor((lng + 180.0) / 360.0 * N).toInt()
    private fun tileY(lat: Double): Int {
        val r = Math.toRadians(lat)
        return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * N).toInt()
    }
    private fun lngOf(x: Int) = x / N * 360.0 - 180.0
    private fun latOf(y: Int) = Math.toDegrees(Math.atan(Math.sinh(PI * (1 - 2.0 * y / N))))

    private companion object {
        const val TAG = "MapLandmarks"
        const val N = 8192.0 // 2^13, the server's tile grid
        const val MAX_TILES = 16
        const val MAX_KEPT = 6000
        const val MAX_SPAN_DEG = 0.25
    }
}
