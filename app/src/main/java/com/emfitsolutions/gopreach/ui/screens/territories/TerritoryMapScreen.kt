package com.emfitsolutions.gopreach.ui.screens.territories

import com.emfitsolutions.gopreach.ui.components.keyboardPadding
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Streetview
import com.emfitsolutions.gopreach.ui.components.AlertDialog
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
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.GpsNotFixed
import androidx.compose.material.icons.rounded.GpsOff
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
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
import com.emfitsolutions.gopreach.ui.screens.circuit.CircuitScopeStore
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
    /** Circuit Overseer: only these congregations are offered (null = no restriction). */
    allowedCongregationIds: Set<String>? = null,
    /** View-only: no drawing, no pins, and none of the publishers' personal Searching / Return Visit / Bible Study records. */
    readOnly: Boolean = false,
    /** Hosted as a Main Form tab: the tab strip replaces this screen's own title bar. */
    embedded: Boolean = false,
    onBack: () -> Unit,
    viewModel: TerritoryMapViewModel = koinViewModel(),
    pipelineViewModel: PipelineViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val toast = rememberToaster()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val isSuperAdmin = fixedCongregationId == null

    val coroutineScope = rememberCoroutineScope()

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
    val allCongregations by viewModel.congregations().collectAsStateWithLifecycle(initialValue = emptyList())
    val congregations = if (allowedCongregationIds == null) allCongregations else allCongregations.filter { it.id in allowedCongregationIds }
    var pickedCongregationId by rememberSaveable {
        // "View Territory Map" on the Circuit Overseer's Congregation Overview leaves its congregation here.
        val handoff = com.emfitsolutions.gopreach.ui.components.CongregationContextStore.get("territory_map")
        com.emfitsolutions.gopreach.ui.components.CongregationContextStore.set("territory_map", null)
        mutableStateOf<String?>(handoff?.takeIf { fixedCongregationId == null && (allowedCongregationIds == null || it in allowedCongregationIds) })
    }
    // A Circuit Overseer (restricted picker) must choose a congregation first — nothing is auto-selected, and the choice
    // is shared with Publishers / Elders & MS / Field Service Report. Others keep the picker's own behavior.
    val restricted = allowedCongregationIds != null
    val chosenCongregationId = if (restricted) CircuitScopeStore.congregation else pickedCongregationId
    val congregationId = (fixedCongregationId ?: chosenCongregationId)
        ?.takeIf { allowedCongregationIds == null || it in allowedCongregationIds }
    // Super-Admin and Circuit Overseer always choose a congregation before the territory map opens.
    if (fixedCongregationId == null && congregationId == null) {
        androidx.compose.material3.Scaffold(
            topBar = {
                androidx.compose.material3.TopAppBar(
                    title = { Text("Territory Map") },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                )
            },
        ) { padding ->
            com.emfitsolutions.gopreach.ui.screens.circuit.SelectCongregationPrompt(
                congregations = congregations,
                onSelect = { if (restricted) CircuitScopeStore.selectCongregation(it) else pickedCongregationId = it },
                modifier = Modifier.padding(padding),
                hint = if (restricted) "Choose a congregation under your assigned Circuit to view its territory." else "Choose a congregation to view its territory map.",
            )
        }
        return
    }

    val myGroupId by remember(currentPersonId) { viewModel.myGroupId(currentPersonId) }.collectAsStateWithLifecycle(initialValue = null)
    val groups by remember(congregationId) { viewModel.groupsFor(congregationId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val hasMine = !isSuperAdmin && myGroupId != null
    // A view-only Circuit Overseer opens on "Show FS Group" (every FS Group of the congregation) — they have no FS Group of their own.
    var scope by rememberSaveable { mutableStateOf(if (readOnly || fixedCongregationId == null) GroupScope.ALL else GroupScope.MINE) }
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
        else if (readOnly) flowOf(key to emptyList())
        else viewModel.recordsFor(congregationId, if (showingAll) null else selectedGroupId, a, areaLocator).map { key to it }
    }.collectAsStateWithLifecycle(initialValue = null)
    val recordsState: List<LocationRecord>? = recordsTagged?.takeIf { it.first == datasetKey }?.second
    val isLoading = datasetKey != null && (areasState == null || recordsState == null)

    // ---- Drawing (long-press -> Drawing Mode; shared with every map module) -------
    val drawingViewModel: MapDrawingViewModel = koinViewModel()
    val drawingAccess by remember(currentPersonId) { drawingViewModel.access(currentPersonId) }
        .collectAsStateWithLifecycle(initialValue = DrawingAccess.NONE)
    // "View only": another FS Group's territory (or every group, when this person may draw in only their own) can be looked at for reference, but seeing a
    // territory never gives the right to draw or edit it - that is decided per group by DrawingAccess, here and again by the server.
    val viewOnlyNote: String? = when {
        selectedGroup != null -> if (drawingAccess.canDrawIn(selectedGroup.id)) null else "View only"
        showingAll -> if (drawingAccess.scope is com.emfitsolutions.gopreach.domain.GroupAccessScope.OwnGroup || drawingAccess.scope == com.emfitsolutions.gopreach.domain.GroupAccessScope.None) "View only, except your own group" else null
        else -> null
    }
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
    // When the last real GPS fix arrived, for the status pill (a fix that stops coming is reported as lost, never shown as current).
    var lastFixAt by remember { mutableStateOf(0L) }
    var permissionAsked by rememberSaveable { mutableStateOf(false) }
    var locationIssue by remember { mutableStateOf<LocationIssue?>(null) }
    var locationRefreshKey by remember { mutableIntStateOf(0) }
    // Driving Mode (navigation aid only). It reuses the one live GPS stream below; it never opens a second one.
    val driving = remember { com.emfitsolutions.gopreach.ui.components.map.DrivingModeState() }
    var driveAfterPermission by remember { mutableStateOf(false) }
    val permissionLauncher = rememberPermissionRequester() { granted ->
        locationRefreshKey++
        if (granted && driveAfterPermission) driving.start()
        driveAfterPermission = false
    }
    LaunchedEffect(locationRefreshKey, driving.active) {
        if (!viewModel.hasLocationPermission()) {
            locationIssue = LocationIssue.NO_PERMISSION
            if (locationRefreshKey == 0) { permissionAsked = true; permissionLauncher.launch(AppPermission.LOCATION) }
            return@LaunchedEffect
        }
        if (!viewModel.isLocationServicesEnabled()) {
            locationIssue = LocationIssue.SERVICES_OFF
            return@LaunchedEffect
        }
        runCatching { viewModel.currentLocation() }.getOrNull()?.let { myLocation = it.lat to it.lng; myAccuracy = it.accuracyMeters; locationIssue = null; lastFixAt = com.emfitsolutions.gopreach.platform.nowMillis() }
            ?: run { if (myLocation == null) locationIssue = LocationIssue.UNAVAILABLE }
        // Live updates only while this screen is visible; small moves are ignored.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            runCatching {
                viewModel.locationUpdates(fast = driving.active).collect { fix ->
                    if (driving.active) driving.onFix(fix, com.emfitsolutions.gopreach.platform.nowMillis())
                    val prev = myLocation
                    if (prev == null || haversineMeters(prev.first, prev.second, fix.lat, fix.lng) >= MIN_MOVE_METERS) {
                        myLocation = fix.lat to fix.lng
                        myAccuracy = fix.accuracyMeters
                    }
                    lastFixAt = com.emfitsolutions.gopreach.platform.nowMillis()
                    locationIssue = null
                }
            }
        }
    }

    // What the map shows as "you": while driving, only a fix that passed every check - never an estimate.
    val driveSnap = driving.snapshot
    val shownLocation = if (driving.active && driveSnap.hasPosition) driveSnap.lat!! to driveSnap.lng!! else myLocation
    val shownAccuracy = if (driving.active && driveSnap.hasPosition) driveSnap.accuracyMeters else myAccuracy
    val connectivity: com.emfitsolutions.gopreach.data.sync.ConnectivityObserver = org.koin.compose.koinInject()
    var online by remember { mutableStateOf(connectivity.isOnline()) }
    LaunchedEffect(connectivity) { connectivity.observe().collect { online = it } }
    // In-app turn-by-turn guidance (needs Driving Mode). Route from the routing service; positions are the filtered Driving Mode fixes.
    val routing: com.emfitsolutions.gopreach.data.navigation.RoutingService = org.koin.compose.koinInject()
    val voiceGuide = remember { com.emfitsolutions.gopreach.data.navigation.VoiceGuide(context) }
    DisposableEffect(voiceGuide) { onDispose { voiceGuide.shutdown() } }
    val drivingPrefs: com.emfitsolutions.gopreach.platform.KeyValueStores = org.koin.compose.koinInject()
    val guidance = remember {
        val store = drivingPrefs.open("gopreach_driving")
        com.emfitsolutions.gopreach.ui.components.map.TurnByTurnState(
            routing, coroutineScope, voiceGuide,
            initialVoiceOn = store.getBoolean("voice_on", true),
            onVoiceChanged = { on -> store.edit().putBoolean("voice_on", on).apply() },
        )
    }
    val routePoints = remember(guidance.route) { guidance.route?.points?.map { it.lat to it.lng } }
    // While choosing a route: the other routes (greyer, underneath) and the frame that shows all of them.
    val altRoutePoints = remember(guidance.choices, guidance.selectedIndex) {
        guidance.choices.filterIndexed { i, _ -> i != guidance.selectedIndex }.map { c -> c.route.points.map { it.lat to it.lng } }
    }
    val fitRoutePoints = remember(guidance.phase, guidance.choices) {
        if (guidance.phase == com.emfitsolutions.gopreach.ui.components.map.GuidancePhase.PREVIEW) guidance.choices.flatMap { c -> c.route.points.map { it.lat to it.lng } } else null
    }
    LaunchedEffect(driveSnap.lat, driveSnap.lng) {
        val la = driveSnap.lat
        val ln = driveSnap.lng
        if (guidance.active && la != null && ln != null) guidance.onPosition(la, ln, driveSnap.accuracyMeters, driveSnap.speedKmh?.let { it / 3.6f }, online)
    }
    // Turning Driving Mode off ends a route being followed; a route still being chosen (or found) does not need Driving Mode yet.
    LaunchedEffect(driving.active) {
        val p = guidance.phase
        if (!driving.active && (p == com.emfitsolutions.gopreach.ui.components.map.GuidancePhase.ACTIVE || p == com.emfitsolutions.gopreach.ui.components.map.GuidancePhase.REROUTING || p == com.emfitsolutions.gopreach.ui.components.map.GuidancePhase.ARRIVED)) guidance.stop()
    }

    // A missing GPS signal must turn the status stale even when no fix arrives.
    LaunchedEffect(driving.active) {
        while (driving.active) {
            kotlinx.coroutines.delay(1_000L)
            driving.tick(com.emfitsolutions.gopreach.platform.nowMillis())
        }
    }
    // The screen stays awake while navigating, and not a moment longer.
    val hostView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(driving.active) {
        hostView.keepScreenOn = driving.active
        onDispose { hostView.keepScreenOn = false }
    }

    // ---- Filters / ordering / view mode -----------------------------------
    var typeFilter by rememberSaveable { mutableStateOf(TypeFilter.ALL) }
    // Publisher's map (hosted in the Ministry tab): "Show my Ministry Record Only" starts ticked, so only the publisher's own pins show; unticked, the
    // Congregation drop-down is offered too and picks the congregation's records.
    var mineOnly by rememberSaveable { mutableStateOf(true) }
    var congregationRecords by rememberSaveable { mutableStateOf(false) }
    // Nothing is auto-selected on load. "Show Next Nearest/Farthest" steps through the
    // current (filtered) records by distance; stepping restarts whenever a filter changes.
    var stepOrder by remember(datasetKey, typeFilter, municipalityFilter, territoryFilter) { mutableStateOf<SortOrder?>(null) }
    var stepIndex by remember(datasetKey, typeFilter, municipalityFilter, territoryFilter) { mutableIntStateOf(-1) }
    // The Territory Map is the map: there is no List switch any more. Same viewing rules as before decide whose territories it shows.
    val viewMode = ViewMode.MAP
    // Super-Admin / Circuit Overseer: every congregation opens on Show FS Group.
    androidx.compose.runtime.LaunchedEffect(congregationId) { if (fixedCongregationId == null && focusLat == null) scope = GroupScope.ALL }
    // Full-screen Map View: hides the app bar, the controls above the map and
    // the system bars so the map fills the display; Back exits it first.
    // The Territory Map opens in full screen (the controls come back with Exit); the user can leave it at any time.
    var fullScreen by rememberSaveable { mutableStateOf(true) }
    // The map opens clean (Filters, Hide Icons, Style, Drive only). Filters opens the filter panel; every other tool is in the long-press menu.
    var showFilterPanel by rememberSaveable { mutableStateOf(false) }
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
    var is3d by rememberSaveable { mutableStateOf(false) }
    // Driving Mode looks like a navigation app: it switches the map to 3D (tilted, heading up, the arrow low on the screen) and puts the map
    // back the way it was when driving ends. The camera tilt it keeps follows the 3D switch, so turning 3D off while driving flattens it.
    var is3dBeforeDriving by rememberSaveable { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(driving.active) {
        if (driving.active) { is3dBeforeDriving = is3d; is3d = true } else is3dBeforeDriving?.let { is3d = it; is3dBeforeDriving = null }
    }
    LaunchedEffect(is3d, driving.active) { driving.tiltDeg = if (is3d && driving.active) 60.0 else 0.0 }
    var recenterToken by remember { mutableIntStateOf(0) }
    // Follow mode (north stays up): the camera keeps me centred. [followPaused] = I moved the map, so following waits for the Recenter button.
    var followMe by rememberSaveable { mutableStateOf(false) }
    var followPaused by rememberSaveable { mutableStateOf(false) }
    var bottomChromePx by remember { mutableIntStateOf(0) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var mapLoadState by remember { mutableStateOf(MapLoadState.LOADING) }

    // ---- Re-center ("My location"): camera only — never touches the selected group / barangay / permissions ------------
    var locationHelp by remember { mutableStateOf<LocationIssue?>(null) } // a "go to settings" explanation
    fun requestLocationPermission() {
        permissionAsked = true
        permissionLauncher.launch(AppPermission.LOCATION)
    }
    // Driving Mode: a navigation aid only - it never edits territories, assignments, statuses or reports.
    fun toggleDriving() {
        when {
            driving.active -> driving.stop()
            drawingState.active -> toast("Finish or close Draw Mode before starting Driving Mode.")
            !viewModel.hasLocationPermission() -> {
                val activity = context as? Activity
                val canAskAgain = activity == null || !permissionAsked ||
                    androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                if (canAskAgain) {
                    toast("Location permission is needed for Driving Mode.")
                    driveAfterPermission = true
                    requestLocationPermission()
                } else {
                    locationHelp = LocationIssue.NO_PERMISSION
                }
            }
            !viewModel.isLocationServicesEnabled() -> locationHelp = LocationIssue.SERVICES_OFF
            else -> driving.start()
        }
    }
    // Navigate: find the routes (the best one and alternatives) and let the driver choose; Driving Mode starts when they press Start navigation.
    fun startNavigation(name: String, lat: Double, lng: Double) {
        if (!viewModel.hasLocationPermission() || !viewModel.isLocationServicesEnabled()) { toggleDriving(); return } // asks for permission / explains location services; tap Navigate again afterwards
        val here = shownLocation ?: myLocation
        when {
            here == null -> toast("Waiting for your GPS location.")
            !online -> toast(if (com.emfitsolutions.gopreach.data.offlinemap.OfflineMaps.isInstalled()) "The offline map is ready and the GPS works, but finding a route needs internet. Routes cannot be calculated offline yet." else "Finding a route needs an internet connection.")
            else -> guidance.preview(com.emfitsolutions.gopreach.ui.components.map.GuidanceDestination(name, lat, lng), here.first, here.second)
        }
    }
    // Start navigation (from the route preview): Driving Mode on, then follow the chosen route.
    fun beginGuidance() {
        if (!driving.active) toggleDriving()
        if (driving.active) {
            guidance.begin()
            toast("Navigation started. Follow road signs and traffic rules.")
        }
    }
    // Driving Mode only belongs to the map: leaving the map view, or starting to draw, ends it (and so the faster GPS updates).
    LaunchedEffect(viewMode, drawingState.active) {
        if (driving.active && (viewMode != ViewMode.MAP || drawingState.active)) driving.stop()
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
    // Driving Mode and Draw Mode have their own camera: plain Follow stands down while either is on.
    LaunchedEffect(drawingState.active, driving.active) {
        if (drawingState.active || driving.active) { followMe = false; followPaused = false }
    }
    // The floating Recenter button: Driving Mode -> resume following the arrow; Follow on -> turn it off; otherwise centre on me and follow.
    // Permission / location-services / no-fix problems are handled (and explained) by recenter() itself.
    fun onRecenterButton() {
        when {
            driving.active -> if (driving.snapshot.hasPosition) driving.recenter() else toast("Waiting for a GPS location.")
            followMe -> recenter() // already following: bring the map back onto me again
            else -> {
                recenter()
                if (viewModel.hasLocationPermission() && viewModel.isLocationServicesEnabled() && myLocation != null) { followMe = true; followPaused = false }
            }
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
            (!embedded || (congregationRecords && !mineOnly) || r.person.publisherPersonId == currentPersonId) &&
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
    // Back from a chosen congregation returns to the congregation picker (Super-Admin / Circuit Overseer).
    fun backToPicker() { pickedCongregationId = null; if (restricted) CircuitScopeStore.selectCongregation(null); otherGroupId = null; selectedRecordId = null }
    BackHandler(enabled = fixedCongregationId == null && congregationId != null && territoryFilter == null && !drawingState.active) { backToPicker() }

    // Re-fit when a boundary finishes loading too, so a selected Barangay is always framed whole (fit-to-screen).
    val fitKey = "$datasetKey|${typeFilter.name}|$municipalityFilter|$territoryFilter|${barangayGroupFilter}|b${visibleAreas.count { it.id in boundaryJson }}"
    val fitReady = !isLoading && datasetKey != null && visibleAreas.all { it.id in attemptedAreaIds }
    val focus = if (focusLat != null && focusLng != null) Triple(focusLat, focusLng, focusName.orEmpty()) else null

    Scaffold(
        topBar = {
            if (!fullScreenActive && !embedded) Column {
                TopAppBar(
                    title = { Text("Territory Map") },
                    navigationIcon = {
                        IconButton(onClick = { if (territoryFilter != null && !drawingState.active) backFromBarangay() else if (fixedCongregationId == null && congregationId != null) backToPicker() else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                    },
                )
                if (!restricted) com.emfitsolutions.gopreach.ui.components.AdminMainTabs(com.emfitsolutions.gopreach.ui.components.AdminTab.TERRITORY)
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
                        onSelect = { pickedCongregationId = it; if (restricted) CircuitScopeStore.selectCongregation(it); otherGroupId = null; selectedRecordId = null },
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
                    viewOnlyNote?.let { ViewOnlyTag(it) }
                    if (effectiveScope == GroupScope.OTHER && (!isSuperAdmin || congregationId != null)) {
                        TextButton(onClick = { showGroupPicker = true }) { Text(if (selectedGroup == null) "Choose FS Group" else "Change") }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (embedded) {
                        val types = listOf(TypeFilter.SEARCHING, TypeFilter.RETURN_VISIT, TypeFilter.BIBLE_STUDY)
                        Row(
                            modifier = Modifier.clickable { mineOnly = !mineOnly; if (mineOnly) congregationRecords = false },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Checkbox(checked = mineOnly, onCheckedChange = { mineOnly = it; if (it) congregationRecords = false })
                            Text("Show my Ministry Record Only", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                        com.emfitsolutions.gopreach.ui.components.MinistryPicker(
                            "My Records", "My", types.indexOf(typeFilter).takeIf { it >= 0 && !congregationRecords },
                            { k -> congregationRecords = false; typeFilter = k?.let { types[it] } ?: TypeFilter.ALL }, Modifier.width(210.dp), allowAll = true,
                        )
                        if (!mineOnly) com.emfitsolutions.gopreach.ui.components.MinistryPicker(
                            "Congregation Records", "Congregation", types.indexOf(typeFilter).takeIf { it >= 0 && congregationRecords },
                            { k -> congregationRecords = true; typeFilter = k?.let { types[it] } ?: TypeFilter.ALL }, Modifier.width(230.dp), allowAll = true,
                        )
                    } else TypeFilter.entries.forEach { f ->
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
                // ONE control bar above the map: Next Nearest / Next Farthest, then the Territory Status filter (All, Finished, To Continue, To Do).
                // The status chips follow the same rule as before (shown to whoever can draw, or when there are drawings to filter).
                if (!drawingState.active) {
                    StatusBar(
                        counts = statusCounts,
                        filter = drawingState.statusFilter,
                        onFilter = { drawingState.statusFilter = it },
                        showStatus = drawingAccess.canUseDrawingTools || statusCounts.values.any { it > 0 },
                        leading = {
                            SegmentedPair(
                                labels = listOf("Next Nearest", "Next Farthest"),
                                icons = listOf(Icons.Rounded.NearMe, Icons.Rounded.Explore),
                                selected = when (stepOrder) { SortOrder.NEAREST -> 0; SortOrder.FARTHEST -> 1; else -> -1 },
                                onSelect = { stepTo(if (it == 0) SortOrder.NEAREST else SortOrder.FARTHEST) },
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
                        },
                    )
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
                        myLocation = shownLocation,
                        myAccuracyMeters = shownAccuracy,
                        driving = driving,
                        routePoints = routePoints,
                        altRoutePoints = altRoutePoints,
                        fitRoutePoints = fitRoutePoints,
                        focus = focus,
                        basemap = basemap,
                        is3d = is3d,
                        reloadToken = reloadToken,
                        fitKey = fitKey,
                        fitReady = fitReady,
                        recenterToken = recenterToken,
                        followMe = followMe,
                        followHeadingUp = followMe,
                        onFollowPaused = { followMe = false; followPaused = true },
                        flyToRecord = flyToRecord,
                        onRecordTap = { selectRecord(it, fly = false) },
                        // Tapping a barangay SELECTS it: the map zooms to it and shows everything in it - every FS Group's
                        // drawings plus its Search / Return Visit / Bible Study records - right on this map.
                        onAreaTap = { id ->
                            areas.firstOrNull { it.id == id }?.let { a ->
                                selectBarangay(id)
                            }
                        },
                        // Long-press asks what to do at that exact spot.
                        // For someone who may draw, a long press turns Drawing Mode on; everyone else keeps the old choice.
                        // Long press: a small menu - Draw (only where the role allows it), Start navigation, Street View and Open in Maps.
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
                    val bottomStripShown = datasetKey != null && !isLoading && mapLoadState == MapLoadState.LOADED && !drawingState.active && drawingState.selectedDrawingId == null && !guidance.active
                    // Map chrome: basemap + my-location buttons.
                    Column(
                        // Kept clear of the status bar / camera cut-out whenever the system bars are showing, so a tap never lands on them.
                        modifier = Modifier.align(Alignment.TopEnd).then(if (embedded) Modifier else Modifier.windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.Companion.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Top))).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        // In the Main Form tab the map is never really full screen (the header and tabs stay), so the toggle is
                        // "Filters" (show the FS Group and record-type controls above the map) / "Map" (hide them again).
                        MapToolButton(
                            icon = if (fullScreenActive) Icons.Rounded.Tune else Icons.Rounded.Fullscreen,
                            label = if (fullScreenActive) "Filters" else if (embedded) "Map" else "Full screen",
                            active = fullScreenActive && showFilterPanel,
                            onClick = { if (fullScreenActive) showFilterPanel = !showFilterPanel else fullScreen = true },
                        )
                        // Hide / Show Icons: one setting for every Territory Map view (all groups, a group, a municipality, a Barangay).
                        MapToolButton(
                            icon = if (drawingState.markersHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                            label = if (drawingState.markersHidden) "Show Icons" else "Hide Icons",
                            active = drawingState.markersHidden,
                            onClick = { drawingState.markersHidden = !drawingState.markersHidden },
                        )
                        // Every map style is one tap away and always visible: Standard, Satellite, Night, and 3D (for every role).
                        if (embedded) {
                            // One compact button with a menu, so the style row never spreads across the map beside the other buttons.
                            var styleMenu by remember { mutableStateOf(false) }
                            Box {
                                MapToolButton(icon = Icons.Rounded.Layers, label = "Style", onClick = { styleMenu = true })
                                DropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }) {
                                    TerritoryBasemap.entries.forEach { b ->
                                        DropdownMenuItem(text = { Text(b.label, fontWeight = if (basemap == b) FontWeight.Bold else FontWeight.Normal) }, onClick = { basemap = b; styleMenu = false })
                                    }
                                    DropdownMenuItem(text = { Text("3D", fontWeight = if (is3d) FontWeight.Bold else FontWeight.Normal) }, onClick = { is3d = !is3d; styleMenu = false })
                                }
                            }
                        } else Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                            shadowElevation = 3.dp,
                        ) {
                            Row(modifier = Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                TerritoryBasemap.entries.forEach { b -> MapStyleChip(b.label, basemap == b) { basemap = b } }
                                MapStyleChip("3D", is3d) { is3d = !is3d }
                            }
                        }
                        MapToolButton(
                            icon = Icons.Rounded.Navigation,
                            label = if (driving.active) "Driving" else "Drive",
                            description = if (driving.active) "Driving Mode is on. Tap to turn it off." else "Start Driving Mode: the map follows you and the arrow shows your direction of travel",
                            active = driving.active,
                            onClick = { toggleDriving() },
                        )
                        if (driving.active) {
                            val q = driving.snapshot.quality
                            MapToolButton(
                                icon = when (q) {
                                    com.emfitsolutions.gopreach.domain.navigation.GpsQuality.GOOD -> Icons.Rounded.GpsFixed
                                    com.emfitsolutions.gopreach.domain.navigation.GpsQuality.ACQUIRING -> Icons.Rounded.GpsNotFixed
                                    else -> Icons.Rounded.GpsOff
                                },
                                label = when (q) {
                                    com.emfitsolutions.gopreach.domain.navigation.GpsQuality.GOOD -> driving.snapshot.accuracyMeters?.let { "GPS ±${it.toInt()}m" } ?: "GPS"
                                    com.emfitsolutions.gopreach.domain.navigation.GpsQuality.ACQUIRING -> "Finding"
                                    com.emfitsolutions.gopreach.domain.navigation.GpsQuality.WEAK -> "Weak GPS"
                                    com.emfitsolutions.gopreach.domain.navigation.GpsQuality.STALE -> "No GPS"
                                },
                                description = "GPS status. " + driving.statusText(online),
                                active = q == com.emfitsolutions.gopreach.domain.navigation.GpsQuality.WEAK || q == com.emfitsolutions.gopreach.domain.navigation.GpsQuality.STALE,
                                onClick = { toast(driving.statusText(online)) },
                            )
                            MapToolButton(
                                icon = Icons.Rounded.Explore,
                                label = if (driving.headingUp) "Heading up" else "North up",
                                description = if (driving.headingUp) "The map turns with your direction of travel. Tap for north-up." else "North stays at the top. Tap for heading-up.",
                                onClick = { driving.headingUp = !driving.headingUp },
                            )
                            MapToolButton(
                                icon = if (guidance.voiceOn) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                                label = if (guidance.voiceOn) "Voice" else "Silent",
                                description = if (guidance.voiceOn) "Spoken guidance is on. Tap to go silent." else "Spoken guidance is silent. Tap to turn the voice on.",
                                active = !guidance.voiceOn,
                                onClick = {
                                    guidance.setVoice(!guidance.voiceOn)
                                    toast(if (guidance.voiceOn) "Voice guidance on" else "Silent - instructions are shown on screen only")
                                },
                            )
                        }
                    }
                    // Status pill: connection and GPS state in one glance, bottom-left above whatever is at the bottom. Tap for a plain sentence.
                    if (!drawingState.active && (driving.active || guidance.active)) {
                        val tick by androidx.compose.runtime.produceState(initialValue = com.emfitsolutions.gopreach.platform.nowMillis()) {
                            while (true) { kotlinx.coroutines.delay(5_000L); value = com.emfitsolutions.gopreach.platform.nowMillis() }
                        }
                        var reconnecting by remember { mutableStateOf(false) }
                        var wasOffline by remember { mutableStateOf(false) }
                        LaunchedEffect(online) {
                            if (!online) wasOffline = true
                            else if (wasOffline) { reconnecting = true; kotlinx.coroutines.delay(3_000L); reconnecting = false; wasOffline = false }
                        }
                        val accuracy = shownAccuracy
                        val gpsLevel: StatusLevel
                        val gpsText: String
                        when {
                            locationIssue == LocationIssue.NO_PERMISSION -> { gpsLevel = StatusLevel.WARN; gpsText = "Location permission off" }
                            locationIssue == LocationIssue.SERVICES_OFF -> { gpsLevel = StatusLevel.WARN; gpsText = "Location is off" }
                            shownLocation == null -> { gpsLevel = StatusLevel.INFO; gpsText = "Finding GPS" }
                            driving.active && (driveSnap.quality == com.emfitsolutions.gopreach.domain.navigation.GpsQuality.STALE) -> { gpsLevel = StatusLevel.WARN; gpsText = "GPS signal lost" }
                            lastFixAt > 0L && tick - lastFixAt > 20_000L -> { gpsLevel = StatusLevel.WARN; gpsText = "GPS signal lost" }
                            (accuracy ?: 0f) > 30f || (driving.active && driveSnap.quality == com.emfitsolutions.gopreach.domain.navigation.GpsQuality.WEAK) -> { gpsLevel = StatusLevel.WARN; gpsText = "GPS signal weak" + (accuracy?.let { " ±${it.toInt()} m" } ?: "") }
                            else -> { gpsLevel = StatusLevel.OK; gpsText = "GPS" + (accuracy?.let { " ±${it.toInt()} m" } ?: "") }
                        }
                        val connText = if (online) (if (reconnecting) "Reconnecting" else "Online") else "Offline"
                        val level = if (gpsLevel == StatusLevel.WARN) StatusLevel.WARN else if (!online || reconnecting) StatusLevel.INFO else gpsLevel
                        val explain = when {
                            driving.active -> driving.statusText(online)
                            else -> buildString {
                                append(
                                    when {
                                        locationIssue == LocationIssue.NO_PERMISSION -> "Location permission is off, so your position is not shown."
                                        locationIssue == LocationIssue.SERVICES_OFF -> "The phone's location is turned off, so your position is not shown."
                                        shownLocation == null -> "Looking for your GPS location."
                                        gpsText.startsWith("GPS signal lost") -> "No recent GPS update. The arrow shows your last known position, not your live position."
                                        gpsLevel == StatusLevel.WARN -> "Weak GPS signal (about ${accuracy?.toInt() ?: "?"} m). Your position may be off."
                                        else -> "GPS is working" + (accuracy?.let { ", accurate to about ${it.toInt()} m." } ?: ".")
                                    },
                                )
                                append(if (online) " You are online." else if (com.emfitsolutions.gopreach.data.offlinemap.OfflineMaps.isInstalled()) " You are offline: the GPS and the saved offline map still work, but finding a route needs internet." else " You are offline: the GPS still works, but map tiles that were not saved cannot load and routes need internet.")
                            }
                        }
                        val liftForPill = (if (bottomStripShown) with(LocalDensity.current) { bottomChromePx.toDp() } else 0.dp) + (if (guidance.phase == com.emfitsolutions.gopreach.ui.components.map.GuidancePhase.PREVIEW) 360.dp else if (guidance.active) 150.dp else 0.dp)
                        NavStatusPill(
                            text = "$connText · $gpsText", level = level, onClick = { toast(explain) },
                            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 16.dp + liftForPill),
                        )
                    }
                    // Floating Recenter / Follow button: bottom-right, kept above whatever card or strip is at the bottom (only while driving or navigating).
                    if (!drawingState.active && (driving.active || guidance.active)) {
                        val followUi = when {
                            driving.active -> if (driving.following) FollowUi.DRIVING else FollowUi.PAUSED
                            followMe -> FollowUi.FOLLOWING
                            followPaused -> FollowUi.PAUSED
                            else -> FollowUi.OFF
                        }
                        val lift = (if (bottomStripShown) with(LocalDensity.current) { bottomChromePx.toDp() } else 0.dp) + (if (guidance.phase == com.emfitsolutions.gopreach.ui.components.map.GuidancePhase.PREVIEW) 360.dp else if (guidance.active) 150.dp else 0.dp)
                        RecenterFab(followUi, onClick = { onRecenterButton() }, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp + lift))
                    }
                    if (guidance.phase == com.emfitsolutions.gopreach.ui.components.map.GuidancePhase.PREVIEW) {
                        com.emfitsolutions.gopreach.ui.components.map.RoutePreviewPanel(
                            state = guidance,
                            onStart = { beginGuidance() },
                            onCancel = { guidance.stop() },
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                        )
                    } else if (guidance.active) {
                        com.emfitsolutions.gopreach.ui.components.map.GuidanceBanner(
                            state = guidance,
                            modifier = Modifier.align(Alignment.TopStart)
                                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.Companion.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Top))
                                .padding(10.dp).fillMaxWidth(0.8f),
                        )
                        com.emfitsolutions.gopreach.ui.components.map.GuidancePanel(
                            state = guidance,
                            onEnd = { guidance.stop() },
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                        )
                    }
                    // Full screen: the controls above the map are hidden, so keep the
                    // essentials floating on it — exit, which group this is, and the
                    // record-type filter.
                    if (fullScreenActive && showFilterPanel && !guidance.active && !driving.active) {
                        Column(
                            modifier = Modifier.align(Alignment.TopStart).then(if (embedded) Modifier else Modifier.windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.Companion.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Top)))
                                .then(if (embedded) Modifier.padding(start = 10.dp, top = 10.dp, end = 78.dp) else Modifier.padding(10.dp).fillMaxWidth(0.82f))
                                // In the tab the group and filter chips sit on one soft panel, ending before the button column on the right.
                                .then(if (embedded) Modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)).padding(8.dp) else Modifier),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!embedded) MapToolButton(icon = Icons.AutoMirrored.Rounded.ArrowBack, label = "Exit", onClick = { fullScreen = false })
                                if (selectedGroup != null) {
                                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), shadowElevation = 3.dp) {
                                        Row(
                                            modifier = Modifier.height(40.dp).padding(horizontal = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Box(Modifier.size(14.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(groupColorHex))))
                                            Text(selectedGroup.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            viewOnlyNote?.let { ViewOnlyTag(it) }
                                        }
                                    }
                                } else viewOnlyNote?.let { ViewOnlyTag(it) }
                            }
                            if (embedded) {
                                // Same as the Ministry tab: "Show my Ministry Record Only" (ticked at first), the My drop-down and, once unticked, the Congregation one.
                                val types = listOf(TypeFilter.SEARCHING, TypeFilter.RETURN_VISIT, TypeFilter.BIBLE_STUDY)
                                Row(
                                    modifier = Modifier.clickable { mineOnly = !mineOnly; if (mineOnly) congregationRecords = false },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    androidx.compose.material3.Checkbox(checked = mineOnly, onCheckedChange = { mineOnly = it; if (it) congregationRecords = false })
                                    Text("Show my Ministry Record Only", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    com.emfitsolutions.gopreach.ui.components.MinistryPicker(
                                        "My Records", "My", types.indexOf(typeFilter).takeIf { it >= 0 && !congregationRecords },
                                        { k -> congregationRecords = false; typeFilter = k?.let { types[it] } ?: TypeFilter.ALL }, Modifier.fillMaxWidth(), allowAll = true,
                                    )
                                    if (!mineOnly) com.emfitsolutions.gopreach.ui.components.MinistryPicker(
                                        "Congregation Records", "Congregation", types.indexOf(typeFilter).takeIf { it >= 0 && congregationRecords },
                                        { k -> congregationRecords = true; typeFilter = k?.let { types[it] } ?: TypeFilter.ALL }, Modifier.fillMaxWidth(), allowAll = true,
                                    )
                                }
                            } else Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TypeFilter.entries.forEach { f ->

                                    FilterChip(selected = typeFilter == f, onClick = { typeFilter = f }, label = { Text(f.label, style = if (embedded) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge) })
                                }
                            }
                            // Province / Municipality / Barangay, the status chips and Next Nearest / Farthest are in the full filter view.
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { showFilterPanel = false; fullScreen = false }) { Text("More filters") }
                                TextButton(onClick = { showFilterPanel = false }) { Text("Close") }
                            }
                        }
                    }
                    if (bottomStripShown) {
                        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomChromePx = it.height }) {
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
                        else -> RecordListView(
                            areas = visibleAreas,
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
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    com.emfitsolutions.gopreach.ui.components.RecordLocationActions(lat = lat, lng = lng, name = null)
                    val here = com.emfitsolutions.gopreach.domain.map.GeoPoint(lat, lng)
                    val canDraw = !readOnly && congregationId != null && !isLoading && drawingAccess.canDrawAt(here, drawingTerritories, congregationId)
                    // Map tools: everything that is not on the clean map (Recenter, Nearest, Farthest, Filters, Full screen, GPS status).
                    val tools = buildList<Triple<androidx.compose.ui.graphics.vector.ImageVector, String, () -> Unit>> {
                        add(Triple(Icons.Rounded.Tune, "Filters", { longPressPoint = null; fullScreen = true; showFilterPanel = true }))
                        add(Triple(Icons.Rounded.MyLocation, "Recenter", { longPressPoint = null; onRecenterButton() }))
                        add(Triple(Icons.Rounded.NearMe, "Nearest", { longPressPoint = null; stepTo(SortOrder.NEAREST) }))
                        add(Triple(Icons.Rounded.Navigation, "Farthest", { longPressPoint = null; stepTo(SortOrder.FARTHEST) }))
                        if (!embedded) add(Triple(if (fullScreenActive) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, if (fullScreenActive) "Exit full screen" else "Full screen", { longPressPoint = null; fullScreen = !fullScreen }))
                        add(Triple(Icons.Rounded.GpsFixed, "GPS status", { longPressPoint = null; toast(if (myLocation == null) "Finding your GPS location." else "GPS is working." + (shownAccuracy?.let { " Accurate to about ${it.toInt()} m." } ?: "")) }))
                    }
                    Text("Map tools", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    tools.chunked(3).forEach { rowTools ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            rowTools.forEach { (icon, label, action) -> MapToolTile(icon, label, action, Modifier.weight(1f)) }
                            repeat(3 - rowTools.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    // Draw is offered only where this role may draw (inside the assigned territory for group-level users,
                    // anywhere on their congregation's map for the wider roles); the map links are always available.
                    if (canDraw) {
                        Button(
                            onClick = { longPressPoint = null; drawingState.enter(here) },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) { Text("Draw") }
                    }
                    Button(
                        onClick = { longPressPoint = null; startNavigation("Selected place", lat, lng) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("Start navigation") }
                    TextButton(onClick = { longPressPoint = null }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }
            },
            confirmButton = {},
        )
    }

    // Create a Pin: a short text mark, saved online.
    pinDraftPoint?.let { (lat, lng) ->
        AlertDialog(
        scrollBody = true,
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
                    com.emfitsolutions.gopreach.ui.components.RecordLocationActions(lat = pin.lat, lng = pin.lng, name = null)
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
                modifier = Modifier.fillMaxWidth().keyboardPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp),
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
            onGuide = { sheetOpen = false; startNavigation(sheetRecord.record.name, sheetRecord.record.lat, sheetRecord.record.lng) },
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
/** One option of the map-style row (Standard / Satellite / Night / 3D). */
@Composable
private fun MapStyleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MapToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, active: Boolean = false, description: String = label) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        shadowElevation = 3.dp,
        modifier = Modifier.width(58.dp).height(46.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp), tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One tile of the long-press "Map tools" grid: the tools that are not on the clean map. */
@Composable
private fun MapToolTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f), modifier = modifier.height(64.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

private enum class FollowUi { OFF, FOLLOWING, DRIVING, PAUSED }

/** A small "View only" tag: the territory shown is for reference and cannot be drawn on or edited by this person. */
@Composable
private fun ViewOnlyTag(text: String) {
    Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFFE6B800).copy(alpha = 0.22f)) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/** How the status pill's dot is coloured: the theme purple when all is well, grey for information (offline, searching), medium yellow for a warning. */
private enum class StatusLevel { OK, INFO, WARN }

/** A compact "Online · GPS ±8 m" pill with a status dot. Colours are the app's own (never the traffic colours). */
@Composable
private fun NavStatusPill(text: String, level: StatusLevel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dot = when (level) {
        StatusLevel.OK -> MaterialTheme.colorScheme.primary
        StatusLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        StatusLevel.WARN -> Color(0xFFE6B800)
    }
    Surface(
        onClick = onClick, shape = RoundedCornerShape(20.dp), shadowElevation = 3.dp, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        modifier = modifier.semantics { contentDescription = "Status: $text. Tap for details." },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
/** The round Recenter / Follow button with a small caption that says what it is doing: nothing when off, "Following" / "Driving" while it follows,
 * and a filled "Recenter" caption when following was paused by moving the map. */
@Composable
private fun RecenterFab(ui: FollowUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val following = ui == FollowUi.FOLLOWING || ui == FollowUi.DRIVING
    val description = when (ui) {
        FollowUi.OFF -> "Recenter on my location and follow me"
        FollowUi.FOLLOWING -> "Following your location. Tap to recenter."
        FollowUi.DRIVING -> "Driving Mode is following you. Tap to recenter."
        FollowUi.PAUSED -> "Following is paused. Tap to recenter on your location."
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            onClick = onClick, shape = CircleShape, shadowElevation = 8.dp, modifier = Modifier.size(60.dp),
            border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
            color = if (following) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (following) Icons.Rounded.NearMe else Icons.Rounded.MyLocation, contentDescription = description, modifier = Modifier.size(26.dp),
                    tint = if (following) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                )
            }
        }
        // The name of the button, always visible under it.
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary, shadowElevation = 3.dp, modifier = Modifier.width(60.dp)) {
            Text(
                "Recenter", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary,
                maxLines = 1, softWrap = false, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(vertical = 3.dp).fillMaxWidth(),
            )
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
    /** "Territories (n)" heading of a group (or of the single group on screen). */
    data class TerritoryHeader(val scope: String, val count: Int) : ListEntry {
        override val key get() = "th_" + scope
    }
    /** One territory = one barangay claimed by the FS Group. */
    data class Territory(val area: TerritoryArea) : ListEntry {
        override val key get() = "ta_" + area.id
    }
}

/** List View. The whole list scrolls, it can be printed / saved as a PDF, and the record picked with
 * "Show Next Nearest/Farthest" is highlighted and scrolled to. With a single FS Group it is grouped by
 * record type; with "Show All FS Groups" it is organized by FS Group first, then by type. */
@Composable
private fun RecordListView(
    areas: List<TerritoryArea>,
    sorted: List<RecordWithDistance>,
    hasLocation: Boolean,
    showAllGroups: Boolean,
    groupColors: Map<String, String>,
    selectedId: String?,
    onClick: (String) -> Unit,
    onPrint: () -> Unit,
) {
    val entries = remember(sorted, areas, showAllGroups) {
        buildList<ListEntry> {
            fun addTerritories(scope: String, list: List<TerritoryArea>) {
                if (list.isEmpty()) return
                add(ListEntry.TerritoryHeader(scope, list.size))
                list.sortedWith(compareBy({ it.municipality.lowercase() }, { it.barangay.lowercase() })).forEach { add(ListEntry.Territory(it)) }
            }
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
                // Every FS Group that has territories or records: its territories first, then its records by type.
                val groupIds = (areas.map { it.groupId to it.groupName } + sorted.map { it.record.groupId.orEmpty() to (it.record.groupName ?: "Unknown group") }).distinct()
                    .sortedBy { it.second.lowercase() }
                groupIds.forEach { (gid, gname) ->
                    val groupRecords = sorted.filter { it.record.groupId.orEmpty() == gid }
                    add(ListEntry.GroupHeader(gname, groupColors[gid], groupRecords.size))
                    addTerritories(gid, areas.filter { it.groupId == gid })
                    addByType(gid, groupRecords)
                }
            } else {
                addTerritories("", areas)
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
                is ListEntry.TerritoryHeader -> Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.Map, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Territories", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("(" + entry.count + ")", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is ListEntry.Territory -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(entry.area.barangay, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.area.municipality, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
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
private fun RecordSheet(item: RecordWithDistance, onDismiss: () -> Unit, onViewDetails: () -> Unit, onGuide: () -> Unit) {
    val r = item.record
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            modifier = Modifier.fillMaxWidth().keyboardPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Who, what kind of record, and how far.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(r.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(markerColor(r.type)))
                    Text(r.type.label, style = MaterialTheme.typography.titleSmall)
                    Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (item.meters != null) "${formatDistance(item.meters)} away" else "Distance unavailable",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // The few facts that matter on the road, each only when it exists.
            val currentAddress = listOfNotNull(r.barangay?.let { "Barangay $it" }, r.municipality, r.province).joinToString(", ")
            val where = currentAddress.ifBlank { r.person.address }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (where.isNotBlank()) Text(where, style = MaterialTheme.typography.bodyMedium)
                listOfNotNull(r.groupName, r.territoryName).takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                r.person.contact?.takeIf { it.isNotBlank() }?.let { Text("Contact: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                r.person.notes?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }
            // Where it is: coordinates, Street View, Open in Maps.
            com.emfitsolutions.gopreach.ui.components.RecordLocationActions(lat = r.lat, lng = r.lng, name = r.name)
            Button(onClick = onGuide, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Icon(Icons.Rounded.Directions, contentDescription = null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Start navigation")
            }
            OutlinedButton(onClick = onViewDetails, modifier = Modifier.fillMaxWidth().height(46.dp)) {
                Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("View full details")
            }
        }
    }
}
