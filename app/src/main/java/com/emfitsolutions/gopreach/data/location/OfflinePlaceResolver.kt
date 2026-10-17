package com.emfitsolutions.gopreach.data.location

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Works out Province / Municipality / Barangay from a GPS point with no internet, by testing the point against the real
 * municipality and barangay boundaries bundled in `assets/territory/territory_boundaries.json` (see
 * [com.emfitsolutions.gopreach.data.repository.TerritoryBoundaryRepository] for where that data comes from). The bundled
 * file only covers Nueva Vizcaya, so anywhere else this returns null and the address stays manual until the phone is online.
 */
object OfflinePlaceResolver {
    /** The one province the bundled boundary file was built for. */
    private const val PROVINCE = "Nueva Vizcaya"

    private class Area(val key: String, val rings: List<List<DoubleArray>>, val minLng: Double, val maxLng: Double, val minLat: Double, val maxLat: Double)

    private var municipalities: List<Area>? = null
    private var barangays: List<Area>? = null

    private fun load(context: Context) {
        if (municipalities != null) return
        try {
            val root = context.assets.open("territory/territory_boundaries.json").use { JsonParser.parseReader(it.bufferedReader()).asJsonObject }
            municipalities = areas(root.getAsJsonObject("municipalities"))
            barangays = areas(root.getAsJsonObject("barangays"))
        } catch (e: Exception) {
            municipalities = emptyList()
            barangays = emptyList()
        }
    }

    private fun areas(obj: com.google.gson.JsonObject): List<Area> = obj.entrySet().mapNotNull { (key, geometry) ->
        if (geometry.isJsonNull) return@mapNotNull null
        val rings = rings(geometry)
        if (rings.isEmpty()) return@mapNotNull null
        val all = rings.flatten()
        Area(key, rings, all.minOf { it[0] }, all.maxOf { it[0] }, all.minOf { it[1] }, all.maxOf { it[1] })
    }

    /** Every ring of a Polygon / MultiPolygon as [lng, lat] points (outer rings and holes alike; see [contains]). */
    private fun rings(geometry: JsonElement): List<List<DoubleArray>> {
        val g = geometry.asJsonObject
        val coords = g.getAsJsonArray("coordinates") ?: return emptyList()
        val polygons: List<JsonArray> = when (g.get("type").asString) {
            "Polygon" -> listOf(coords)
            "MultiPolygon" -> coords.map { it.asJsonArray }
            else -> emptyList()
        }
        return polygons.flatMap { polygon -> polygon.map { ring -> ring.asJsonArray.map { p -> doubleArrayOf(p.asJsonArray[0].asDouble, p.asJsonArray[1].asDouble) } } }
    }

    /** Even-odd ray casting over all rings, so a hole inside a polygon correctly counts as outside it. */
    private fun Area.contains(lng: Double, lat: Double): Boolean {
        if (lng < minLng || lng > maxLng || lat < minLat || lat > maxLat) return false
        var inside = false
        for (ring in rings) {
            var j = ring.size - 1
            for (i in ring.indices) {
                val (xi, yi) = ring[i][0] to ring[i][1]
                val (xj, yj) = ring[j][0] to ring[j][1]
                if ((yi > lat) != (yj > lat) && lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
        }
        return inside
    }

    /** Boundary names are stored upper-case and abbreviated ("STA CRUZ", "DISTRICT III POB"); the PSGC list spells them out. */
    private fun display(name: String, stripPob: Boolean): String {
        var n = name.trim()
        if (stripPob) n = n.removeSuffix(" POB").trim()
        n = n.replace(Regex("\\bSTA\\b"), "SANTA").replace(Regex("\\bSTO\\b"), "SANTO")
        return n.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    /** The address the point falls in, or null when it is outside the bundled boundaries. */
    suspend fun resolve(context: Context, lat: Double, lng: Double): GeocodedAddress? = withContext(Dispatchers.Default) {
        load(context)
        val municipality = municipalities.orEmpty().firstOrNull { it.contains(lng, lat) } ?: return@withContext null
        val barangay = barangays.orEmpty().firstOrNull { it.key.startsWith(municipality.key + "|") && it.contains(lng, lat) }
        GeocodedAddress(
            barangay = barangay?.let { display(it.key.substringAfter('|'), stripPob = true) },
            cityMunicipality = display(municipality.key, stripPob = false),
            province = PROVINCE,
        )
    }
}
