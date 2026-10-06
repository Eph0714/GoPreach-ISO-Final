package com.emfitsolutions.gopreach.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Live fallback for any barangay outside [TerritoryBoundaryRepository]'s
 * bundled Nueva Vizcaya extract — "No boundary map available for this
 * barangay yet" otherwise never resolves for any other province, since
 * bundling the full nationwide admin4 layer (~700MB decompressed, per that
 * class's own doc comment) isn't viable, doubly so now that the native
 * TomTom SDK already pushed this app's APK size far past normal. Fetches
 * one municipality's barangay boundaries at a time (~15-50KB, not the whole
 * country) from faeldon/philippines-json-maps — MIT-licensed, GADM/NAMRIA-
 * derived, 2011 vintage (any barangay split/renamed since then won't appear
 * here) — and caches each municipality's file on disk so repeat lookups
 * never re-fetch: https://github.com/faeldon/philippines-json-maps
 *
 * Deliberately best-effort, same spirit as [OverpassStreetRepository]: any
 * failure (offline, GitHub rate limit, municipality/barangay not found)
 * returns `null`, which every caller already treats as "not covered yet" —
 * never a crash or a blocking error.
 */
class RemoteBarangayBoundaryRepository(
    private val context: Context,
) {
    private val mutex = Mutex()
    private var municityIndex: Map<String, String>? = null // slug -> filename
    private val featureCache = LinkedHashMap<String, List<JsonObject>>()

    /** [province] disambiguates municipality names that repeat across
     * provinces (e.g. "San Jose") — when it doesn't match any candidate's
     * own `NAME_1`, falls back to the first candidate rather than failing
     * outright, since a caller that genuinely has no province context is
     * still better served by a best guess than nothing. */
    suspend fun barangayGeometry(province: String, municipality: String, barangay: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                withTimeoutOrNull(8000) {
                    val fileName = findMunicipalityFile(province, municipality) ?: return@withTimeoutOrNull null
                    val features = loadMunicipalityFeatures(fileName) ?: return@withTimeoutOrNull null
                    val wanted = normalize(barangay)
                    features.firstOrNull { feature ->
                        val name = feature.getAsJsonObject("properties")?.get("NAME_3")?.asString ?: ""
                        normalize(name) == wanted
                    }?.getAsJsonObject("geometry")?.toString()
                }
            }.onFailure { Log.w(TAG, "Remote barangay boundary fetch failed", it) }.getOrNull()
        }

    private fun normalize(s: String): String =
        s.replace(Regex("\\(.*?\\)"), " ")
            .replace(Regex("[^A-Za-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .uppercase()

    private fun slug(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private suspend fun findMunicipalityFile(province: String, municipality: String): String? = mutex.withLock {
        val index = municityIndex ?: fetchIndex()?.also { municityIndex = it } ?: return null
        val wantedSlug = slug(municipality)
        val candidates = index.filterKeys { it == wantedSlug }.values.toList()
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()
        val wantedProvince = normalize(province)
        for (fileName in candidates) {
            val features = loadMunicipalityFeatures(fileName) ?: continue
            val actualProvince = features.firstOrNull()?.getAsJsonObject("properties")?.get("NAME_1")?.asString
            if (actualProvince != null && normalize(actualProvince) == wantedProvince) return fileName
        }
        candidates.first()
    }

    // The Contents API (GET .../contents/<dir>) silently truncates past ~900
    // entries with no error or pagination header — confirmed against this
    // exact directory (885 of this repo's real ~1,395 municipality files
    // came back). The recursive Git Trees API has no such cap, at the cost
    // of listing the whole repo (every year/format, not just this one
    // directory) in one response — acceptable for a one-time, in-memory-
    // cached index fetch.
    private fun fetchIndex(): Map<String, String>? = runCatching {
        val connection = (URL(INDEX_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        if (connection.responseCode != 200) {
            Log.w(TAG, "Municipality index fetch returned HTTP ${connection.responseCode}")
            return null
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val root = JsonParser.parseString(body).asJsonObject
        val entries = root.getAsJsonArray("tree") ?: return null
        val map = LinkedHashMap<String, String>()
        entries.forEach { entry ->
            val path = entry.asJsonObject.get("path")?.asString ?: return@forEach
            // The repo has 2011/2019/2023 years, geojson/topojson formats,
            // and hires/medres/lowres variants, all sharing similarly-shaped
            // filenames — matching the directory prefix too (not just the
            // filename regex) avoids e.g. a topojson or hires path winning
            // the same "bayombong" slug key by sheer iteration order.
            if (!path.startsWith(TARGET_DIR_PREFIX)) return@forEach
            // ".../lowres/barangays-municity-1156-bayombong.0.001.json" -> slug "bayombong"
            val match = FILE_NAME_REGEX.find(path) ?: return@forEach
            map[match.groupValues[1]] = path.substringAfterLast('/')
        }
        map
    }.onFailure { Log.w(TAG, "Fetching municipality index threw", it) }.getOrNull()

    private fun loadMunicipalityFeatures(fileName: String): List<JsonObject>? {
        synchronized(featureCache) { featureCache[fileName] }?.let { return it }
        val diskCacheFile = File(File(context.cacheDir, CACHE_DIR_NAME), fileName)
        val body = if (diskCacheFile.exists()) {
            runCatching { diskCacheFile.readText() }.getOrNull()
        } else {
            val fetched = runCatching {
                val connection = (URL("$RAW_BASE_URL/$fileName").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 8000
                }
                if (connection.responseCode != 200) {
                    Log.w(TAG, "Fetching $fileName returned HTTP ${connection.responseCode}")
                    return@runCatching null
                }
                connection.inputStream.bufferedReader().use { it.readText() }
            }.onFailure { Log.w(TAG, "Fetching $fileName threw", it) }.getOrNull()
            if (fetched != null) {
                runCatching {
                    diskCacheFile.parentFile?.mkdirs()
                    diskCacheFile.writeText(fetched)
                }
            }
            fetched
        } ?: return null
        val features = runCatching {
            JsonParser.parseString(body).asJsonObject.getAsJsonArray("features").map { it.asJsonObject }
        }.getOrNull() ?: return null
        synchronized(featureCache) { featureCache[fileName] = features }
        return features
    }

    private companion object {
        const val TAG = "RemoteBarangayBoundary"
        const val CACHE_DIR_NAME = "barangay_boundaries"
        const val INDEX_URL = "https://api.github.com/repos/faeldon/philippines-json-maps/git/trees/master?recursive=1"
        const val TARGET_DIR_PREFIX = "2011/geojson/barangays/lowres/"
        const val RAW_BASE_URL = "https://raw.githubusercontent.com/faeldon/philippines-json-maps/master/2011/geojson/barangays/lowres"
        val FILE_NAME_REGEX = Regex("""barangays-municity-\d+-([a-z0-9]+)\.""")
    }
}
