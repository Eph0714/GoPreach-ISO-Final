package com.emfitsolutions.gopreach.data.repository

import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * "Snap the small [Group Territory] shape to real streets/blocks where
 * possible" — this app has no bundled street/block-level geometry of its
 * own (the only real bundled boundary data is Municipality/Barangay-level
 * administrative polygons — see [TerritoryBoundaryRepository]'s own doc
 * comment — and using those for a Group's own territory was already tried
 * and reverted: it made a 2-record Group's territory look exactly as big
 * as the whole Barangay). This queries OpenStreetMap's public Overpass API
 * live for the real street network actually near a Group's own records, so
 * [TerritoryMapScreen]'s own territory-shape builder can shape its convex
 * hull around genuine street bends/intersections there instead of a purely
 * synthetic polygon — still sized and centered on the Group's own real
 * records, never expanded into an administrative-unit stand-in.
 *
 * Deliberately best-effort: Overpass is a shared public service with real
 * rate limits, and this is cosmetic (a nicer-looking, still-accurate-
 * enough shape), never authoritative data — any failure (timeout, rate
 * limit, offline, malformed response) simply returns `null`, and the
 * caller falls back to the plain point-based shape it already had. A
 * small in-memory cache and a one-request-at-a-time [mutex] keep repeated
 * territory redraws (the same Group's own live Firestore listener firing
 * again, say) from re-hitting the API for the same real-world spot, and
 * from ever running two Overpass requests concurrently from this app.
 */
class OverpassStreetRepository () {
    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, List<Pair<Double, Double>>>()
    private val maxCacheEntries = 60

    /** Every real street ([way] tagged `highway`) node coordinate within
     * [radiusMeters] of ([lat], [lng]) — `null` on any failure (network,
     * timeout, rate limit, malformed response) or an empty result, so a
     * caller can always just fall back to its own points-only shape
     * without special-casing "Overpass said there are truly zero streets
     * here" differently from "Overpass didn't answer." */
    suspend fun nearbyStreetPoints(lat: Double, lng: Double, radiusMeters: Double): List<Pair<Double, Double>>? {
        val key = "${(lat * 2000).roundToInt()},${(lng * 2000).roundToInt()},${radiusMeters.roundToInt()}"
        synchronized(cache) { cache[key] }?.let { return it }
        return mutex.withLock {
            synchronized(cache) { cache[key] }?.let { return@withLock it }
            val result = withTimeoutOrNull(6000) { fetch(lat, lng, radiusMeters) }
            if (result != null) {
                synchronized(cache) {
                    cache[key] = result
                    if (cache.size > maxCacheEntries) cache.remove(cache.keys.first())
                }
            }
            result
        }
    }

    private suspend fun fetch(lat: Double, lng: Double, radiusMeters: Double): List<Pair<Double, Double>>? = withContext(Dispatchers.IO) {
        runCatching {
            val query = "[out:json][timeout:5];way[\"highway\"](around:$radiusMeters,$lat,$lng);out geom;"
            // overpass-api.de itself started rejecting every request with a
            // bare "406 Not Acceptable" (confirmed independently via curl —
            // not a client/query problem, the server rejects even a trivial
            // probe query). This mirror was confirmed returning real results.
            val connection = (URL("https://overpass.openstreetmap.fr/api/interpreter").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 4000
                readTimeout = 5000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            connection.outputStream.use { it.write("data=${URLEncoder.encode(query, "UTF-8")}".toByteArray()) }
            val code = connection.responseCode
            if (code != 200) {
                val errorBody = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                Log.w(TAG, "Overpass returned HTTP $code: ${errorBody?.take(300)}")
                return@runCatching null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val elements = JsonParser.parseString(body).asJsonObject.getAsJsonArray("elements") ?: return@runCatching null
            val points = mutableListOf<Pair<Double, Double>>()
            elements.forEach { element ->
                val geometry = element.asJsonObject.getAsJsonArray("geometry") ?: return@forEach
                geometry.forEach { node ->
                    val obj = node.asJsonObject
                    val nodeLat = obj.get("lat")?.asDouble ?: return@forEach
                    val nodeLng = obj.get("lon")?.asDouble ?: return@forEach
                    points.add(nodeLat to nodeLng)
                }
            }
            points.ifEmpty { null }
        }.onFailure { Log.w(TAG, "Overpass fetch threw", it) }.getOrNull()
    }

    private companion object {
        const val TAG = "OverpassStreetRepo"
    }
}
