package com.emfitsolutions.gopreach.ui.screens.territoryassignments

import com.emfitsolutions.gopreach.platform.rememberPermissionRequester
import com.emfitsolutions.gopreach.platform.AppPermission
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PinDrop
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.koin.compose.viewmodel.koinViewModel
import com.emfitsolutions.gopreach.data.export.BoundaryKmlExporter
import com.emfitsolutions.gopreach.data.repository.AreaFeature
import com.emfitsolutions.gopreach.data.repository.Landmark
import com.emfitsolutions.gopreach.ui.components.map.NamedBoundary
import com.emfitsolutions.gopreach.ui.components.map.HideSystemBarsEffect
import com.emfitsolutions.gopreach.ui.components.map.OsmBoundaryMap
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * "If a barangay is selected show the boundary map" — a full-screen dialog
 * showing one barangay's real polygon boundary (same bundled OCHA/NAMRIA/PSA
 * data [com.emfitsolutions.gopreach.data.repository.TerritoryBoundaryRepository]
 * already supplies to Territory Map, just drawn on its own here instead of
 * inside that screen's full multi-layer map) on a native OpenStreetMap-backed
 * map ([OsmBoundaryMap] — no WebView, no bundled JS mapping library). Only
 * Nueva Vizcaya is bundled today — a barangay outside that coverage shows a
 * plain "not available yet" message rather than an error, the same
 * graceful-miss convention every other boundary lookup in this app follows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarangayBoundaryDialog(
    province: String,
    municipality: String,
    barangayName: String,
    boundaryColorHex: String? = null,
    onDismiss: () -> Unit,
    viewModel: TerritoryAssignmentsViewModel = koinViewModel(),
) {
    var geometryJson by remember(municipality, barangayName) { mutableStateOf<String?>(null) }
    var isLoading by remember(municipality, barangayName) { mutableStateOf(true) }
    var landmarks by remember(municipality, barangayName) { mutableStateOf<List<Landmark>>(emptyList()) }
    var areas by remember(municipality, barangayName) { mutableStateOf<List<AreaFeature>>(emptyList()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var myLocationFix by remember(municipality, barangayName) { mutableStateOf<Pair<Double, Double>?>(null) }
    var distanceLabel by remember(municipality, barangayName) { mutableStateOf<String?>(null) }
    var pointDistanceLabel by remember(municipality, barangayName) { mutableStateOf<String?>(null) }

    // "Show and hide my location" — [isLiveLocationOn] drives a continuous
    // subscription below, not a one-shot fetch, so the marker tracks the
    // device live instead of freezing at one fix.
    var isLiveLocationOn by remember(municipality, barangayName) { mutableStateOf(false) }
    var isPickModeOn by remember(municipality, barangayName) { mutableStateOf(false) }

    LaunchedEffect(province, municipality, barangayName) {
        isLoading = true
        geometryJson = viewModel.boundaryGeometry(province, municipality, barangayName)
        isLoading = false
        val details = geometryJson?.let { viewModel.mapDetailsFor(it) }
        landmarks = details?.landmarks ?: emptyList()
        areas = details?.areas ?: emptyList()
    }

    // "Add my location, then compare the distance to the selected barangay"
    // — a live fused-location subscription (same provider Share Location's
    // own live tracking uses); [myLocationFix] is fed straight into
    // [OsmBoundaryMap], which moves its own marker and reports the distance
    // back via [onDistanceToCenterComputed] (a true great-circle distance
    // computed from the real GeoJSON geometry, not re-derived in JS).
    LaunchedEffect(isLiveLocationOn) {
        if (!isLiveLocationOn) {
            myLocationFix = null
            distanceLabel = null
            pointDistanceLabel = null
            isPickModeOn = false
            return@LaunchedEffect
        }
        runCatching {
            viewModel.locationUpdates().collect { fix ->
                myLocationFix = fix.lat to fix.lng
            }
        }.onFailure {
            isLiveLocationOn = false
            snackbarHostState.showSnackbar("Could not get your current location. Make sure location is turned on and try again.")
        }
    }

    fun turnOnLiveLocation() {
        if (!viewModel.isLocationServicesEnabled()) {
            scope.launch { snackbarHostState.showSnackbar("Location services are disabled. Please enable GPS to continue.") }
            return
        }
        isLiveLocationOn = true
    }
    val permissionLauncher = rememberPermissionRequester() { granted ->
        if (granted) {
            turnOnLiveLocation()
        } else {
            scope.launch { snackbarHostState.showSnackbar("Location permission is required to show your location.") }
        }
    }
    fun toggleLiveLocation() {
        if (isLiveLocationOn) {
            isLiveLocationOn = false
        } else if (viewModel.hasLocationPermission()) {
            turnOnLiveLocation()
        } else {
            permissionLauncher.launch(AppPermission.LOCATION)
        }
    }

    // "Let the user select a point then calculate the distance from
    // location" — a second mode, independent of the barangay-distance
    // banner above: while on, tapping anywhere on the map drops a point
    // marker and reports its distance from the live location fix. Requires
    // live location already on, since a point is meaningless without a "from".
    fun togglePickMode() {
        if (!isLiveLocationOn) {
            scope.launch { snackbarHostState.showSnackbar("Turn on My Location first, then tap the map to pick a point.") }
            return
        }
        isPickModeOn = !isPickModeOn
        if (isPickModeOn) {
            scope.launch { snackbarHostState.showSnackbar("Tap anywhere on the map to measure distance from your location.") }
        } else {
            pointDistanceLabel = null
        }
    }

    // "If the selected point is clicked, show Google Maps — show the way
    // from my location" — a plain directions deep link (not a package-
    // specific intent for the Google Maps app) so it still works through
    // whatever the device resolves ACTION_VIEW maps URLs to (Google Maps if
    // installed, a browser fallback otherwise), same tolerant approach the
    // rest of this app takes for external links.
    fun openDirections(originLat: Double, originLng: Double, destLat: Double, destLng: Double) {
        val uri = Uri.parse(
            "https://www.google.com/maps/dir/?api=1&origin=$originLat,$originLng&destination=$destLat,$destLng&travelmode=driving",
        )
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { scope.launch { snackbarHostState.showSnackbar("Could not open Google Maps.") } }
    }

    // usePlatformDefaultWidth = false — the one flag that lets a Dialog's
    // content actually span the full screen instead of being capped to
    // Android's default dialog max-width, same as every other full-screen
    // Dialog in this app.
    var fullScreen by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { if (fullScreen) fullScreen = false else onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        HideSystemBarsEffect(fullScreen)
        Scaffold(
            topBar = {
                if (!fullScreen) TopAppBar(
                    title = {
                        Column {
                            Text(barangayName)
                            Text(municipality, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close")
                        }
                    },
                    actions = {
                        // "Can the line barrier also show in Google Maps?" —
                        // see BoundaryKmlExporter's own doc comment for why
                        // this can only ever be a manual KML import into
                        // Google My Maps (no API renders a custom polygon
                        // inside a maps deep link), not something automatic.
                        if (geometryJson != null) {
                            IconButton(
                                onClick = { BoundaryKmlExporter.share(context, geometryJson!!, barangayName, municipality) },
                            ) {
                                Icon(Icons.Rounded.Share, contentDescription = "Share boundary as KML")
                            }
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                if (!isLoading && geometryJson != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        FloatingActionButton(
                            onClick = { togglePickMode() },
                            containerColor = if (isPickModeOn) {
                                MaterialTheme.colorScheme.tertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                        ) {
                            Icon(
                                Icons.Rounded.PinDrop,
                                contentDescription = if (isPickModeOn) "Stop picking a point" else "Pick a point to measure distance",
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        FloatingActionButton(
                            onClick = { toggleLiveLocation() },
                            containerColor = if (isLiveLocationOn) {
                                MaterialTheme.colorScheme.tertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                        ) {
                            Icon(
                                if (isLiveLocationOn) Icons.Rounded.MyLocation else Icons.Rounded.LocationOff,
                                contentDescription = if (isLiveLocationOn) "Hide my location" else "Show my location",
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when {
                    isLoading -> Box(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    geometryJson == null -> Box(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No boundary map available for this barangay yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> Box(modifier = Modifier.fillMaxSize()) {
                        OsmBoundaryMap(
                            boundaries = listOf(NamedBoundary(barangayName, geometryJson!!)),
                            landmarks = landmarks,
                            areas = areas,
                            boundaryColorHex = boundaryColorHex,
                            exportTitle = "$barangayName $municipality",
                            fullScreen = fullScreen,
                            onFullScreenChange = { fullScreen = it },
                            myLocation = myLocationFix,
                            pickModeEnabled = isPickModeOn,
                            onDistanceToCenterComputed = { meters ->
                                distanceLabel = "📍 ${formatDistance(meters)} from $barangayName"
                            },
                            onPointDistanceComputed = { meters ->
                                pointDistanceLabel = "📏 ${formatDistance(meters)} to selected point"
                            },
                            onPointNeedsLocation = {
                                scope.launch { snackbarHostState.showSnackbar("Turn on My Location first.") }
                            },
                            onOpenDirections = { originLat, originLng, destLat, destLng ->
                                openDirections(originLat, originLng, destLat, destLng)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        Column(
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            distanceLabel?.let { label ->
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier
                                        .clip(MaterialTheme.shapes.medium)
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                            pointDistanceLabel?.let { label ->
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier
                                        .clip(MaterialTheme.shapes.medium)
                                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDistance(meters: Double): String =
    if (meters < 1000) "${meters.toInt()} m" else String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)
