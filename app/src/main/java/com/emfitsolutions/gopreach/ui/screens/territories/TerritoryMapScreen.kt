package com.emfitsolutions.gopreach.ui.screens.territories

import com.emfitsolutions.gopreach.platform.rememberPermissionRequester
import com.emfitsolutions.gopreach.platform.AppPermission
import com.emfitsolutions.gopreach.platform.rememberToaster
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Streetview
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Map
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.print.ReportPrinter
import com.emfitsolutions.gopreach.data.repository.MapPinResult
import com.emfitsolutions.gopreach.data.print.ReportTable
import com.emfitsolutions.gopreach.ui.components.GroupColorPalette
import com.emfitsolutions.gopreach.domain.map.DrawingAccess
import com.emfitsolutions.gopreach.ui.components.map.DrawingDock
import com.emfitsolutions.gopreach.domain.map.DrawingGeometry
import com.emfitsolutions.gopreach.domain.map.GeoPoint
import com.emfitsolutions.gopreach.ui.components.map.BoundaryGeometry
import com.emfitsolutions.gopreach.ui.components.map.StatusBar
import com.emfitsolutions.gopreach.ui.components.map.MapDrawingState
import com.emfitsolutions.gopreach.ui.components.map.MapDrawingViewModel
import com.emfitsolutions.gopreach.ui.components.map.TerritoryBoundaryInput
import com.emfitsolutions.gopreach.ui.components.map.territoryBoundaries
import com.emfitsolutions.gopreach.ui.components.map.MapLoadState
import com.emfitsolutions.gopreach.ui.screens.pipeline.PipelinePersonDetailScreen
import com.emfitsolutions.gopreach.ui.screens.pipeline.PipelineViewModel
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.BarangayBoundaryDialog
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private enum class GroupScope { MINE, OTHER, ALL }
private enum class ViewMode(val label: String) { MAP("Map"), LIST("List") }
private enum class SortOrder(val label: String) { NEAREST("Nearest"), FARTHEST("Farthest") }
private enum class TypeFilter(val label: String, val type: RecordType?) {
    ALL("All", null),
    SEARCHING("Searching", RecordType.SEARCHING),
    RETURN_VISIT("Return Visit", RecordType.RETURN_VISIT),
    BIBLE_STUDY("Bible Study", RecordType.BIBLE_STUDY),
}
private enum class LocationIssue { NO_PERMISSION, SERVICES_OFF, UNAVAILABLE }

/** Distances smaller than this are ignored, so GPS jitter doesn't constantly
 * re-sort the list and redraw the map. */
private const val MIN_MOVE_METERS = 2.0

private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

/** "350 m" under a kilometre, otherwise "1.4 km". */
private fun formatDistance(meters: Double?): String = when {
    meters == null -> "—"
    meters < 1000 -> "${meters.roundToInt()} m"
    else -> "${"%.1f".format(meters / 1000.0)} km"
}

private fun markerColor(type: RecordType): Color = Color(android.graphics.Color.parseColor(type.colorHex))

/**
 * Territory Map — a field-service location finder: open it, see your FS Group's
 * territories, see the nearest Searching / Return Visit / Bible Study
 * locations and how far away each is. MapLibre is the only map technology
 * used. A Publisher starts on "My FS Group" only; other groups' territories
 * load only when explicitly chosen. Scope is always limited to the
 * server-resolved [fixedCongregationId] (null = Super-Admin, who picks one).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerritoryMapScreen(
    fixedCongregationId: String?,
    currentPersonId: String,
    // Non-null only when reached via Share Location's "open in Territory Map".
    focusLat: Double? = null,
    focusLng: Double? = null,
    focusName: String? = null,
    onBack: () -> Unit,
    viewModel: TerritoryMapViewModel = koinViewModel(),
    pipelineViewModel: PipelineViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val toast = rememberToaster()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val isSuperAdmin = fixedCongregationId == null

    val coroutineScope = rememberCoroutineScope()
    fun openGoogleMaps(lat: Double, lng: Double) {
        // A plain search link, so a browser handles it if the Google Maps app is missing.
        val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lng")
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { toast("Couldn't open Google Maps.") }
    }

    // "View Details" opens the same full detail + Visit History screen the
    // Pipeline module uses.
    var detailsPerson by remember { mutableStateOf<InterestedPerson?>(null) }
    detailsPerson?.let { person ->
        val congregationName by remember(person.congregationId) { pipelineViewModel.congregationName(person.congregationId) }
            .collectAsStateWithLifecycle(initialValue = null)
        PipelinePersonDetailScreen(
            person = person,
            currentPersonId = currentPersonId,
            congregationName = congregationName ?: "—",
            stage = person.pipelineStage,
            canManageAllVisitHistory = false,
            onBack = { detailsPerson = null },
            viewModel = pipelineViewModel,
        )
        return
    }

    // ---- Congregation / FS Group scope ------------------------------------
    val congregations by viewModel.congregations().collectAsStateWithLifecycle(initialValue = emptyList())
    var pickedCongregationId by rememberSaveable { mutableStateOf<String?>(null) }
    val congregationId = fixedCongregationId ?: pickedCongregationId

    val myGroupId by remember(currentPersonId) { viewModel.myGroupId(currentPersonId) }.collectAsStateWithLifecycle(initialValue = null)
    val groups by remember(congregationId) { viewModel.groupsFor(congregationId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val hasMine = !isSuperAdmin && myGroupId != null
    var scope by rememberSaveable { mutableStateOf(GroupScope.MINE) }
    var otherGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var showGroupPicker by rememberSaveable { mutableStateOf(false) }
    // Province -> Municipality -> Barangay selection (kept across the group scopes: picking a Barangay shows the whole
    // Barangay, every FS Group's part of it) plus the FS Group filter that applies once a Barangay is picked.
    var provinceFilter by rememberSaveable(congregationId) { mutableStateOf<String?>(null) }
    var municipalityFilter by rememberSaveable(congregationId) { mutableStateOf<String?>(null) }
    var territoryFilter by rememberSaveable(congregationId) { mutableStateOf<String?>(null) }
    var barangayGroupFilter by rememberSaveable(congregationId) { mutableStateOf<String?>(null) }
    // The three FS Group viewing modes: My FS Group / Other FS Group / Show All FS Groups.
    val effectiveScope = when {
        scope == GroupScope.ALL -> GroupScope.ALL
        territoryFilter != null -> GroupScope.ALL // a selected Barangay shows every FS Group's drawings and records in it
        hasMine -> scope
        else -> GroupScope.OTHER
    }
    val showingAll = effectiveScope == GroupScope.ALL
    val selectedGroupId = when (effectiveScope) {
        GroupScope.MINE -> myGroupId
        GroupScope.OTHER -> otherGroupId
        GroupScope.ALL -> null
    }
    val selectedGroup = groups.firstOrNull { it.id == selectedGroupId }
    // Identifies the dataset on screen: one group, or every group of the congregation.
    // null = nothing chosen yet. Everything loaded below is tagged with it, so the
    // datasets of different modes can never be mixed while switching.
    val datasetKey: String? = if (showingAll) congregationId?.let { "ALL|$it" } else selectedGroupId
    // Each FS Group's own color code (set on its Group record), falling back to the
    // same generated color the rest of the app uses for a group without one.
    val groupColors = groups.associate { g ->
        g.id to (g.color?.takeIf { runCatching { android.graphics.Color.parseColor(it) }.isSuccess } ?: GroupColorPalette.colorForGroupId(g.id))
    }
    val groupColorHex = groupColors[selectedGroupId] ?: GroupColorPalette.colorForGroupId(selectedGroupId.orEmpty())

    // ---- Pins (long-press -> Create a Pin), saved online --------------------
    val pins by remember(congregationId) { viewModel.pinsFor(congregationId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var longPressPoint by remember { mutableStateOf<Pair<Double, Double>?>(null) } // the "what to do here?" choice
    var pinDraftPoint by remember { mutableStateOf<Pair<Double, Double>?>(null) } // a pin being written
    var pinDraftText by remember { mutableStateOf("") }
    var isSavingPin by remember { mutableStateOf(false) }
    var selectedPinId by remember { mutableStateOf<String?>(null) }
    var stackIds by remember { mutableStateOf<List<String>?>(null) } // records sharing one spot (a stack marker was tapped)
    var barangayDialogArea by remember { mutableStateOf<TerritoryArea?>(null) }

    // ---- Territories + records of the selected group only -----------------
    // Every loaded dataset is tagged with the group it was loaded for, and only
    // data for the group on screen *now* is used: while switching groups the
    // state still holds the previous group's value until the new flow emits,
    // and that must never be shown (or sorted/measured) as the new group's.
    val areasTagged by remember(datasetKey) {
        datasetKey?.let { key ->
            // One group's territories, or (Show All FS Groups) every group's in this congregation.
            val flow = if (showingAll) viewModel.areasFor(null, congregationId) else viewModel.areasFor(selectedGroupId)
            flow.map { key to it }
        } ?: flowOf<Pair<String, List<TerritoryArea>>?>(null)
    }.collectAsStateWithLifecycle(initialValue = null)
    val areasState: List<TerritoryArea>? = areasTagged?.takeIf { it.first == datasetKey }?.second
    val areas = areasState.orEmpty()
    // Every claimed Barangay of the congregation (all FS Groups) — what the Province/Municipality/Barangay pickers list.
    val allAreas by remember(congregationId) {
        if (congregationId == null) flowOf(emptyList<TerritoryArea>()) else viewModel.areasFor(null, congregationId)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    // Boundaries load lazily, only for the territories of what is on screen.
    val boundaryJson = remember(datasetKey) { mutableStateMapOf<String, String>() }
    var attemptedAreaIds by remember(datasetKey) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(areasState) {
        areas.filter { it.id !in attemptedAreaIds }.forEach { area ->
            launch {
                viewModel.boundaryFor(area)?.let { boundaryJson[area.id] = it }
                attemptedAreaIds = attemptedAreaIds + area.id
            }
        }
    }

    // Which loaded territory a GPS point lies inside (so a record is placed by WHERE it is, not only by the barangay name typed on it).
    val areaLocator: (Double, Double) -> TerritoryArea? = remember(boundaryJson.size, areasState) {
        val rings = areas.mapNotNull { a ->
            boundaryJson[a.id]?.let { json -> a to BoundaryGeometry.outerRings(json).filter { it.size >= 3 }.map { r -> r.map { (lat, lng) -> GeoPoint(lat, lng) } } }
        }
        val locate: (Double, Double) -> TerritoryArea? = { lat, lng -> val p = GeoPoint(lat, lng); rings.firstOrNull { (_, rs) -> rs.any { DrawingGeometry.contains(it, p) } }?.first }
        locate
    }
    val recordsTagged by remember(congregationId, datasetKey, areasState, areaLocator) {
        val key = datasetKey
        val a = areasState
        if (key == null || a == null) flowOf<Pair<String, List<LocationRecord>>?>(null)
        else viewModel.recordsFor(congregationId, if (showingAll) null else selectedGroupId, a, areaLocator).map { key to it }
    }.collectAsStateWithLifecycle(initialValue = null)
    val recordsState: List<LocationRecord>? = recordsTagged?.takeIf { it.first == datasetKey }?.second
    val isLoading = datasetKey != null && (areasState == null || recordsState == null)

    // ---- Drawing (long-press -> Drawing Mode; shared with every map module) -------
    val drawingViewModel: MapDrawingViewModel = koinViewModel()
    val drawingAccess by remember(currentPersonId) { drawingViewModel.access(currentPersonId) }
        .collectAsStateWithLifecycle(initialValue = DrawingAccess.NONE)
    // Admin / Service Overseer / Coordinator Elder / Secretary open on "Show FS Group" (every group of the congregation) once, as the initial choice.
    var initialScopeApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(drawingAccess.scope) {
        if (!initialScopeApplied && drawingAccess.scope is com.emfitsolutions.gopreach.domain.GroupAccessScope.Congregation) {
            scope = GroupScope.ALL
            initialScopeApplied = true
        }
    }
    val drawingState = remember { MapDrawingState() }
    val allDrawings by remember { drawingViewModel.drawings() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val boundarySnapshot = boundaryJson.toMap()
    // The drawings on the map. With a Barangay selected: EVERY drawing in it — whoever drew it, whichever FS Group it
    // belongs to — i.e. made on that Barangay's territory or lying inside its boundary, narrowed by the FS Group filter.
    // (Seeing a drawing is not permission to edit it: that is decided per drawing by DrawingAccess.canManage.)
    val visibleDrawings = remember(allDrawings, areas, congregationId, territoryFilter, barangayGroupFilter, boundarySnapshot) {
        if (territoryFilter != null) {
            val rings = boundarySnapshot[territoryFilter]?.let { json ->
                BoundaryGeometry.outerRings(json).filter { it.size >= 3 }.map { r -> r.map { (lat, lng) -> GeoPoint(lat, lng) } }
            }.orEmpty()
            val selArea = areas.firstOrNull { it.id == territoryFilter }
            allDrawings.filter { d ->
                d.congregationId == congregationId &&
                    (barangayGroupFilter == null || d.groupId == barangayGroupFilter) &&
                    (
                        d.territoryId == territoryFilter ||
                            (d.barangayId != 0 && d.barangayId == selArea?.barangayId && d.muncityId == selArea.muncityId) ||
                            DrawingGeometry.parsePolygon(d.geometryJson)?.let { ring ->
                                val p = DrawingGeometry.interiorPoint(ring)
                                rings.any { DrawingGeometry.contains(it, p) }
                            } == true
                        )
            }
        } else {
            val ids = areas.map { it.id }.toSet()
            allDrawings.filter { it.territoryId in ids || (it.territoryId.isBlank() && it.congregationId == congregationId) }
        }
    }
    val statusCounts = remember(visibleDrawings) {
        val c = visibleDrawings.groupingBy { it.status }.eachCount()
        com.emfitsolutions.gopreach.data.model.DrawingStatus.entries.associateWith { c[it] ?: 0 }
    }
    // The territories on screen, with their real boundaries — what a drawing is validated against.
    val drawingTerritories = remember(areas, boundarySnapshot, congregationId) {
        territoryBoundaries(
            congregationId.orEmpty(),
            areas.mapNotNull { a -> boundarySnapshot[a.id]?.let { TerritoryBoundaryInput(a.id, a.barangay, a.groupId, it, a.provinceId, a.muncityId, a.barangayId) } },
        )
    }

    // ---- Current location --------------------------------------------------
    var myLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var myAccuracy by remember { mutableStateOf<Float?>(null) }
    var permissionAsked by rememberSaveable { mutableStateOf(false) }
    var locationIssue by remember { mutableStateOf<LocationIssue?>(null) }
    var locationRefreshKey by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberPermissionRequester() { locationRefreshKey++ }
    LaunchedEffect(locationRefreshKey) {
        if (!viewModel.hasLocationPermission()) {
            locationIssue = LocationIssue.NO_PERMISSION
            if (locationRefreshKey == 0) { permissionAsked = true; permissionLauncher.launch(AppPermission.LOCATION) }
            return@LaunchedEffect
        }
        if (!viewModel.isLocationServicesEnabled()) {
            locationIssue = LocationIssue.SERVICES_OFF
            return@LaunchedEffect
        }
        runCatching { viewModel.currentLocation() }.getOrNull()?.let { myLocation = it.lat to it.lng; myAccuracy = it.accuracyMeters; locationIssue = null }
            ?: run { if (myLocation == null) locationIssue = LocationIssue.UNAVAILABLE }
        // Live updates only while this screen is visible; small moves are ignored.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            runCatching {
                viewModel.locationUpdates().collect { fix ->
                    val prev = myLocation
                    if (prev == null || haversineMeters(prev.first, prev.second, fix.lat, fix.lng) >= MIN_MOVE_METERS) {
                        myLocation = fix.lat to fix.lng
                        myAccuracy = fix.accuracyMeters
                    }
                    locationIssue = null
                }
            }
        }
    }

    // ---- Filters / ordering / view mode -----------------------------------
    var typeFilter by rememberSaveable { mutableStateOf(TypeFilter.ALL) }
    // Nothing is auto-selected on load. "Show Next Nearest/Farthest" steps through the
    // current (filtered) records by distance; stepping restarts whenever a filter changes.
    var stepOrder by remember(datasetKey, typeFilter, municipalityFilter, territoryFilter) { mutableStateOf<SortOrder?>(null) }
    var stepIndex by remember(datasetKey, typeFilter, municipalityFilter, territoryFilter) { mutableIntStateOf(-1) }
    var viewMode by rememberSaveable { mutableStateOf(ViewMode.MAP) }
    // Full-screen Map View: hides the app bar, the controls above the map and
    // the system bars so the map fills the display; Back exits it first.
    // The Territory Map opens in full screen (the controls come back with Exit); the user can leave it at any time.
    var fullScreen by rememberSaveable { mutableStateOf(true) }
    val fullScreenActive = fullScreen && viewMode == ViewMode.MAP
    BackHandler(enabled = fullScreenActive) { fullScreen = false }
    DisposableEffect(fullScreenActive) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (fullScreenActive) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    var basemap by rememberSaveable { mutableStateOf(TerritoryBasemap.STANDARD) }
    var recenterToken by remember { mutableIntStateOf(0) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var mapLoadState by remember { mutableStateOf(MapLoadState.LOADING) }

    // ---- Re-center ("My location"): camera only — never touches the selected group / barangay / permissions ------------
    var locationHelp by remember { mutableStateOf<LocationIssue?>(null) } // a "go to settings" explanation
    fun requestLocationPermission() {
        permissionAsked = true
        permissionLauncher.launch(AppPermission.LOCATION)
    }
    fun recenter() {
        when {
            !viewModel.hasLocationPermission() -> {
                val activity = context as? Activity
                val canAskAgain = activity == null || !permissionAsked ||
                    androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                if (canAskAgain) {
                    toast("Location permission is needed to re-center the map on you.")
                    requestLocationPermission()
                } else {
                    // Permanently denied: the system will not ask again — offer the app's settings.
                    locationHelp = LocationIssue.NO_PERMISSION
                }
            }
            !viewModel.isLocationServicesEnabled() -> locationHelp = LocationIssue.SERVICES_OFF
            myLocation == null -> {
                // Non-blocking: the map stays usable while the location is found.
                toast("Your location isn't available yet. Looking for it...")
                locationRefreshKey++
            }
            else -> recenterToken++
        }
    }
    locationHelp?.let { issue ->
        AlertDialog(
            onDismissRequest = { locationHelp = null },
            title = { Text(if (issue == LocationIssue.NO_PERMISSION) "Location permission needed" else "Location is turned off") },
            text = {
                Text(
                    if (issue == LocationIssue.NO_PERMISSION) "Allow location for GoPreach in the app settings to use Re-center and see where you are on the map."
                    else "Turn on your device's location to use Re-center and see where you are on the map.",
                )
            },
            dismissButton = { TextButton(onClick = { locationHelp = null }) { Text("Not now") } },
            confirmButton = {
                TextButton(onClick = {
                    locationHelp = null
                    val intent = if (issue == LocationIssue.NO_PERMISSION) {
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    } else {
                        Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    }
                    runCatching { context.startActivity(intent) }
                }) { Text("Open Settings") }
            },
        )
    }

    val visibleAreas = areas.filter { a ->
        (provinceFilter == null || normalizePlaceName(a.provinceName) == normalizePlaceName(provinceFilter)) &&
        (municipalityFilter == null || normalizePlaceName(a.municipality) == normalizePlaceName(municipalityFilter)) &&
            (territoryFilter == null || a.id == territoryFilter)
    }
    val visibleRecords = recordsState.orEmpty().filter { r ->
        (typeFilter.type == null || r.type == typeFilter.type) &&
            (municipalityFilter == null || normalizePlaceName(r.municipality) == normalizePlaceName(municipalityFilter)) &&
            (territoryFilter == null || (r.areaId == territoryFilter && (barangayGroupFilter == null || r.groupId == barangayGroupFilter)))
    }
    val here = myLocation
    val recordsWithDistance = visibleRecords
        .map { RecordWithDistance(it, here?.let { h -> haversineMeters(h.first, h.second, it.lat, it.lng) }) }
        // Default order is simply by type, then name — nothing is sorted by distance (or picked) until asked.
        .sortedWith(compareBy({ if (showingAll) it.record.groupName.orEmpty().lowercase() else "" }, { it.record.type.ordinal }, { it.record.name.lowercase() }))
    val nearestOrdered = recordsWithDistance.filter { it.meters != null }.sortedBy { it.meters }
    val farthestOrdered = nearestOrdered.reversed()

    var selectedRecordId by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var flyToRecord by remember { mutableStateOf<LocationRecord?>(null) }
    val selectedRecord = recordsWithDistance.firstOrNull { it.record.id == selectedRecordId }
    fun selectRecord(id: String, fly: Boolean, openSheet: Boolean = true) {
        selectedRecordId = id
        sheetOpen = openSheet
        if (fly) flyToRecord = recordsWithDistance.firstOrNull { it.record.id == id }?.record
    }
    // "Show Next Nearest/Farthest": the next record by distance from the current location,
    // within the current filters; wraps around after the last one.
    fun stepTo(order: SortOrder) {
        if (myLocation == null) {
            toast("Turn on your location to find the nearest or farthest.")
            locationRefreshKey++
            return
        }
        val ordered = if (order == SortOrder.NEAREST) nearestOrdered else farthestOrdered
        if (ordered.isEmpty()) {
            toast("No locations to show.")
            return
        }
        val next = if (stepOrder == order) (stepIndex + 1) % ordered.size else 0
        stepOrder = order
        stepIndex = next
        selectRecord(ordered[next].record.id, fly = true, openSheet = false)
    }
    // "Print / PDF" of the list as shown, through the system print dialog (Save as PDF).
    fun printList() {
        ReportPrinter.print(
            context,
            ReportTable(
                title = "Territory Map - " + (selectedGroup?.name ?: "FS Group"),
                count = recordsWithDistance.size,
                countLabel = "Total Records",
                columns = listOf("Type", "Name", "Barangay", "Municipality", "Distance", "FS Group"),
                rows = recordsWithDistance.map {
                    listOf(it.record.type.label, it.record.name, it.record.barangay.orEmpty(), it.record.municipality.orEmpty(), formatDistance(it.meters), it.record.groupName.orEmpty())
                },
                totals = RecordType.entries.map { t -> t.label to recordsWithDistance.count { it.record.type == t }.toString() },
            ),
        )
    }
    // A new group or filter starts clean: nothing selected.
    LaunchedEffect(datasetKey, typeFilter, municipalityFilter, territoryFilter) {
        selectedRecordId = null
        sheetOpen = false
    }
    var selectedAreaId by rememberSaveable(congregationId) { mutableStateOf<String?>(null) }
    // ---- Single-Barangay navigation -----------------------------------------------
    // Selecting a Barangay remembers the view it came from; Back returns one level up — never wider than the user's access:
    //  - congregation-wide roles / Super Admin (authorized for FS Groups): the "Show FS Group" list view (their authorized FS Groups);
    //  - everyone else: the view they came from (a Group Overseer/Servant/Assistant: their own FS Group's territory).
    // Province / Municipality and the type filter are kept; the map re-frames to the level shown.
    var scopeBeforeBarangay by rememberSaveable { mutableStateOf<GroupScope?>(null) }
    val hasFsGroupOverview = drawingAccess.scope is com.emfitsolutions.gopreach.domain.GroupAccessScope.Congregation ||
        drawingAccess.scope == com.emfitsolutions.gopreach.domain.GroupAccessScope.AllCongregations
    fun selectBarangay(id: String) {
        if (territoryFilter == null) scopeBeforeBarangay = scope
        territoryFilter = id
        selectedAreaId = id
        barangayGroupFilter = null
    }
    fun backFromBarangay() {
        territoryFilter = null
        selectedAreaId = null
        barangayGroupFilter = null
        scope = if (hasFsGroupOverview) GroupScope.ALL else (scopeBeforeBarangay ?: if (hasMine) GroupScope.MINE else GroupScope.OTHER)
        scopeBeforeBarangay = null
    }
    BackHandler(enabled = territoryFilter != null && !drawingState.active) { if (fullScreenActive) fullScreen = false else backFromBarangay() }

    // Re-fit when a boundary finishes loading too, so a selected Barangay is always framed whole (fit-to-screen).
    val fitKey = "$datasetKey|${typeFilter.name}|$municipalityFilter|$territoryFilter|${barangayGroupFilter}|b${visibleAreas.count { it.id in boundaryJson }}"
    val fitReady = !isLoading && datasetKey != null && visibleAreas.all { it.id in attemptedAreaIds }
    val focus = if (focusLat != null && focusLng != null) Triple(focusLat, focusLng, focusName.orEmpty()) else null

    Scaffold(
        topBar = {
            if (!fullScreenActive) {
                TopAppBar(
                    title = { Text("Territory Map") },
                    navigationIcon = {
                        IconButton(onClick = { if (territoryFilter != null && !drawingState.active) backFromBarangay() else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                    },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Congregation (Super-Admin only), then My FS Group / Other FS Groups.
            if (!fullScreenActive) Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isSuperAdmin) {
                    DropdownChip(
                        label = "Congregation",
                        value = congregations.firstOrNull { it.id == pickedCongregationId }?.name,
                        placeholder = "Select a congregation",
                        options = congregations.map { it.id to it.name },
                        onSelect = { pickedCongregationId = it; otherGroupId = null; selectedRecordId = null },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // FS GROUP selector: My FS Group / Other FS Group / Show All FS Groups. The active
                // option is the filled one; "My FS Group" only exists for someone who belongs to a group.
                val scopeOptions = buildList {
                    if (hasMine) add(GroupScope.MINE)
                    add(GroupScope.OTHER)
                    add(GroupScope.ALL)
                }
                SegmentedPair(
                    icons = scopeOptions.map { when (it) { GroupScope.MINE -> Icons.Rounded.Person; GroupScope.OTHER -> Icons.Rounded.Groups; GroupScope.ALL -> Icons.Rounded.Public } },
                    labels = scopeOptions.map {
                        when (it) {
                            GroupScope.MINE -> "My FS Group"
                            GroupScope.OTHER -> "Other FS Group"
                            GroupScope.ALL -> "Show FS Group"
                        }
                    },
                    selected = scopeOptions.indexOf(effectiveScope).coerceAtLeast(0),
                    onSelect = { index ->
                        when (scopeOptions[index]) {
                            GroupScope.MINE -> scope = GroupScope.MINE
                            GroupScope.OTHER -> if (otherGroupId == null) showGroupPicker = true else scope = GroupScope.OTHER
                            GroupScope.ALL -> scope = GroupScope.ALL
                        }
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selectedGroup != null) {
                        Box(
                            Modifier.padding(end = 8.dp).size(16.dp).clip(CircleShape)
                                .background(Color(android.graphics.Color.parseColor(groupColorHex))),
                        )
                    }
                    Text(
                        when {
                            showingAll -> "FS Group: All Groups"
                            selectedGroup != null -> if (effectiveScope == GroupScope.MINE) "My FS Group · " + selectedGroup.name else selectedGroup.name
                            else -> "No FS Group selected"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (effectiveScope == GroupScope.OTHER && (!isSuperAdmin || congregationId != null)) {
                        TextButton(onClick = { showGroupPicker = true }) { Text(if (selectedGroup == null) "Choose FS Group" else "Change") }
                    }
                }
                // Compact controls: view switcher and nearest/farthest stepping, wrapping instead of scrolling on narrow screens.
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SegmentedPair(
                        labels = ViewMode.entries.map { it.label },
                        icons = listOf(Icons.Rounded.Map, Icons.AutoMirrored.Rounded.ViewList),
                        selected = viewMode.ordinal,
                        onSelect = { viewMode = ViewMode.entries[it] },
                    )
                    SegmentedPair(
                        labels = listOf("Next Nearest", "Next Farthest"),
                        icons = listOf(Icons.Rounded.NearMe, Icons.Rounded.Explore),
                        selected = when (stepOrder) { SortOrder.NEAREST -> 0; SortOrder.FARTHEST -> 1; else -> -1 },
                        onSelect = { stepTo(if (it == 0) SortOrder.NEAREST else SortOrder.FARTHEST) },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TypeFilter.entries.forEach { f ->
                        FilterChip(selected = typeFilter == f, onClick = { typeFilter = f }, label = { Text(f.label) })
                    }
                    // Province -> Municipality -> Barangay, over every claimed Barangay of the congregation. Picking a
                    // Barangay zooms to it and shows everything in it (all FS Groups), subject to the same viewing rules.
                    val provinces = allAreas.map { it.provinceName }.filter { it.isNotBlank() }.distinct()
                    if (provinces.size > 1) {
                        DropdownChip(
                            label = "Province",
                            value = provinceFilter,
                            placeholder = "Province",
                            options = listOf<Pair<String?, String>>(null to "All provinces") + provinces.map { it to it },
                            onSelect = { provinceFilter = it; municipalityFilter = null; territoryFilter = null; barangayGroupFilter = null; selectedAreaId = null },
                        )
                    }
                    val inProvince = allAreas.filter { provinceFilter == null || normalizePlaceName(it.provinceName) == normalizePlaceName(provinceFilter) }
                    val municipalities = inProvince.map { it.municipality }.distinct()
                    if (municipalities.size > 1) {
                        DropdownChip(
                            label = "Municipality",
                            value = municipalityFilter,
                            placeholder = "Municipality",
                            options = listOf<Pair<String?, String>>(null to "All municipalities") + municipalities.map { it to it },
                            onSelect = { municipalityFilter = it; territoryFilter = null; barangayGroupFilter = null; selectedAreaId = null },
                        )
                    }
                    val barangayChoices = inProvince.filter { municipalityFilter == null || normalizePlaceName(it.municipality) == normalizePlaceName(municipalityFilter) }
                    if (barangayChoices.isNotEmpty()) {
                        DropdownChip(
                            label = "Barangay",
                            value = barangayChoices.firstOrNull { it.id == territoryFilter }?.barangay,
                            placeholder = "Barangay",
                            options = listOf<Pair<String?, String>>(null to "All barangays") +
                                barangayChoices.sortedWith(compareBy({ it.municipality.lowercase() }, { it.barangay.lowercase() }))
                                    .map { it.id to (if (municipalityFilter == null) "${it.barangay} · ${it.municipality}" else it.barangay) },
                            onSelect = { if (it == null) backFromBarangay() else selectBarangay(it) },
                        )
                    }
                    // FS Group filter for the selected Barangay: all permitted FS Groups, or one.
                    if (territoryFilter != null && groups.isNotEmpty()) {
                        DropdownChip(
                            label = "FS Group",
                            value = groups.firstOrNull { it.id == barangayGroupFilter }?.name,
                            placeholder = "All FS Groups",
                            options = listOf<Pair<String?, String>>(null to "All FS Groups") + groups.map { it.id to it.name },
                            onSelect = { barangayGroupFilter = it },
                        )
                    }
                }
                // Selected Barangay summary (counts follow the FS Group filter; they are what this user can see).
                if (territoryFilter != null) {
                    val inBarangay = recordsState.orEmpty().filter { r -> r.areaId == territoryFilter && (barangayGroupFilter == null || r.groupId == barangayGroupFilter) }
                    val byType = inBarangay.groupingBy { it.type }.eachCount()
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text("Barangay: " + (allAreas.firstOrNull { it.id == territoryFilter }?.barangay ?: ""), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("Territory Drawings: ${visibleDrawings.size}", style = MaterialTheme.typography.bodySmall)
                                Text("Search Records: ${byType[RecordType.SEARCHING] ?: 0}", style = MaterialTheme.typography.bodySmall)
                                Text("Return Visits: ${byType[RecordType.RETURN_VISIT] ?: 0}", style = MaterialTheme.typography.bodySmall)
                                Text("Bible Studies: ${byType[RecordType.BIBLE_STUDY] ?: 0}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                // Territory Status + filter: a single row up here, never over the map.
                if (!drawingState.active && (drawingAccess.canUseDrawingTools || statusCounts.values.any { it > 0 })) {
                    StatusBar(counts = statusCounts, filter = drawingState.statusFilter, onFilter = { drawingState.statusFilter = it })
                }
                locationIssue?.let { issue ->
                    LocationBanner(
                        issue = issue,
                        onAction = {
                            if (issue == LocationIssue.NO_PERMISSION) {
                                permissionLauncher.launch(AppPermission.LOCATION)
                            } else {
                                locationRefreshKey++
                            }
                        },
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (viewMode == ViewMode.MAP) {
                    TerritoryMapCanvas(
                        areas = visibleAreas,
                        // A snapshot copy: the canvas keys its effects on this value,
                        // so it must change (by equality) when a boundary arrives.
                        boundaryJson = boundaryJson.toMap(),
                        areaColors = visibleAreas.associate { a -> a.id to (groupColors[a.groupId] ?: GroupColorPalette.colorForGroupId(a.groupId)) },
                        groupColors = groupColors,
                        showAllGroups = showingAll,
                        records = recordsWithDistance,
                        selectedAreaId = selectedAreaId,
                        selectedRecordId = selectedRecordId,
                        myLocation = myLocation,
                        myAccuracyMeters = myAccuracy,
                        focus = focus,
                        basemap = basemap,
                        reloadToken = reloadToken,
                        fitKey = fitKey,
                        fitReady = fitReady,
                        recenterToken = recenterToken,
                        flyToRecord = flyToRecord,
                        onRecordTap = { selectRecord(it, fly = false) },
                        // Tapping a barangay SELECTS it: the map zooms to it and shows everything in it - every FS Group's
                        // drawings plus its Search / Return Visit / Bible Study records - right on this map.
                        onAreaTap = { id ->
                            areas.firstOrNull { it.id == id }?.let { a ->
                                selectBarangay(id)
                            }
                        },
                        // Long-press asks: Create a Pin, or Open Google Maps at that exact spot.
                        // For someone who may draw, a long press turns Drawing Mode on; everyone else keeps the old choice.
                        // Long press: a small menu — Draw (only where the role allows it) and Open Google Maps.
                        onLongPress = { lat, lng -> if (!drawingState.active) longPressPoint = lat to lng },
                        drawingState = drawingState,
                        drawingAccess = drawingAccess,
                        drawingTerritories = drawingTerritories,
                        drawingCongregationId = congregationId.orEmpty(),
                        hideMarkers = drawingState.markersHidden,
                        onStackTap = { stackIds = it },
                        drawings = visibleDrawings,
                        pins = pins,
                        onPinTap = { selectedPinId = it },
                        onLoadStateChange = { mapLoadState = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Map chrome: basemap + my-location buttons.
                    Column(
                        // Kept clear of the status bar / camera cut-out whenever the system bars are showing, so a tap never lands on them.
                        modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.Companion.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Top)).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        MapToolButton(
                            icon = if (fullScreenActive) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                            label = if (fullScreenActive) "Exit" else "Full screen",
                            onClick = { fullScreen = !fullScreen },
                        )
                        // Hide / Show Icons: one setting for every Territory Map view (all groups, a group, a municipality, a Barangay).
                        MapToolButton(
                            icon = if (drawingState.markersHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                            label = if (drawingState.markersHidden) "Show Icons" else "Hide Icons",
                            active = drawingState.markersHidden,
                            onClick = { drawingState.markersHidden = !drawingState.markersHidden },
                        )
                        MapToolButton(
                            icon = Icons.Rounded.Layers,
                            label = basemap.label,
                            onClick = { basemap = TerritoryBasemap.entries[(basemap.ordinal + 1) % TerritoryBasemap.entries.size] },
                        )
                        MapToolButton(
                            icon = Icons.Rounded.MyLocation,
                            label = "My location",
                            onClick = { recenter() },
                        )
                    }
                    // Full screen: the controls above the map are hidden, so keep the
                    // essentials floating on it — exit, which group this is, and the
                    // record-type filter.
                    if (fullScreenActive) {
                        Column(
                            modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.Companion.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Top)).padding(10.dp).fillMaxWidth(0.82f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MapToolButton(icon = Icons.AutoMirrored.Rounded.ArrowBack, label = "Exit", onClick = { fullScreen = false })
                                if (selectedGroup != null) {
                                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), shadowElevation = 3.dp) {
                                        Row(
                                            modifier = Modifier.height(40.dp).padding(horizontal = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Box(Modifier.size(14.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(groupColorHex))))
                                            Text(selectedGroup.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TypeFilter.entries.forEach { f ->
                                    FilterChip(selected = typeFilter == f, onClick = { typeFilter = f }, label = { Text(f.label) })
                                }
                            }
                        }
                    }
                    if (datasetKey != null && !isLoading && mapLoadState == MapLoadState.LOADED && !drawingState.active && drawingState.selectedDrawingId == null) {
                        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                            if (areas.isEmpty()) {
                                MessageCard("No territories have been assigned to this FS Group yet.", Modifier.padding(12.dp))
                            } else if (recordsWithDistance.isEmpty()) {
                                MessageCard("No Searching, Return Visit, or Bible Study locations found.", Modifier.padding(12.dp))
                            } else {
                                val stepped = recordsWithDistance.firstOrNull { it.record.id == selectedRecordId }
                                val steppedOrder = stepOrder
                                if (stepped != null && steppedOrder != null && !sheetOpen) {
                                    val total = (if (steppedOrder == SortOrder.NEAREST) nearestOrdered else farthestOrdered).size
                                    StepCard(
                                        item = stepped,
                                        label = (if (steppedOrder == SortOrder.NEAREST) "Nearest" else "Farthest") + " · " + (stepIndex + 1) + " of " + total,
                                        onClick = { sheetOpen = true },
                                    )
                                } else {
                                    SummaryStrip(records = recordsWithDistance, hasLocation = myLocation != null)
                                }
                            }
                        }
                    }
                    when {
                        datasetKey == null -> CenterState {
                            Text(
                                if (isSuperAdmin && congregationId == null) "Select a congregation first to see its FS Groups' territories."
                                else if (showingAll) "Loading all FS Groups' territories."
                                else "Choose an FS Group to see its territories.",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (!isSuperAdmin || congregationId != null) {
                                Button(onClick = { showGroupPicker = true }) { Text("Choose FS Group") }
                            }
                        }
                        isLoading || mapLoadState == MapLoadState.LOADING -> Box(
                            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                        mapLoadState == MapLoadState.FAILED -> CenterState {
                            Text("The map couldn't be loaded. Check your internet connection and try again.", style = MaterialTheme.typography.bodyLarge)
                            Button(onClick = { reloadToken++ }) { Text("Retry") }
                        }
                    }
                } else {
                    when {
                        datasetKey == null -> CenterState {
                            Text("Choose an FS Group to see its locations.", style = MaterialTheme.typography.bodyLarge)
                            if (!isSuperAdmin || congregationId != null) {
                                Button(onClick = { showGroupPicker = true }) { Text("Choose FS Group") }
                            }
                        }
                        isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        areas.isEmpty() -> CenterState { Text("No territories have been assigned to this FS Group yet.", style = MaterialTheme.typography.bodyLarge) }
                        recordsWithDistance.isEmpty() -> CenterState {
                            Text("No Searching, Return Visit, or Bible Study locations found.", style = MaterialTheme.typography.bodyLarge)
                        }
                        else -> RecordListView(
                            sorted = recordsWithDistance,
                            hasLocation = myLocation != null,
                            showAllGroups = showingAll,
                            groupColors = groupColors,
                            selectedId = if (stepOrder != null) selectedRecordId else null,
                            onClick = { selectRecord(it, fly = false) },
                            onPrint = { printList() },
                        )
                    }
                }
            }
            // Drawing controls (status, remarks, tools, save) live here, BELOW the map, so the canvas is never covered.
            DrawingDock(drawingState)
        }
    }


    // A tapped barangay: that barangay only, in its FS Group's color.
    barangayDialogArea?.let { a ->
        BarangayBoundaryDialog(
            province = a.provinceName,
            municipality = a.municipality,
            barangayName = a.barangay,
            boundaryColorHex = groupColors[a.groupId],
            onDismiss = { barangayDialogArea = null; selectedAreaId = null },
        )
    }

    // Long-press: what to do with this spot.
    longPressPoint?.let { (lat, lng) ->
        AlertDialog(
            onDismissRequest = { longPressPoint = null },
            title = { Text("What would you like to do?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("%.5f, %.5f".format(lat, lng), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val here = com.emfitsolutions.gopreach.domain.map.GeoPoint(lat, lng)
                    val canDraw = congregationId != null && !isLoading && drawingAccess.canDrawAt(here, drawingTerritories, congregationId)
                    // Draw is offered only where this role may draw (inside the assigned territory for group-level users,
                    // anywhere on their congregation's map for the wider roles); Open Google Maps is always available.
                    if (canDraw) {
                        Button(
                            onClick = { longPressPoint = null; drawingState.enter(here) },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) { Text("Draw") }
                    }
                    OutlinedButton(
                        onClick = { longPressPoint = null; openGoogleMaps(lat, lng) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("Open Google Maps") }
                    TextButton(onClick = { longPressPoint = null }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }
            },
            confirmButton = {},
        )
    }

    // Create a Pin: a short text mark, saved online.
    pinDraftPoint?.let { (lat, lng) ->
        AlertDialog(
            onDismissRequest = { if (!isSavingPin) pinDraftPoint = null },
            title = { Text("Create a Pin") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pinDraftText,
                        onValueChange = { pinDraftText = it.take(60) },
                        label = { Text("Text mark") },
                        placeholder = { Text("e.g. Dog at the gate") },
                        maxLines = 3,
                        supportingText = { Text(pinDraftText.length.toString() + "/60") },
                        visualTransformation = VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("%.5f, %.5f".format(lat, lng), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(
                    enabled = pinDraftText.isNotBlank() && !isSavingPin,
                    onClick = {
                        val cid = congregationId ?: return@Button
                        isSavingPin = true
                        coroutineScope.launch {
                            val result = viewModel.createPin(cid, pinDraftText, lat, lng, currentPersonId)
                            isSavingPin = false
                            when (result) {
                                MapPinResult.Success -> {
                                    pinDraftPoint = null
                                    toast("Pin saved online.")
                                }
                                MapPinResult.Offline ->
                                    toast("You're offline. Connect to the internet to save a pin.", long = true)
                                is MapPinResult.Error ->
                                    toast("Couldn't save the pin: " + result.message, long = true)
                            }
                        }
                    },
                ) {
                    if (isSavingPin) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Save Pin")
                }
            },
            dismissButton = { TextButton(enabled = !isSavingPin, onClick = { pinDraftPoint = null }) { Text("Cancel") } },
        )
    }

    // Tapped pin: its text, who added it, and (for its creator / territory roles) Delete.
    pins.firstOrNull { it.id == selectedPinId }?.let { pin ->
        var canDelete by remember(pin.id) { mutableStateOf(false) }
        LaunchedEffect(pin.id) { canDelete = viewModel.canDeletePin(pin, currentPersonId) }
        AlertDialog(
            onDismissRequest = { selectedPinId = null },
            title = { Text(pin.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        listOfNotNull(
                            pin.createdByName.takeIf { it.isNotBlank() }?.let { "Added by $it" },
                            java.text.DateFormat.getDateInstance().format(java.util.Date(pin.createdAt)),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("%.5f, %.5f".format(pin.lat, pin.lng), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { openGoogleMaps(pin.lat, pin.lng) }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Open Google Maps") }
                    if (canDelete) {
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    when (val result = viewModel.deletePin(pin, currentPersonId)) {
                                        MapPinResult.Success -> {
                                            selectedPinId = null
                                            toast("Pin removed.")
                                        }
                                        MapPinResult.Offline ->
                                            toast("You're offline. Connect to the internet to remove a pin.", long = true)
                                        is MapPinResult.Error -> toast(result.message, long = true)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) { Text("Delete Pin", color = MaterialTheme.colorScheme.error) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedPinId = null }) { Text("Close") } },
        )
    }

    if (showGroupPicker) {
        GroupPickerDialog(
            groups = groups,
            myGroupId = myGroupId.takeIf { hasMine },
            initialSelection = otherGroupId,
            onDismiss = { showGroupPicker = false },
            onConfirm = { id ->
                showGroupPicker = false
                if (hasMine && id == myGroupId) {
                    scope = GroupScope.MINE
                } else {
                    otherGroupId = id
                    scope = GroupScope.OTHER
                }
                municipalityFilter = null
                territoryFilter = null
                selectedRecordId = null
            },
        )
    }

    // A numbered stack marker: the records sharing (almost) the same spot, as a clean list — pick one to open it.
    stackIds?.let { ids ->
        val stacked = recordsWithDistance.filter { it.record.id in ids }
        ModalBottomSheet(onDismissRequest = { stackIds = null }, sheetState = rememberModalBottomSheetState()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("${stacked.size} records at this location", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                stacked.forEach { rd ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { stackIds = null; selectRecord(rd.record.id, fly = false) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(markerColor(rd.record.type)))
                        Column(Modifier.weight(1f)) {
                            Text(rd.record.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(rd.record.type.label + (rd.record.groupName?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(formatDistance(rd.meters), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    val sheetRecord = selectedRecord
    if (sheetOpen && sheetRecord != null) {
        RecordSheet(
            item = sheetRecord,
            onDismiss = { sheetOpen = false },
            onViewDetails = { sheetOpen = false; detailsPerson = sheetRecord.record.person },
            onNavigate = {
                val r = sheetRecord.record
                val uri = Uri.parse("geo:${r.lat},${r.lng}?q=${r.lat},${r.lng}(${Uri.encode(r.name)})")
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    .onFailure { toast("No navigation app found.") }
            },
        )
    }
}

/** Compact segmented control: small icon + short label, ~34dp tall, the selected segment in the primary colour. */
@Composable
private fun SegmentedPair(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icons: List<androidx.compose.ui.graphics.vector.ImageVector?> = emptyList(),
) {
    Row(
        modifier = modifier.height(34.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val isSelected = i == selected
            val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.95f else 1f, androidx.compose.animation.core.tween(90), label = "segPress")
            val container by androidx.compose.animation.animateColorAsState(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, androidx.compose.animation.core.tween(160), label = "segBg")
            val tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(RoundedCornerShape(8.dp))
                    .background(container)
                    .clickable(interactionSource = interaction, indication = null) { onSelect(i) }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                icons.getOrNull(i)?.let { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint) }
                Text(
                    label,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = tint,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun <T> DropdownChip(
    label: String,
    value: String?,
    placeholder: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 6.dp).height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(value ?: placeholder, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp))
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = label)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onSelect(key) })
            }
        }
    }
}

@Composable
private fun LocationBanner(issue: LocationIssue, onAction: () -> Unit) {
    val (message, action) = when (issue) {
        LocationIssue.NO_PERMISSION -> "Location permission is needed to show distances." to "Allow"
        LocationIssue.SERVICES_OFF -> "Turn on your device's location to see distances." to "Retry"
        LocationIssue.UNAVAILABLE -> "Your current location can't be determined yet. Distances are hidden." to "Retry"
    }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Row(modifier = Modifier.padding(start = 12.dp, end = 4.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun MessageCard(text: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun CenterState(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

/** Compact labelled map tool: a small icon over a one-word caption, so each button says what it does. */
@Composable
private fun MapToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, active: Boolean = false) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        shadowElevation = 3.dp,
        modifier = Modifier.width(58.dp).height(46.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp), tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** What the "Show Next Nearest/Farthest" button just picked — tap for the full details sheet. */
@Composable
private fun StepCard(item: RecordWithDistance, label: String, onClick: () -> Unit) {
    val r = item.record
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(markerColor(r.type)))
                Column(modifier = Modifier.weight(1f)) {
                    Text(r.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val place = listOfNotNull(r.barangay, r.municipality).joinToString(", ")
                    Text(
                        listOfNotNull(r.groupName, r.type.label, place.takeIf { it.isNotBlank() }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(formatDistance(item.meters), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Quiet default for the bottom of the map: how many locations are showing, by type — nothing selected. */
@Composable
private fun SummaryStrip(records: List<RecordWithDistance>, hasLocation: Boolean) {
    Surface(shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                records.size.toString() + " location" + (if (records.size == 1) "" else "s") + " shown",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 4.dp)) {
                RecordType.entries.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(markerColor(t)))
                        Text(t.shortLabel + " " + records.count { it.record.type == t }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (!hasLocation) {
                Text(
                    "Current location unavailable — distances and Show Next Nearest/Farthest need it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** One row of the flattened list: an FS Group heading (Show All FS Groups only), a record-type
 * heading, or a record. Flattening makes "scroll to the selected record" a simple index lookup. */
private sealed interface ListEntry {
    val key: String
    data class GroupHeader(val groupName: String, val colorHex: String?, val count: Int) : ListEntry {
        override val key get() = "g_" + groupName
    }
    data class TypeHeader(val scope: String, val type: RecordType, val count: Int) : ListEntry {
        override val key get() = "t_" + scope + "_" + type.name
    }
    data class Item(val item: RecordWithDistance) : ListEntry {
        override val key get() = "r_" + item.record.id
    }
}

/** List View. The whole list scrolls, it can be printed / saved as a PDF, and the record picked with
 * "Show Next Nearest/Farthest" is highlighted and scrolled to. With a single FS Group it is grouped by
 * record type; with "Show All FS Groups" it is organized by FS Group first, then by type. */
@Composable
private fun RecordListView(
    sorted: List<RecordWithDistance>,
    hasLocation: Boolean,
    showAllGroups: Boolean,
    groupColors: Map<String, String>,
    selectedId: String?,
    onClick: (String) -> Unit,
    onPrint: () -> Unit,
) {
    val entries = remember(sorted, showAllGroups) {
        buildList<ListEntry> {
            fun addByType(scope: String, records: List<RecordWithDistance>) {
                RecordType.entries.forEach { type ->
                    val ofType = records.filter { it.record.type == type }
                    if (ofType.isNotEmpty()) {
                        add(ListEntry.TypeHeader(scope, type, ofType.size))
                        ofType.forEach { add(ListEntry.Item(it)) }
                    }
                }
            }
            if (showAllGroups) {
                sorted.groupBy { it.record.groupId to it.record.groupName }
                    .entries.sortedBy { it.key.second.orEmpty().lowercase() }
                    .forEach { (group, records) ->
                        add(ListEntry.GroupHeader(group.second ?: "Unknown group", group.first?.let { groupColors[it] }, records.size))
                        addByType(group.first.orEmpty(), records)
                    }
            } else {
                addByType("", sorted)
            }
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(selectedId, entries) {
        if (selectedId == null) return@LaunchedEffect
        val index = entries.indexOfFirst { it is ListEntry.Item && it.item.record.id == selectedId }
        // +1: the title row above the entries.
        if (index >= 0) listState.animateScrollToItem(index + 1)
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "title") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        (if (showAllGroups) "All FS Groups · " else "") + com.emfitsolutions.gopreach.ui.components.recordFoundText(sorted.size),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (!hasLocation) {
                        Text("Distances unavailable — location not determined", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onPrint) { Icon(Icons.Rounded.Print, contentDescription = "Print or save as PDF") }
            }
        }
        items(entries, key = { it.key }) { entry ->
            when (entry) {
                is ListEntry.GroupHeader -> Column(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier.size(16.dp).clip(CircleShape)
                                .background(entry.colorHex?.let { Color(android.graphics.Color.parseColor(it)) } ?: MaterialTheme.colorScheme.outline),
                        )
                        Text(entry.groupName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("(" + entry.count + ")", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 2.dp)
                }
                is ListEntry.TypeHeader -> Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(markerColor(entry.type)))
                    Text(entry.type.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("(" + entry.count + ")", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is ListEntry.Item -> {
                    val r = entry.item.record
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (r.id == selectedId) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { onClick(r.id) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val place = listOfNotNull(r.barangay, r.municipality).joinToString(", ")
                            if (place.isNotBlank()) {
                                Text(place, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(formatDistance(entry.item.meters), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

/** "Other FS Group Territories" — pick one group, then View Selected Group. */
@Composable
private fun GroupPickerDialog(
    groups: List<com.emfitsolutions.gopreach.data.model.Group>,
    myGroupId: String?,
    initialSelection: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var choice by remember { mutableStateOf(initialSelection) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Other FS Group Territories") },
        text = {
            if (groups.isEmpty()) {
                Text("No FS Groups are available.")
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    groups.forEach { g ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { choice = g.id }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = choice == g.id, onClick = { choice = g.id })
                            Text(if (g.id == myGroupId) "${g.name} (my group)" else g.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = { Button(enabled = choice != null, onClick = { choice?.let(onConfirm) }) { Text("View Selected Group") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordSheet(item: RecordWithDistance, onDismiss: () -> Unit, onViewDetails: () -> Unit, onNavigate: () -> Unit) {
    val r = item.record
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(r.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(markerColor(r.type)))
                Text(r.type.label, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                if (item.meters != null) "${formatDistance(item.meters)} away" else "Distance unavailable",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            val currentAddress = listOfNotNull(r.barangay?.let { "Barangay $it" }, r.municipality, r.province).joinToString(", ")
            Text("Place of Origin: " + r.person.address.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
            Text("Current Address: " + currentAddress.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
            r.person.contact?.takeIf { it.isNotBlank() }?.let { Text("Contact: $it", style = MaterialTheme.typography.bodyMedium) }
            r.person.notes?.takeIf { it.isNotBlank() }?.let { Text("Notes: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            r.groupName?.let { Text("FS Group: $it", style = MaterialTheme.typography.bodyMedium) }
            r.territoryName?.let { Text("Territory: $it", style = MaterialTheme.typography.bodyMedium) }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onNavigate, modifier = Modifier.weight(1f).height(44.dp)) {
                    Icon(Icons.Rounded.Directions, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Navigate")
                }
                Button(onClick = onViewDetails, modifier = Modifier.weight(1f).height(44.dp)) {
                    Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("View Details")
                }
            }
        }
    }
}
