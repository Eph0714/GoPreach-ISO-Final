package com.emfitsolutions.gopreach.ui.components.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.emfitsolutions.gopreach.BuildConfig
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** MapTiler vector style URL for [styleId] ("hybrid-v4", "outdoor-v4",
 * "basic-v2-dark", "satellite", ...), needing [BuildConfig.MAPTILER_API_KEY].
 * Without a key it falls back to OpenFreeMap's keyless "Liberty" style, so a
 * map is never blank just because a developer key is missing. */
fun maptilerStyleUrl(styleId: String): String =
    if (com.emfitsolutions.gopreach.data.offlinemap.OfflineMaps.shouldUse()) com.emfitsolutions.gopreach.data.offlinemap.OfflineMaps.styleUrl(dark = "dark" in styleId)
    else maptilerOnlineStyleUrl(styleId)

private fun maptilerOnlineStyleUrl(styleId: String): String =
    if (BuildConfig.MAPTILER_API_KEY.isBlank()) "https://tiles.openfreemap.org/styles/liberty"
    else "https://api.maptiler.com/maps/$styleId/style.json?key=${BuildConfig.MAPTILER_API_KEY}"

/**
 * The one place that embeds a native MapLibre [MapView] in Compose: library
 * init, the MapView lifecycle forwarding it requires, (re)loading the style,
 * and load/fail reporting. Screens add their own sources/layers inside
 * [onStyleReady], which runs after *every* style load — switching style wipes
 * all sources/layers/images, so anything added there is re-added each time.
 *
 * It also owns what every GoPreach map shares, so no module builds its own:
 *  - the blinking radar/spiral "You Are Here" indicator ([CurrentLocationLayer]) for
 *    [myLocation], always added after everything a screen adds, so it is on top;
 *  - the N/E/S/W [MapOrientationIndicator], driven by the device compass when there
 *    is a trustworthy one (otherwise the map stays North-up and no compass is shown);
 *  - the drawing layers ([drawingLayers] = true) that [MapDrawingOverlay] draws into.
 *
 * [content] is laid over the map and receives the live [MapLibreMap] plus a
 * `styleVersion` that changes after every style load (what effects key on to re-add data).
 *
 * [interactive] = false switches every gesture off (a small preview that must
 * not fight the scroll view around it). [reloadToken] changes force a style
 * reload — what a screen's "Retry" button bumps.
 */
@Composable
fun MapLibreHost(
    styleUrl: String,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    reloadToken: Int = 0,
    onLoadStateChange: (MapLoadState) -> Unit = {},
    onMapClick: (MapLibreMap, LatLng) -> Boolean = { _, _ -> false },
    onMapLongClick: (MapLibreMap, LatLng) -> Boolean = { _, _ -> false },
    myLocation: Pair<Double, Double>? = null,
    /** GPS accuracy radius of [myLocation] in metres, drawn as a faint circle under the arrow. */
    myAccuracyMeters: Float? = null,
    showOrientation: Boolean = interactive,
    orientationAlignment: Alignment = Alignment.CenterStart,
    drawingLayers: Boolean = false,
    /** Tapping the My Location arrow centers the map on it. A screen whose records sit above the arrow turns this off and does the check itself, after its record layers. */
    recenterOnArrowTap: Boolean = true,
    /** Driving Mode: the arrow turns to [headingOverrideDeg] (GPS course of travel, true-north degrees) instead of the compass, and the compass-driven map rotation is off. */
    drivingMode: Boolean = false,
    headingOverrideDeg: Float? = null,
    /** Walking Mode (on top of [drivingMode]): a walking person marker, and the compass heading is used while no direction of travel is known. */
    walkingMode: Boolean = false,
    /** True: turn the map to the compass heading (what is in front of the arrow is up), as if the compass dial had been tapped. False afterwards: back to north-up. */
    headingUpRequest: Boolean = false,
    /** The compass heading (rounded to 3 degrees), or null when there is none: lets a screen turn the map and move it in ONE camera update. */
    onHeading: (Float?) -> Unit = {},
    onStyleReady: (MapLibreMap, Style) -> Unit,
    content: @Composable BoxScope.(map: MapLibreMap?, styleVersion: Int) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    remember { MapLibre.getInstance(context) }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleVersion by remember { mutableIntStateOf(0) }
    val latestOnLoadState = rememberUpdatedState(onLoadStateChange)
    val latestOnMapClick = rememberUpdatedState(onMapClick)
    val latestOnMapLongClick = rememberUpdatedState(onMapLongClick)
    val latestOnStyleReady = rememberUpdatedState(onStyleReady)
    val latestOnHeading = rememberUpdatedState(onHeading)
    val latestMyLocation = rememberUpdatedState(myLocation)

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            addOnDidFailLoadingMapListener { latestOnLoadState.value(MapLoadState.FAILED) }
            getMapAsync { m ->
                m.uiSettings.isLogoEnabled = false
                // GoPreach draws its own compass (MapOrientationIndicator).
                m.uiSettings.isCompassEnabled = false
                if (!interactive) {
                    m.uiSettings.setAllGesturesEnabled(false)
                }
                m.cameraPosition = CameraPosition.Builder().target(LatLng(12.8797, 121.7740)).zoom(5.0).build()
                m.addOnMapClickListener { latLng ->
                    // Tapping the My Location arrow centers the map on the user.
                    val me = latestMyLocation.value
                    val tap = m.projection.toScreenLocation(latLng)
                    if (recenterOnArrowTap && me != null && CurrentLocationLayer.isHit(m, tap, me, 24f * density)) {
                        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(me.first, me.second), maxOf(m.cameraPosition.zoom, 16.0)), 400)
                        true
                    } else {
                        latestOnMapClick.value(m, latLng)
                    }
                }
                m.addOnMapLongClickListener { latLng -> latestOnMapLongClick.value(m, latLng) }
                map = m
            }
        }
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(map, styleUrl, reloadToken) {
        val m = map ?: return@LaunchedEffect
        latestOnLoadState.value(MapLoadState.LOADING)
        m.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
            // Shared layers go FIRST (above only the base map) so the screen's own layers — the Searching / Return Visit /
            // Bible Study record icons above all — draw on top of territory drawings and the My Location arrow.
            if (drawingLayers) MapDrawingLayers.install(style)
            CurrentLocationLayer.install(style, density)
            latestOnStyleReady.value(m, style)
            styleVersion++
            latestOnLoadState.value(MapLoadState.LOADED)
        }
    }

    // ---- shared location + orientation -----------------------------------------
    val needsCompass = interactive && (!drivingMode || walkingMode) && (showOrientation || myLocation != null)
    val compassHeading: Float? = if (needsCompass) rememberDeviceHeading(myLocation).value else null
    // While driving, the direction is the GPS course of travel - never the way the phone happens to face.
    val heading: Float? = if (drivingMode) (headingOverrideDeg ?: if (walkingMode) compassHeading else null) else compassHeading
    var orientationMode by remember { mutableStateOf(OrientationMode.NORTH_UP) }
    LaunchedEffect(heading == null) { if (heading == null) orientationMode = OrientationMode.NORTH_UP }
    // A screen (Follow mode) can ask for heading-up; it is only applied when a compass reading exists (never invented), and undone when the request ends.
    LaunchedEffect(heading?.let { (it / 3f).toInt() }) { latestOnHeading.value(heading?.let { (it / 3f).toInt() * 3f }) }
    var headingUpRequested by remember { mutableStateOf(false) }
    LaunchedEffect(headingUpRequest, heading != null) {
        if (headingUpRequest) {
            headingUpRequested = true
            if (heading != null) orientationMode = OrientationMode.FOLLOW_HEADING
        } else if (headingUpRequested) {
            headingUpRequested = false
            orientationMode = OrientationMode.NORTH_UP
            map?.animateCamera(CameraUpdateFactory.bearingTo(0.0), 300)
        }
    }
    CurrentLocationUpdater(map, styleVersion, myLocation, heading, drivingMode = drivingMode, directionKnown = !drivingMode || headingOverrideDeg != null || (walkingMode && compassHeading != null), walkingMode = walkingMode && drivingMode)
    LaunchedEffect(map, styleVersion, myLocation, myAccuracyMeters) {
        val style = map?.style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        CurrentLocationLayer.setAccuracy(style, myLocation, myAccuracyMeters)
    }
    if (interactive && !drivingMode) MapOrientationEffects(map, heading, orientationMode)
    val bearing by rememberMapBearing(map)

    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize())
        content(map, styleVersion)
        if (showOrientation) {
            MapOrientationIndicator(
                mapBearing = bearing,
                headingDeg = compassHeading,
                mode = orientationMode,
                onClick = {
                    val m = map
                    if (heading != null && orientationMode == OrientationMode.NORTH_UP) {
                        orientationMode = OrientationMode.FOLLOW_HEADING
                    } else {
                        orientationMode = OrientationMode.NORTH_UP
                        m?.animateCamera(CameraUpdateFactory.bearingTo(0.0), 300)
                    }
                },
                modifier = Modifier.align(orientationAlignment).padding(10.dp),
            )
        }
    }
}

/** Load progress of a map embedded via [MapLibreHost]. */
enum class MapLoadState { LOADING, LOADED, FAILED }
