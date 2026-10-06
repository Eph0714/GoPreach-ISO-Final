package com.emfitsolutions.gopreach.data.repository

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "TERRITORY MAP – ... BOUNDARIES" (both the current and the queued-up
 * "FINAL RECORD SCOPE" spec) — "Do not use an arbitrary circle. Use the
 * actual geographic boundary available from the map/geographic data source."
 *
 * Real Municipality/Barangay polygon boundaries, sourced from OCHA/NAMRIA/PSA's
 * own published Philippines administrative boundaries (HDX "cod-ab-phl",
 * CC BY 3.0 IGO — see https://data.humdata.org/dataset/cod-ab-phl), trimmed
 * offline to just the province(s) this app's congregations actually use
 * (currently Nueva Vizcaya — the full nationwide barangay layer alone is
 * ~700MB decompressed, far too large to bundle) and bundled as a single
 * ~650KB asset. This keeps the boundary lookup fully offline (no live
 * geocoding/tile-boundary API call, no dependency on the on-device Geocoder
 * this session already found unreliable on non-genuine-GMS devices) at the
 * cost of only covering provinces this file was built for.
 *
 * Keyed by the exact same name-normalization rule [PhilippineLocationRepository]'s
 * own `normalize()` uses (strip parentheticals, strip non-alphanumerics,
 * collapse whitespace, uppercase) so a lookup by a record's own plain
 * `cityMunicipality`/`barangay` string — never a PSGC code, this bundled
 * PSGC table has none, see [com.emfitsolutions.gopreach.data.local.psgc
 * .MuncityEntity]'s own doc comment — finds its boundary with no fuzzy
 * matching needed.
 *
 * To add another province: re-run the same extraction script (stream-filters
 * the HDX zip's `phl_admin3.geojson`/`phl_admin4.geojson` entries by
 * `adm2_name`, no need to download the full ~1GB archive) and replace this
 * asset; nothing else in the app needs to change since a lookup miss here
 * already degrades gracefully (see [TerritoryLiveMap]'s circle fallback).
 */
@Singleton
class TerritoryBoundaryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remoteBarangayBoundaryRepository: RemoteBarangayBoundaryRepository,
) {
    private var loaded = false
    private var municipalities: JsonObject? = null
    private var barangays: JsonObject? = null

    private fun normalize(s: String): String =
        s.replace(Regex("\\(.*?\\)"), " ")
            .replace(Regex("[^A-Za-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .uppercase()

    // Bug fix ("two adjacent barangays' boundaries visibly cross/overlap on
    // the map instead of sharing a clean edge") — the bundled HDX extract is
    // itself inconsistent about "Santa"/"Santo": Bagabag's own entries
    // abbreviate it ("STA CRUZ", "STA LUCIA") while Bayombong's spell it in
    // full ("SANTA ROSA"), so neither a plain lookup nor a blanket
    // full<->abbreviated rewrite matches every entry. A barangay whose own
    // stored name used the OTHER form than its bundled entry silently missed
    // the bundled (precise) geometry and fell through to
    // [RemoteBarangayBoundaryRepository]'s cruder, differently-sourced
    // fallback instead — a different data source than its bundled neighbor
    // almost never shares the exact same edge, so the two boundaries visibly
    // crossed instead of touching. Trying both forms of the key (only when
    // "SANTA"/"STA"/"SANTO"/"STO" actually appears, so every other lookup is
    // the one plain key as before) finds the bundled entry regardless of
    // which form it happens to use.
    private fun keyVariants(normalized: String): List<String> {
        if (!Regex("\\b(SANTA|STA|SANTO|STO)\\b").containsMatchIn(normalized)) return listOf(normalized)
        val abbreviated = normalized.replace(Regex("\\bSANTA\\b"), "STA").replace(Regex("\\bSANTO\\b"), "STO")
        val full = normalized.replace(Regex("\\bSTA\\b"), "SANTA").replace(Regex("\\bSTO\\b"), "SANTO")
        return listOf(normalized, abbreviated, full).distinct()
    }

    // Same bug, different cause: a handful of Bayombong "Poblacion district"
    // barangays (e.g. "District III (D.M.P.)", "Don Domingo Maddela") are
    // bundled with a trailing " POB" the barangay's own stored name doesn't
    // include ("BAYOMBONG|DISTRICT III POB" vs. the stored "District III
    // (D.M.P.)", which normalizes to just "DISTRICT III" once the
    // parenthetical is stripped) — same silent miss -> cruder remote
    // fallback -> visibly crossing boundary as the Santa/Sta case above.
    // Tried only as a barangay-key suffix (never for the municipality side,
    // where "POB" would never legitimately belong) so this never changes
    // behavior for the vast majority of barangays that already match plainly.
    private fun barangayKeyVariants(normalized: String): List<String> =
        keyVariants(normalized).flatMap { listOf(it, "$it POB") }.distinct()

    private suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            if (loaded) return@withContext
            try {
                context.assets.open("territory/territory_boundaries.json").use { input ->
                    val root = JsonParser.parseReader(input.bufferedReader()).asJsonObject
                    municipalities = root.getAsJsonObject("municipalities")
                    barangays = root.getAsJsonObject("barangays")
                }
            } catch (e: Exception) {
                // No bundled data at all (shouldn't happen — the asset ships
                // with the app), or a corrupt file. Every lookup below just
                // returns null, so callers fall back to the circle, same as
                // an ordinary "this province isn't covered yet" miss.
                municipalities = null
                barangays = null
            }
            loaded = true
        }
    }

    /** Real polygon/multipolygon GeoJSON geometry (as a raw JSON string,
     * ready to hand straight to Leaflet's `L.geoJSON`) for a Municipality,
     * or null if this asset wasn't built for that Municipality's province.
     *
     * Bug fix ("boundary dialog shows the whole world instead of the
     * barangay" for a handful of entries): [JsonObject.get] returns Gson's
     * own [com.google.gson.JsonNull] singleton — not Kotlin `null` — when a
     * key's value is a JSON `null` (as opposed to the key being absent
     * entirely); [com.google.gson.JsonNull.toString] is the literal 4-character
     * string `"null"`, which is not equal to Kotlin `null` and sailed straight
     * past every caller's `geometryJson == null` "not covered" check. That
     * string then got embedded verbatim as `var geometry = null;` in the
     * boundary dialog's JS, which `L.geoJSON()` silently accepts and turns
     * into an empty layer with invalid bounds — Leaflet's fallback for that
     * is to render the default whole-world view, not an error. [isJsonNull]
     * now treats that case the same as a missing key: a clean `null`, so
     * every caller's existing "not available yet" fallback actually fires. */
    suspend fun municipalityGeometry(municipality: String): String? {
        ensureLoaded()
        for (key in keyVariants(normalize(municipality))) {
            val element = municipalities?.get(key) ?: continue
            return if (element.isJsonNull) null else element.toString()
        }
        return null
    }

    /** Same as [municipalityGeometry], for one Barangay within a Municipality —
     * both names are needed since Barangay names repeat across Municipalities.
     * [province] is only used if the bundled Nueva Vizcaya extract misses
     * (i.e. for every other province) — see [RemoteBarangayBoundaryRepository]'s
     * own doc comment for why that live fallback exists at all instead of
     * this method just returning null for anywhere outside Nueva Vizcaya. */
    suspend fun barangayGeometry(province: String, municipality: String, barangay: String): String? {
        ensureLoaded()
        val municipalityKeys = keyVariants(normalize(municipality))
        val barangayKeys = barangayKeyVariants(normalize(barangay))
        var bundled: String? = null
        outer@ for (m in municipalityKeys) {
            for (b in barangayKeys) {
                val element = barangays?.get("$m|$b") ?: continue
                if (element.isJsonNull) continue
                bundled = element.toString()
                break@outer
            }
        }
        return bundled ?: remoteBarangayBoundaryRepository.barangayGeometry(province, municipality, barangay)
    }
}
