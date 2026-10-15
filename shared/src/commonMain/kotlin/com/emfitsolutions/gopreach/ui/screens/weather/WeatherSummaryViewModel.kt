package com.emfitsolutions.gopreach.ui.screens.weather

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.sync.ConnectivityObserver
import com.emfitsolutions.gopreach.data.weather.CurrentPlaceResolver
import com.emfitsolutions.gopreach.data.weather.WeatherPreferences
import com.emfitsolutions.gopreach.data.weather.WeatherService
import com.emfitsolutions.gopreach.domain.weather.WeatherPlace
import com.emfitsolutions.gopreach.platform.nowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The one-line weather shown on the dashboard header. [place] null means no place has been chosen yet. */
data class WeatherSummary(
    val loaded: Boolean = false,
    val place: WeatherPlace? = null,
    val temperatureC: Double? = null,
    val weatherCode: Int? = null,
    val isDay: Boolean = true,
    /** True when the figures come from a saved copy that could not be refreshed - never presented as live. */
    val saved: Boolean = false,
)

/**
 * Feeds the dashboard weather line from the same per-person cache the Weather screen writes, and refreshes it quietly when it is older than
 * [WeatherViewModel.FRESH_MS] and the device is online. A failure leaves the saved copy on screen, labelled as saved.
 */
class WeatherSummaryViewModel(
    private val service: WeatherService,
    private val preferences: WeatherPreferences,
    private val connectivity: ConnectivityObserver,
    private val locationResolver: CurrentPlaceResolver,
) : ViewModel() {
    private val _state = MutableStateFlow(WeatherSummary())
    val state: StateFlow<WeatherSummary> = _state

    private var personId = ""
    private var job: Job? = null
    private var observing = false

    fun refresh(personId: String) {
        this.personId = personId
        if (!observing) {
            observing = true
            viewModelScope.launch { connectivity.observe().distinctUntilChanged().drop(1).collect { if (it) load() } }
        }
        load()
    }

    private fun load() {
        job?.cancel()
        val place = preferences.selected(personId)
        if (place == null) { _state.value = WeatherSummary(loaded = true); useDeviceLocationAsFirstPlace(); return }
        val now = nowMillis()
        val cached = preferences.cached(personId, place, now)
        val fresh = cached != null && now - cached.fetchedAtMillis in 0..WeatherViewModel.FRESH_MS
        if (cached != null) show(place, cached, saved = !fresh)
        else _state.update { it.copy(loaded = true, place = place, temperatureC = null, weatherCode = null) }
        if (fresh || !connectivity.isOnline()) return
        job = viewModelScope.launch {
            try {
                val report = service.forecast(place, nowMillis())
                preferences.cache(personId, report)
                show(place, report, saved = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // keep whatever is on screen; a saved copy stays labelled as saved
            }
        }
    }

    /** Nothing chosen yet: when location permission was already given, the device's own location becomes the first place. It never asks for permission here. */
    private fun useDeviceLocationAsFirstPlace() {
        if (!locationResolver.hasPermission()) return
        job = viewModelScope.launch {
            val found = locationResolver.resolve() as? CurrentPlaceResolver.Result.Found ?: return@launch
            if (preferences.selected(personId) != null) return@launch
            preferences.setSelected(personId, found.place)
            load()
        }
    }

    private fun show(place: WeatherPlace, report: com.emfitsolutions.gopreach.domain.weather.WeatherReport, saved: Boolean) {
        val c = report.current
        _state.value = WeatherSummary(loaded = true, place = place, temperatureC = c?.temperatureC, weatherCode = c?.weatherCode, isDay = c?.isDay ?: true, saved = saved)
    }
}
