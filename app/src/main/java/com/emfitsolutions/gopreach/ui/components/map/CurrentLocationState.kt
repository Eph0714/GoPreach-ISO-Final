package com.emfitsolutions.gopreach.ui.components.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.emfitsolutions.gopreach.data.location.LocationTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import com.emfitsolutions.gopreach.data.location.LatLng as FixedLatLng

/** Gives map composables the device location without each module re-implementing it. */
@HiltViewModel
class CurrentLocationViewModel @Inject constructor(private val tracker: LocationTracker) : ViewModel() {
    fun hasPermission(): Boolean = tracker.hasLocationPermission()
    suspend fun lastKnown(): FixedLatLng? = tracker.getLastKnownLocation()
    fun updates(): Flow<FixedLatLng> = tracker.requestLocationUpdatesFlow(intervalMillis = 4_000L, minUpdateIntervalMillis = 2_000L)
}

/**
 * The device's current (lat, lng) for a map that has no location handling of its own — it feeds
 * [MapLibreHost]'s `myLocation`, so the shared blinking indicator shows up on that map too.
 * Updates only while the screen is started; it never prompts for permission (the Territory Map is
 * where that is asked) — without it, this is just null and no indicator is drawn.
 */
@Composable
fun rememberCurrentLocation(): State<Pair<Double, Double>?> {
    val viewModel: CurrentLocationViewModel = hiltViewModel()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val location = remember { mutableStateOf<Pair<Double, Double>?>(null) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!viewModel.hasPermission()) return@repeatOnLifecycle
            runCatching { viewModel.lastKnown() }.getOrNull()?.let { location.value = it.lat to it.lng }
            runCatching { viewModel.updates().collect { location.value = it.lat to it.lng } }
        }
    }
    return location
}
