package com.emfitsolutions.gopreach.ui.components.map

import com.google.gson.JsonParser

/**
 * Parses a raw GeoJSON `Polygon`/`MultiPolygon` geometry string — exactly
 * what [com.emfitsolutions.gopreach.data.repository.TerritoryBoundaryRepository]
 * hands to Leaflet's `L.geoJSON()` today — into plain (lat, lng) rings, so
 * both the Leaflet fallback and [com.tomtom.sdk.map.display.polygon.PolygonOptions]
 * can be built from the same bundled data without either renderer's types
 * leaking into this shared parsing code. Only outer rings are kept (holes —
 * a ring after the first in a `Polygon`'s `coordinates` — are dropped), the
 * same simplification the existing boundary dialogs already make by filling
 * the whole polygon at low opacity rather than punching out exclusions.
 */
object BoundaryGeometry {
    /** One ring per outer boundary, each a list of (lat, lng) pairs, GeoJSON's
     * own [lng, lat] order flipped to the (lat, lng) order every Android/Maps
     * API expects. */
    fun outerRings(geometryJson: String): List<List<Pair<Double, Double>>> = runCatching {
        val element = JsonParser.parseString(geometryJson)
        if (!element.isJsonObject) return@runCatching emptyList()
        val obj = element.asJsonObject
        val coordinates = obj.getAsJsonArray("coordinates") ?: return@runCatching emptyList()
        when (obj.get("type")?.asString) {
            "Polygon" -> listOfNotNull(outerRingOf(coordinates))
            "MultiPolygon" -> coordinates.mapNotNull { polygon -> outerRingOf(polygon.asJsonArray) }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    private fun outerRingOf(polygonCoordinates: com.google.gson.JsonArray): List<Pair<Double, Double>>? {
        val outer = polygonCoordinates.firstOrNull()?.asJsonArray ?: return null
        return outer.map { point ->
            val pair = point.asJsonArray
            val lng = pair[0].asDouble
            val lat = pair[1].asDouble
            lat to lng
        }
    }

    /** "Make the text outside the selected barangay less opacity, so focus
     * stays on the selected barangay" — whether (lat, lng) actually falls
     * inside one of [rings], so callers can dim everything else (a landmark,
     * street, or area label fetched from a padded bounding box around the
     * boundary, not the boundary itself) instead of drawing it at full
     * strength. Standard even-odd ray casting, checked against every ring
     * (a multipolygon barangay is "inside" if the point is inside any one
     * of its parts) — holes are already dropped in [outerRings], so this
     * only ever sees outer rings. */
    fun containsPoint(rings: List<List<Pair<Double, Double>>>, lat: Double, lng: Double): Boolean =
        rings.any { ring -> ringContains(ring, lat, lng) }

    private fun ringContains(ring: List<Pair<Double, Double>>, lat: Double, lng: Double): Boolean {
        if (ring.size < 3) return false
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val (lat1, lng1) = ring[i]
            val (lat2, lng2) = ring[j]
            if ((lng1 > lng) != (lng2 > lng)) {
                val latAtLng = lat1 + (lng - lng1) / (lng2 - lng1) * (lat2 - lat1)
                if (lat < latAtLng) inside = !inside
            }
            j = i
        }
        return inside
    }
}
