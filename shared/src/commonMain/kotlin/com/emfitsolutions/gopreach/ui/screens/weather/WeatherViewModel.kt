package com.emfitsolutions.gopreach.ui.screens.weather

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.weather.CurrentPlaceResolver
import com.emfitsolutions.gopreach.data.repository.TerritoryDrawingRepository
import com.emfitsolutions.gopreach.data.sync.ConnectivityObserver
import com.emfitsolutions.gopreach.data.weather.WeatherPreferences
import com.emfitsolutions.gopreach.data.weather.WeatherService
import com.emfitsolutions.gopreach.data.weather.asSaved
import com.emfitsolutions.gopreach.domain.map.DrawingGeometry
import com.emfitsolutions.gopreach.domain.weather.PlaceKind
import com.emfitsolutions.gopreach.domain.weather.WeatherException
import com.emfitsolutions.gopreach.domain.weather.WeatherFailure
import com.emfitsolutions.gopreach.domain.weather.WeatherPlace
import com.emfitsolutions.gopreach.domain.weather.WeatherReport
import com.emfitsolutions.gopreach.platform.nowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class WeatherSource { NONE, LIVE, SAVED_COPY }

/** Which territories this person may pick: their own congregation's, or every congregation's (Super Admin), or none. */
data class TerritoryWeatherScope(val congregationId: String? = null, val allCongregations: Boolean = false)

data class WeatherUiState(
    val place: WeatherPlace? = null,
    val report: WeatherReport? = null,
    val source: WeatherSource = WeatherSource.NONE,
    val loading: Boolean = false,
    /** A failure to refresh. When a saved copy is also shown, the copy stays on screen under this message. */
    val error: String? = null,
    val failure: WeatherFailure? = null,
    val notice: String? = null,
    val online: Boolean = true,
    val query: String = "",
    val searching: Boolean = false,
    val results: List<WeatherPlace> = emptyList(),
    val searchMessage: String? = null,
    val saved: List<WeatherPlace> = emptyList(),
    val territories: List<WeatherPlace> = emptyList(),
    val locating: Boolean = false,
    val locationMessage: String? = null,
)

class WeatherViewModel(
    private val service: WeatherService,
    private val preferences: WeatherPreferences,
    private val connectivity: ConnectivityObserver,
    private val locationResolver: CurrentPlaceResolver,
    private val territories: TerritoryDrawingRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(WeatherUiState(online = connectivity.isOnline()))
    val state: StateFlow<WeatherUiState> = _state

    private var personId = ""
    private var started = false
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private val lastAttempt = mutableMapOf<String, Long>()

    fun start(personId: String, scope: TerritoryWeatherScope) {
        if (started) return
        started = true
        this.personId = personId
        _state.update { it.copy(saved = preferences.savedPlaces(personId)) }
        preferences.selected(personId)?.let { stored ->
            select(stored)
            // A place that came from the device location follows the person: quietly refresh it when permission is already granted.
            if (stored.kind == PlaceKind.CURRENT_LOCATION && locationResolver.hasPermission()) useCurrentLocation(silent = true)
        }

        viewModelScope.launch {
            territories.observeAll()
                .catch { }
                .collect { drawings ->
                    val allowed = drawings.filter { scope.allCongregations || (scope.congregationId != null && it.congregationId == scope.congregationId) }
                    val places = allowed.groupBy { it.territoryId.ifBlank { it.id } }.mapNotNull { (id, group) ->
                        val d = group.first()
                        val label = d.territoryName.ifBlank { d.name }.ifBlank { return@mapNotNull null }
                        val centre = group.firstNotNullOfOrNull { centreOf(it.geometryJson, it.minLat, it.maxLat, it.minLng, it.maxLng) } ?: return@mapNotNull null
                        WeatherPlace(id = "territory_$id", name = label, detail = "Territory", lat = centre.first, lng = centre.second, kind = PlaceKind.TERRITORY)
                    }.sortedBy { it.name.lowercase() }
                    _state.update { it.copy(territories = places) }
                }
        }
        viewModelScope.launch {
            var wasOnline = connectivity.isOnline()
            connectivity.observe().distinctUntilChanged().collect { online ->
                _state.update { it.copy(online = online) }
                // Retry quietly once the connection returns, when what is shown is a saved copy or a failure.
                val s = _state.value
                if (online && !wasOnline && s.place != null && (s.source != WeatherSource.LIVE || s.error != null)) load(s.place, force = false, retry = true)
                wasOnline = online
            }
        }
    }

    fun select(place: WeatherPlace) {
        preferences.setSelected(personId, place)
        _state.update { it.copy(place = place, report = null, source = WeatherSource.NONE, error = null, failure = null, notice = null, results = emptyList(), query = "", searchMessage = null) }
        load(place, force = false)
    }

    /** The refresh button. Refreshing the same place again within [MIN_REFRESH_MS] is declined so the service is not hammered. */
    fun refresh() { _state.value.place?.let { load(it, force = true) } }

    private fun load(place: WeatherPlace, force: Boolean, retry: Boolean = false) {
        loadJob?.cancel()
        val now = nowMillis()
        val cached = preferences.cached(personId, place, now)?.let { it.copy(place = place) } // a copy cached for a nearby place must not rename this one
        val current = _state.value.report?.takeIf { it.place.key == place.key } ?: cached
        val ageOfCurrent = current?.let { now - it.fetchedAtMillis }
        if (current != null) {
            val fresh = ageOfCurrent!! in 0..FRESH_MS
            _state.update { it.copy(report = current, source = if (fresh) WeatherSource.LIVE else WeatherSource.SAVED_COPY) }
            if (!force && fresh) { _state.update { it.copy(loading = false, error = null, failure = null) }; return }
        }
        val recentAttempt = lastAttempt[place.key]?.let { now - it < MIN_REFRESH_MS } == true
        if (recentAttempt && (force || retry) && current != null) {
            _state.update { it.copy(loading = false, notice = "Already up to date - checked a moment ago.") }
            return
        }
        if (!connectivity.isOnline()) {
            _state.update {
                it.copy(loading = false, online = false, failure = WeatherFailure.OFFLINE, notice = null,
                    error = if (current != null) "You are offline. Showing the last saved forecast." else "You are offline and there is no saved forecast for this place yet.")
            }
            return
        }
        lastAttempt[place.key] = now
        _state.update { it.copy(loading = true, error = null, failure = null, notice = null) }
        loadJob = viewModelScope.launch {
            try {
                val report = service.forecast(place, nowMillis())
                preferences.cache(personId, report)
                _state.update { if (it.place?.key != place.key) it else it.copy(report = report, source = WeatherSource.LIVE, loading = false, error = null, failure = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WeatherException) {
                fail(place, e.failure, e.message ?: "Weather could not be loaded.", current != null)
            } catch (e: Exception) {
                fail(place, WeatherFailure.BAD_RESPONSE, "Weather could not be loaded.", current != null)
            }
        }
    }

    private fun fail(place: WeatherPlace, failure: WeatherFailure, message: String, hasCopy: Boolean) {
        _state.update {
            if (it.place?.key != place.key) it
            else it.copy(loading = false, failure = failure, error = if (hasCopy) "$message Showing the last saved forecast." else message, source = if (hasCopy) WeatherSource.SAVED_COPY else WeatherSource.NONE)
        }
    }

    fun onQueryChange(text: String) {
        _state.update { it.copy(query = text, searchMessage = null) }
        searchJob?.cancel()
        if (text.trim().length < 2) { _state.update { it.copy(results = emptyList(), searching = false) }; return }
        searchJob = viewModelScope.launch {
            delay(450) // wait for the person to stop typing
            if (!connectivity.isOnline()) { _state.update { it.copy(searching = false, results = emptyList(), searchMessage = "Searching for a place needs an internet connection. Your saved places still work offline.") }; return@launch }
            _state.update { it.copy(searching = true) }
            try {
                val found = service.search(text)
                _state.update { it.copy(searching = false, results = found, searchMessage = if (found.isEmpty()) "No place found. Try the name of a municipality, city or barangay." else null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WeatherException) {
                _state.update { it.copy(searching = false, results = emptyList(), searchMessage = e.message) }
            }
        }
    }

    /** Called only after the device location permission is granted (or already was). */
    fun useCurrentLocation(silent: Boolean = false) {
        _state.update { it.copy(locating = true, locationMessage = null) }
        viewModelScope.launch {
            when (val r = locationResolver.resolve()) {
                CurrentPlaceResolver.Result.ServicesOff -> _state.update { it.copy(locating = false, locationMessage = if (silent) null else "Location services are switched off. Turn them on, or search for a place instead.") }
                CurrentPlaceResolver.Result.NotFound -> _state.update { it.copy(locating = false, locationMessage = if (silent) null else "Your location could not be found. Try again, or search for a place instead.") }
                is CurrentPlaceResolver.Result.Found -> { _state.update { it.copy(locating = false) }; select(r.place) }
            }
        }
    }

    fun onLocationPermissionDenied() {
        _state.update { it.copy(locating = false, locationMessage = "Location permission was not allowed. You can still search for a place or pick a saved one.") }
    }

    fun hasLocationPermission() = locationResolver.hasPermission()

    fun savePlace(place: WeatherPlace, name: String = place.name) {
        val saved = _state.value.saved
        if (saved.any { it.key == place.key }) { _state.update { it.copy(notice = "This place is already saved.") }; return }
        val entry = place.asSaved(WeatherPreferences.newSavedId(nowMillis()), name.trim().ifBlank { place.name })
        persist(saved + entry)
        _state.update { if (it.place?.key == place.key) it.copy(place = entry, notice = "Saved \"${entry.name}\".") else it.copy(notice = "Saved \"${entry.name}\".") }
        preferences.setSelected(personId, _state.value.place ?: entry)
    }

    fun renamePlace(id: String, name: String) {
        val clean = name.trim().ifBlank { return }
        persist(_state.value.saved.map { if (it.id == id) it.copy(name = clean) else it })
        _state.update { s -> if (s.place?.id == id) s.copy(place = s.place.copy(name = clean), report = s.report?.copy(place = s.report.place.copy(name = clean))) else s }
    }

    fun removePlace(id: String) = persist(_state.value.saved.filterNot { it.id == id })

    private fun persist(list: List<WeatherPlace>) {
        preferences.savePlaces(personId, list)
        _state.update { it.copy(saved = list) }
    }

    fun consumeNotice() = _state.update { it.copy(notice = null) }

    companion object {
        const val FRESH_MS = 10 * 60_000L
        const val MIN_REFRESH_MS = 30_000L

        /** The middle of a drawing: its stored bounds when present, otherwise the average of its outline. */
        internal fun centreOf(geometryJson: String, minLat: Double, maxLat: Double, minLng: Double, maxLng: Double): Pair<Double, Double>? {
            if (!(minLat == 0.0 && maxLat == 0.0 && minLng == 0.0 && maxLng == 0.0)) return (minLat + maxLat) / 2 to (minLng + maxLng) / 2
            val ring = DrawingGeometry.parsePolygon(geometryJson) ?: DrawingGeometry.parsePoint(geometryJson)?.let { listOf(it) } ?: return null
            return ring.map { it.lat }.average() to ring.map { it.lng }.average()
        }
    }
}
