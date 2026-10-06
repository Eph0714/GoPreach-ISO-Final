package com.emfitsolutions.gopreach.data.repository

import android.util.Log
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Which broad [MapLayer][com.emfitsolutions.gopreach.ui.components.map.MapLayer]
 * a [LandmarkCategory] belongs to — Kingdom Halls, generic churches, and gas
 * stations each need their own independently checkable filter entry, and
 * every other category is a plain "Landmarks" entry for that same filter. */
enum class LandmarkGroup { KINGDOM_HALL, CHURCH, GASOLINE, LANDMARK }

/** What kind of place a [Landmark] is — drives which icon/color the map
 * draws for it (a school pin looks nothing like a police station pin) so
 * the map reads as real, categorized places rather than identical dots. */
enum class LandmarkCategory(val emoji: String, val colorArgb: Int, val group: LandmarkGroup) {
    KINGDOM_HALL("📖", 0xFF283593.toInt(), LandmarkGroup.KINGDOM_HALL),
    SCHOOL("🏫", 0xFF7B1FA2.toInt(), LandmarkGroup.LANDMARK),
    WORSHIP("⛪", 0xFF6D4C41.toInt(), LandmarkGroup.CHURCH),
    HEALTH("🏥", 0xFFD32F2F.toInt(), LandmarkGroup.LANDMARK),
    PHARMACY("💊", 0xFF00897B.toInt(), LandmarkGroup.LANDMARK),
    MARKET("🛒", 0xFFF57C00.toInt(), LandmarkGroup.LANDMARK),
    SUPERMARKET("🏪", 0xFF2E7D32.toInt(), LandmarkGroup.LANDMARK),
    SHOP("🏬", 0xFFEF6C00.toInt(), LandmarkGroup.LANDMARK),
    GOVERNMENT("🏛", 0xFF455A64.toInt(), LandmarkGroup.LANDMARK),
    POLICE("🚓", 0xFF1565C0.toInt(), LandmarkGroup.LANDMARK),
    FIRE("🚒", 0xFFC62828.toInt(), LandmarkGroup.LANDMARK),
    FUEL("⛽", 0xFFE65100.toInt(), LandmarkGroup.GASOLINE),
    BANK("🏦", 0xFF0277BD.toInt(), LandmarkGroup.LANDMARK),
    RESTAURANT("🍽", 0xFFD84315.toInt(), LandmarkGroup.LANDMARK),
    HOTEL("🏨", 0xFF8E24AA.toInt(), LandmarkGroup.LANDMARK),
    TOURISM("📷", 0xFF2E7D32.toInt(), LandmarkGroup.LANDMARK),
    OTHER("📍", 0xFF616161.toInt(), LandmarkGroup.LANDMARK),
}

/** One named point of interest within a boundary's bounding box — a real
 * landmark (school, church, market, clinic, shop, ...) rather than a
 * synthetic label, so "the map" reads as an actual place and not just a
 * satellite photo or a bare polygon. [category] is derived from whichever
 * OSM tag actually matched in [OverpassLandmarkRepository.classify]. */
data class Landmark(val name: String, val lat: Double, val lng: Double, val category: LandmarkCategory)

/** One real road, as the ordered (lat, lng) points tracing its actual path —
 * drawn as its own line, not flattened into a point cloud like
 * [OverpassStreetRepository.nearbyStreetPoints] does for its own (different)
 * convex-hull-shape use case. [name] is the road's own OSM "name" tag
 * ("Maharlika Highway", "Burgos St.", ...) so the map can label it instead
 * of just drawing an anonymous line — null for unnamed ways (alleys,
 * driveways, ...), which still draw, just unlabeled. */
data class StreetSegment(val points: List<Pair<Double, Double>>, val name: String? = null)

/** A labeled stretch of land — a real OSM name if the area has one, else a
 * plain description of what it actually is ("Rice Field", "Forest", ...)
 * derived from its own `landuse`/`natural` tag, placed at the area's own
 * centroid. Lets the map describe what's actually on the ground (the
 * farmland/ricefields this app's rural provinces are mostly made of)
 * instead of only ever labeling roads and named businesses. */
data class AreaFeature(val name: String, val lat: Double, val lng: Double)

/** One real building footprint — the actual OSM `building` way outline, so
 * zooming into a street shows real house/building shapes along it ("add a
 * drawing of the houses and buildings if zoom in") instead of just roads
 * and named places. Drawn only above a close zoom threshold by the caller
 * — there can be hundreds in a single poblacion barangay, so always
 * drawing them would just clutter every other zoom level. */
data class BuildingFootprint(val points: List<Pair<Double, Double>>)

data class MapDetails(
    val landmarks: List<Landmark>,
    val streets: List<StreetSegment>,
    val areas: List<AreaFeature> = emptyList(),
    val buildings: List<BuildingFootprint> = emptyList(),
)

/**
 * Live, best-effort landmark + street lookup for a boundary's bounding box —
 * same public Overpass (OpenStreetMap) API and same "never block, never
 * error, just come back empty" spirit as [OverpassStreetRepository], the
 * existing precedent for calling this exact service from this app. Exists
 * because the native TomTom map's own vector "Standard"/"Driving" styles
 * have no road, building, or label data at all for the rural provinces this
 * app actually serves — confirmed on-device, and confirmed it isn't just a
 * style choice: both of TomTom's own vector styles (same Map Display API,
 * same key) render the identical blank canvas for the same real boundary.
 * Raw satellite imagery alone shows terrain/rooftops but names and draws
 * nothing — neither gives a Service Overseer/Secretary an actual
 * recognizable street or place to go by, so both are drawn here instead,
 * independent of whatever TomTom's own map data does or doesn't have.
 */
class OverpassLandmarkRepository() {
    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, MapDetails>()
    private val maxCacheEntries = 40

    /** Up to 40 named landmarks (schools, churches, markets, clinics, shops,
     * government offices, notable attractions) and up to 150 real roads
     * inside ([south],[west])-([north],[east]), or both empty on any
     * failure (offline, timeout, rate limit) or if the area genuinely has
     * none mapped — every caller already treats "nothing found" the same
     * as "didn't fetch," so this never needs to distinguish the two. */
    suspend fun detailsIn(south: Double, west: Double, north: Double, east: Double): MapDetails {
        val key = "${(south * 2000).toInt()},${(west * 2000).toInt()},${(north * 2000).toInt()},${(east * 2000).toInt()}"
        synchronized(cache) { cache[key] }?.let { return it }
        return mutex.withLock {
            synchronized(cache) { cache[key] }?.let { return@withLock it }
            val result = withTimeoutOrNull(10000) { fetch(south, west, north, east) } ?: MapDetails(emptyList(), emptyList())
            synchronized(cache) {
                cache[key] = result
                if (cache.size > maxCacheEntries) cache.remove(cache.keys.first())
            }
            result
        }
    }

    private suspend fun fetch(south: Double, west: Double, north: Double, east: Double): MapDetails =
        withContext(Dispatchers.IO) {
            runCatching {
                val bbox = "$south,$west,$north,$east"
                // Landmarks (named nodes) and streets (highway ways) in one
                // round trip — cheaper than two separate requests against the
                // same bounding box, and this public service is already
                // best shared sparingly.
                val query = "[out:json][timeout:20];(" +
                    "node[\"name\"][\"amenity\"~\"^(school|place_of_worship|hospital|clinic|pharmacy|marketplace|townhall|police|fire_station|fuel|bank|restaurant|fast_food)$\"]($bbox);" +
                    "node[\"name\"][\"shop\"]($bbox);" +
                    "node[\"name\"][\"tourism\"~\"^(attraction|museum|viewpoint|hotel)$\"]($bbox);" +
                    "node[\"name\"][\"office\"=\"government\"]($bbox);" +
                    ");out body 60;" +
                    "way[\"highway\"]($bbox);out geom 150;" +
                    // What's actually on the ground — farmland (ricefields in
                    // this app's own rural provinces), orchards, forest,
                    // water — so the map can describe land, not just roads
                    // and named businesses.
                    "way[\"landuse\"~\"^(farmland|orchard|forest|meadow|vineyard|aquaculture)$\"]($bbox);out geom 60;" +
                    "way[\"natural\"~\"^(wood|water)$\"]($bbox);out geom 30;" +
                    // Real building footprints — "add a drawing of the houses
                    // and buildings if zoom in" — capped well below streets/
                    // landmarks since a single poblacion barangay can have
                    // hundreds; the caller only draws these above a close
                    // zoom threshold anyway.
                    "way[\"building\"]($bbox);out geom 400;"
                // overpass-api.de itself started rejecting every request with
                // a bare "406 Not Acceptable" (confirmed both from this app
                // on-device and independently via curl — not a client/query
                // problem, the server rejects even a trivial [out:json];node(1);out;
                // probe). This mirror was confirmed returning real results.
                val connection = (URL(OVERPASS_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 4000
                    readTimeout = 10000
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
                connection.outputStream.use { it.write("data=${URLEncoder.encode(query, "UTF-8")}".toByteArray()) }
                val code = connection.responseCode
                Log.i(TAG, "Overpass query HTTP $code for bbox $bbox")
                if (code != 200) {
                    val errorBody = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                    Log.w(TAG, "Overpass query returned HTTP $code: ${errorBody?.take(300)}")
                    return@runCatching MapDetails(emptyList(), emptyList())
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val elements = JsonParser.parseString(body).asJsonObject.getAsJsonArray("elements") ?: return@runCatching MapDetails(emptyList(), emptyList())
                val landmarks = elements.mapNotNull { el ->
                    val obj = el.asJsonObject
                    if (obj.get("type")?.asString != "node") return@mapNotNull null
                    val lat = obj.get("lat")?.asDouble ?: return@mapNotNull null
                    val lon = obj.get("lon")?.asDouble ?: return@mapNotNull null
                    val tags = obj.getAsJsonObject("tags") ?: return@mapNotNull null
                    val name = tags.get("name")?.asString ?: return@mapNotNull null
                    Landmark(name, lat, lon, classify(tags))
                }
                val ways = elements.filter { it.asJsonObject.get("type")?.asString == "way" }
                val streets = ways.mapNotNull { el ->
                    val obj = el.asJsonObject
                    val tags = obj.getAsJsonObject("tags")
                    if (tags?.has("highway") != true) return@mapNotNull null
                    val geometry = obj.getAsJsonArray("geometry") ?: return@mapNotNull null
                    val points = geometry.mapNotNull { g ->
                        val point = g.asJsonObject
                        val lat = point.get("lat")?.asDouble ?: return@mapNotNull null
                        val lon = point.get("lon")?.asDouble ?: return@mapNotNull null
                        lat to lon
                    }
                    val name = tags.get("name")?.asString
                    if (points.size >= 2) StreetSegment(points, name) else null
                }
                val rawAreas = ways.mapNotNull { el ->
                    val obj = el.asJsonObject
                    val tags = obj.getAsJsonObject("tags") ?: return@mapNotNull null
                    if (tags.has("highway") || tags.has("building")) return@mapNotNull null
                    val name = areaFeatureName(tags) ?: return@mapNotNull null
                    val geometry = obj.getAsJsonArray("geometry") ?: return@mapNotNull null
                    val points = geometry.mapNotNull { g ->
                        val point = g.asJsonObject
                        val lat = point.get("lat")?.asDouble ?: return@mapNotNull null
                        val lon = point.get("lon")?.asDouble ?: return@mapNotNull null
                        lat to lon
                    }
                    if (points.isEmpty()) return@mapNotNull null
                    AreaFeature(name, points.map { it.first }.average(), points.map { it.second }.average())
                }
                val buildings = ways.mapNotNull { el ->
                    val obj = el.asJsonObject
                    val tags = obj.getAsJsonObject("tags") ?: return@mapNotNull null
                    if (!tags.has("building")) return@mapNotNull null
                    val geometry = obj.getAsJsonArray("geometry") ?: return@mapNotNull null
                    val points = geometry.mapNotNull { g ->
                        val point = g.asJsonObject
                        val lat = point.get("lat")?.asDouble ?: return@mapNotNull null
                        val lon = point.get("lon")?.asDouble ?: return@mapNotNull null
                        lat to lon
                    }
                    if (points.size >= 3) BuildingFootprint(points) else null
                }
                // A river/pond is commonly split across several adjacent OSM
                // ways that each carry the same `natural=water` tag — left
                // as-is, that drew 2-3 "Water" labels stacked directly on
                // top of each other (confirmed on-device). Collapsing any
                // same-named area within ~200m of one already kept turns
                // that into the single label it should have been.
                val areas = mutableListOf<AreaFeature>()
                rawAreas.forEach { candidate ->
                    val tooClose = areas.any {
                        it.name == candidate.name &&
                            kotlin.math.abs(it.lat - candidate.lat) < 0.002 &&
                            kotlin.math.abs(it.lng - candidate.lng) < 0.002
                    }
                    if (!tooClose) areas.add(candidate)
                }
                Log.i(
                    TAG,
                    "Overpass query found ${landmarks.size} landmark(s), ${streets.size} street(s), " +
                        "${areas.size} area(s), ${buildings.size} building(s) of ${elements.size()} element(s)",
                )
                MapDetails(landmarks, streets, areas, buildings)
            }.onFailure { Log.w(TAG, "Overpass fetch threw", it) }.getOrDefault(MapDetails(emptyList(), emptyList()))
        }

    /** Which [LandmarkCategory] a node's own OSM tags actually describe —
     * checked in the same priority the query itself groups by (a specific
     * `amenity` value first, since it's the most precise tag a POI can
     * carry; a bare `shop`/`tourism` presence last, since those tags only
     * say "this is a shop/attraction", never which kind). */
    private fun classify(tags: com.google.gson.JsonObject): LandmarkCategory {
        val amenity = tags.get("amenity")?.asString
        val office = tags.get("office")?.asString
        val shop = tags.get("shop")?.asString
        val tourism = tags.get("tourism")?.asString
        val religion = tags.get("religion")?.asString
        val name = tags.get("name")?.asString.orEmpty()
        return when {
            // A Kingdom Hall is `amenity=place_of_worship` with no OSM tag
            // of its own to tell it apart from any other church — checked
            // before the generic place_of_worship case below. Most mappers
            // do add `religion=jehovahs_witness`; a name match is the
            // fallback for the ones who only wrote the name.
            amenity == "place_of_worship" &&
                (religion == "jehovahs_witness" || name.contains("Kingdom Hall", ignoreCase = true)) ->
                LandmarkCategory.KINGDOM_HALL
            amenity == "school" -> LandmarkCategory.SCHOOL
            amenity == "place_of_worship" -> LandmarkCategory.WORSHIP
            amenity == "hospital" || amenity == "clinic" -> LandmarkCategory.HEALTH
            amenity == "pharmacy" -> LandmarkCategory.PHARMACY
            amenity == "marketplace" -> LandmarkCategory.MARKET
            amenity == "townhall" -> LandmarkCategory.GOVERNMENT
            amenity == "police" -> LandmarkCategory.POLICE
            amenity == "fire_station" -> LandmarkCategory.FIRE
            amenity == "fuel" -> LandmarkCategory.FUEL
            amenity == "bank" -> LandmarkCategory.BANK
            amenity == "restaurant" || amenity == "fast_food" -> LandmarkCategory.RESTAURANT
            office == "government" -> LandmarkCategory.GOVERNMENT
            shop == "supermarket" -> LandmarkCategory.SUPERMARKET
            tourism == "hotel" -> LandmarkCategory.HOTEL
            tourism != null -> LandmarkCategory.TOURISM
            shop != null -> LandmarkCategory.SHOP
            else -> LandmarkCategory.OTHER
        }
    }

    /** What to label a `landuse`/`natural` area as — its own OSM `name` if
     * it has one, else a plain description of what the tag itself says it
     * is. `landuse=farmland` has no crop-specific tag in practice for this
     * app's own rural provinces, so it maps to "Rice Field" (the actual,
     * near-universal crop here) rather than the more generic but less
     * useful "Farmland". Returns null for anything neither named nor one of
     * the recognized values, so an unrelated `landuse` this app doesn't
     * care about never becomes a mystery label. */
    private fun areaFeatureName(tags: com.google.gson.JsonObject): String? {
        tags.get("name")?.asString?.let { if (it.isNotBlank()) return it }
        val landuse = tags.get("landuse")?.asString
        val natural = tags.get("natural")?.asString
        return when {
            landuse == "farmland" -> "Rice Field"
            landuse == "orchard" -> "Orchard"
            landuse == "forest" -> "Forest"
            landuse == "meadow" -> "Grassland"
            landuse == "vineyard" -> "Vineyard"
            landuse == "aquaculture" -> "Fish Pond"
            natural == "wood" -> "Forest"
            natural == "water" -> "Water"
            else -> null
        }
    }

    private companion object {
        const val TAG = "OverpassLandmarkRepo"
        const val OVERPASS_URL = "https://overpass.openstreetmap.fr/api/interpreter"
    }
}
