package com.emfitsolutions.gopreach.data.weather

import com.emfitsolutions.gopreach.domain.weather.PlaceKind
import com.emfitsolutions.gopreach.domain.weather.WeatherPlace
import com.emfitsolutions.gopreach.domain.weather.WeatherReport
import com.emfitsolutions.gopreach.platform.KeyValueStore
import com.emfitsolutions.gopreach.platform.KeyValueStores
import com.emfitsolutions.gopreach.platform.edit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Everything the Weather module remembers, kept on this device only (never sent to the server, never part of the sync queue or any report):
 * the saved places, the last place looked at, and the most recent forecast of a few places for offline use. One store per signed-in person,
 * so a shared phone does not show one person's saved places to another.
 */
class WeatherPreferences(private val stores: KeyValueStores) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun prefs(personId: String): KeyValueStore = stores.open("gopreach_weather_${personId.ifBlank { "guest" }}")

    fun savedPlaces(personId: String): List<WeatherPlace> = read(prefs(personId).getString(SAVED, null), ListSerializer(WeatherPlace.serializer())).orEmpty()

    fun savePlaces(personId: String, places: List<WeatherPlace>) {
        prefs(personId).edit { putString(SAVED, json.encodeToString(ListSerializer(WeatherPlace.serializer()), places.take(MAX_SAVED))) }
    }

    fun selected(personId: String): WeatherPlace? = read(prefs(personId).getString(SELECTED, null), WeatherPlace.serializer())

    fun setSelected(personId: String, place: WeatherPlace) {
        prefs(personId).edit { putString(SELECTED, json.encodeToString(WeatherPlace.serializer(), place)) }
    }

    /** The cached report for [place], or null when none exists or it is older than [maxAgeMillis] (an expired copy is dropped). */
    fun cached(personId: String, place: WeatherPlace, now: Long, maxAgeMillis: Long = CACHE_MAX_AGE_MS): WeatherReport? {
        val report = read(prefs(personId).getString(CACHE_PREFIX + place.key, null), WeatherReport.serializer()) ?: return null
        val age = now - report.fetchedAtMillis
        if (age < 0 || age > maxAgeMillis || report.hourly.isEmpty() && report.daily.isEmpty() && report.current == null) {
            prefs(personId).edit { remove(CACHE_PREFIX + place.key) }
            return null
        }
        return report
    }

    fun cache(personId: String, report: WeatherReport) {
        val store = prefs(personId)
        val index = (store.getString(CACHE_INDEX, "").orEmpty().split('|').filter { it.isNotBlank() } - report.place.key) + report.place.key
        val dropped = index.take((index.size - MAX_CACHED).coerceAtLeast(0))
        store.edit {
            putString(CACHE_PREFIX + report.place.key, json.encodeToString(WeatherReport.serializer(), report))
            dropped.forEach { remove(CACHE_PREFIX + it) }
            putString(CACHE_INDEX, index.takeLast(MAX_CACHED).joinToString("|"))
        }
    }

    private fun <T> read(text: String?, serializer: kotlinx.serialization.KSerializer<T>): T? =
        text?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    companion object {
        const val MAX_SAVED = 30
        const val MAX_CACHED = 8
        const val CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        private const val SAVED = "saved_places"
        private const val SELECTED = "selected_place"
        private const val CACHE_PREFIX = "report_"
        private const val CACHE_INDEX = "report_index"

        fun newSavedId(now: Long) = "saved_$now"
    }
}

/** A place worth saving keeps its own coordinates but is stored as [PlaceKind.SAVED] under a stable id. */
fun WeatherPlace.asSaved(id: String, name: String = this.name) = copy(id = id, name = name, kind = PlaceKind.SAVED)
