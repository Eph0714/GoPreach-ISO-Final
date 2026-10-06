package com.emfitsolutions.gopreach.ui.components.map

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.DrawingStatus
import com.emfitsolutions.gopreach.data.model.DrawingSyncState
import com.emfitsolutions.gopreach.domain.map.DrawingAccess
import com.emfitsolutions.gopreach.domain.map.DrawingGeometry
import com.emfitsolutions.gopreach.domain.map.DrawingValidation
import com.emfitsolutions.gopreach.domain.map.DrawingValidator
import com.emfitsolutions.gopreach.domain.map.GeoPoint
import com.emfitsolutions.gopreach.domain.map.TerritoryBoundary
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.roundToInt

private fun LatLng.toGeo() = GeoPoint(latitude, longitude)

/**
 * The reusable drawing layer for any map screen. Drawing is the **Polygon Lasso**: tap the map to place corner points,
 * a straight line joins each new corner to the previous one, and Finish closes the polygon. This overlay draws the saved
 * drawings and the polygon being built, handles the map taps, validates every corner against the user's drawing
 * permission, and shows the info panel and dialogs. Corner dragging lives in [polygonCornerGestures] (attached to the map
 * itself, so the map stays freely movable/zoomable whenever the user is not on a corner). The tools / status / name /
 * remarks live in the dock below the map ([DrawingDock]) so nothing covers the canvas; the dock drives this overlay
 * through [MapDrawingState].
 *
 * Place it in [MapLibreHost]'s `content` slot (created with `drawingLayers = true`). Permissions, validation, offline
 * saving and sync state all come from [MapDrawingViewModel].
 *
 * [territories] are the territories (with real boundary rings) currently loaded on the map: what a drawing is validated
 * against. [drawings] are the saved drawings to show.
 */
@Composable
fun BoxScope.MapDrawingOverlay(
    state: MapDrawingState,
    map: MapLibreMap?,
    styleVersion: Int,
    access: DrawingAccess,
    territories: List<TerritoryBoundary>,
    /** The saved drawings to show — the screen decides which (its Barangay / FS Group selection), the overlay just draws them. */
    drawings: List<com.emfitsolutions.gopreach.data.model.TerritoryDrawing>,
    /** The congregation whose map this is (the Super Admin's selection; everyone else's own). */
    congregationId: String,
    modifier: Modifier = Modifier,
    viewModel: MapDrawingViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val scope = rememberCoroutineScope()

    val syncStates by remember { viewModel.syncStates() }.collectAsStateWithLifecycle(initialValue = emptyMap())
    val groups by remember { viewModel.groups() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val congregations by remember { viewModel.congregations() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val restricted = remember(access, territories) { access.restrictedRings(territories) }

    var rejection by remember { mutableStateOf<DrawingValidation?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<com.emfitsolutions.gopreach.data.model.TerritoryDrawing?>(null) }

    // Congregation-wide roles publish territory bounding boxes so the server can enforce the group-level rule.
    LaunchedEffect(access, territories) { viewModel.publishBounds(access, territories) }

    // Entering Drawing Mode from too far out: zoom in around the long-press so precise corner placement is possible.
    LaunchedEffect(state.focusPoint) {
        val m = map ?: return@LaunchedEffect
        val focus = state.focusPoint ?: return@LaunchedEffect
        if (m.cameraPosition.zoom < MIN_DRAWING_ZOOM) {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(focus.lat, focus.lng), DRAWING_ZOOM), 450)
        }
    }

    // ---- layers ---------------------------------------------------------------
    val editingId = state.editing?.id
    LaunchedEffect(map, styleVersion, drawings, syncStates, state.selectedDrawingId, editingId, state.statusFilter) {
        val style = map?.style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        MapDrawingLayers.setSaved(style, drawings, syncStates, state.selectedDrawingId, setOfNotNull(editingId), state.statusFilter)
    }
    val items = state.items
    val showCorners = state.active && state.tool == DrawingTool.POINTS
    LaunchedEffect(map, styleVersion, items, showCorners, state.selectedVertex, state.rejectedPoint) {
        val style = map?.style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        MapDrawingLayers.setDraft(style, items, showCorners, state.selectedVertex, state.rejectedPoint)
    }
    LaunchedEffect(map, styleVersion, state.outsideRuns) {
        val style = map?.style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        MapDrawingLayers.setOutside(style, state.outsideRuns)
    }
    // A refused point flashes red for a moment.
    LaunchedEffect(state.rejectedPoint) {
        if (state.rejectedPoint != null) { delay(1600); state.rejectedPoint = null }
    }

    // ---- save / cancel / finish (driven by the dock's buttons) ----------------------
    fun save() {
        if (state.saving || !state.hasDraft) return
        state.saveBlocker?.let { why ->
            Toast.makeText(context, why, Toast.LENGTH_SHORT).show()
            return
        }
        state.saving = true
        scope.launch {
            val outcome = runCatching { viewModel.save(access, state.items, territories, congregationId) }
            state.saving = false
            outcome.onFailure { Toast.makeText(context, "Couldn't save the drawing: " + (it.message ?: "unknown error"), Toast.LENGTH_LONG).show() }
            outcome.onSuccess {
                when (it) {
                    is DrawingSaveOutcome.Saved -> {
                        Toast.makeText(context, if (it.count == 1) "Drawing saved." else "${it.count} drawings saved.", Toast.LENGTH_SHORT).show()
                        state.exit()
                    }
                    is DrawingSaveOutcome.Incomplete -> Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
                    is DrawingSaveOutcome.Rejected -> {
                        // The parts of the outline outside the permitted territory are drawn in red.
                        state.outsideRuns = (it.validation as? DrawingValidation.OutsideAssigned)?.outsideRuns.orEmpty()
                        rejection = it.validation
                    }
                }
            }
        }
    }

    fun finish() {
        val poly = state.activeItem
        if (poly == null || !state.canFinish) {
            Toast.makeText(context, "Add at least 3 corner points to create the territory.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!DrawingGeometry.isSimple(poly.ring)) {
            Toast.makeText(context, "The outline crosses itself. Move or remove a corner so the lines don't cross.", Toast.LENGTH_LONG).show()
            return
        }
        // The closing line must stay inside the authorized area too (group-level users).
        val runs = restricted?.let { DrawingGeometry.outsideRuns(poly.ring, it, DrawingValidator.EDGE_TOLERANCE_METERS) }.orEmpty()
        if (runs.isNotEmpty()) {
            state.outsideRuns = runs
            Toast.makeText(context, "Part of the polygon is outside your authorized territory. Move a corner so it stays inside.", Toast.LENGTH_LONG).show()
            return
        }
        state.finish()
    }

    fun cancel() {
        if (state.hasDraft && (state.editing == null || state.canUndo)) confirmDiscard = true else state.exit()
    }
    state.saveHandler = { save() }
    state.cancelHandler = { cancel() }
    state.finishHandler = { finish() }

    // A map tap in Drawing Mode places the next corner (corners/midpoints are handled by polygonCornerGestures first).
    fun reject(p: GeoPoint, message: String) {
        state.flashRejected(p)
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
    state.mapTapHandler = tap@{ latLng ->
        if (!state.active || state.tool != DrawingTool.POINTS) return@tap true
        val g = latLng.toGeo()
        val poly = state.activeItem
        if (poly != null && poly.closed) {
            Toast.makeText(context, "Drag a corner to move it, or tap a small dot on a line to add one.", Toast.LENGTH_SHORT).show()
            return@tap true
        }
        // Every corner is validated against the drawing permission as it is placed.
        if (restricted != null && !DrawingGeometry.isAllowed(restricted, g, DrawingValidator.EDGE_TOLERANCE_METERS)) {
            reject(g, "That point is outside your authorized territory. You can only mark areas within it.")
            return@tap true
        }
        if (poly != null && poly.ring.any { DrawingGeometry.distanceMeters(it, g) < DUPLICATE_METERS }) {
            reject(g, "A corner is already there.")
            return@tap true
        }
        if (poly != null && DrawingGeometry.openPathCrossesItself(poly.ring + g)) {
            reject(g, "That line would cross another line. Choose a different corner.")
            return@tap true
        }
        state.addCorner(g)
        true
    }
    DisposableEffect(state) {
        onDispose { state.saveHandler = null; state.cancelHandler = null; state.finishHandler = null; state.mapTapHandler = null }
    }
    BackHandler(enabled = state.active) { cancel() }

    // ---- precision magnifier while a corner is dragged -------------------------------
    VertexMagnifier(state, density)

    // ---- info panel (never while drawing: the canvas stays clear) ---------------------
    if (!state.active) {
        val selected = drawings.firstOrNull { it.id == state.selectedDrawingId }
        if (selected != null) {
            DrawingInfoPanel(
                drawing = selected,
                groupName = groups.firstOrNull { it.id == selected.groupId }?.name,
                congregationName = congregations.firstOrNull { it.id == selected.congregationId }?.name,
                syncState = syncStates[selected.id] ?: DrawingSyncState.SYNCED,
                actions = DrawingPanelActions(
                    canManage = access.canManage(selected),
                    onEdit = { state.startEditing(selected) },
                    onChangeStatus = { status ->
                        scope.launch {
                            runCatching { viewModel.changeStatus(access, selected, status) }
                                .onFailure { Toast.makeText(context, "Couldn't change the status.", Toast.LENGTH_SHORT).show() }
                        }
                    },
                    onDelete = { confirmDelete = selected },
                    onRetrySync = {
                        scope.launch { viewModel.retrySync(selected.id) }
                        Toast.makeText(context, "Retrying sync.", Toast.LENGTH_SHORT).show()
                    },
                    onClose = { state.selectedDrawingId = null },
                ),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp),
            )
        }
    }

    // ---- dialogs --------------------------------------------------------------
    rejection?.let { r ->
        val (title, body) = when (r) {
            is DrawingValidation.OutsideAssigned -> "Drawing Outside Assigned Territory" to "You can only mark areas within your assigned territory."
            DrawingValidation.OtherCongregation -> "Another Congregation's Map" to "You can only draw on your own congregation's map."
            DrawingValidation.SelfIntersecting -> "Drawing Crosses Itself" to "The outline overlaps itself. Move or remove a corner so the lines don't cross."
            DrawingValidation.NotAllowed -> "No Access" to "Your role can't draw on the map."
            is DrawingValidation.Valid -> "" to ""
        }
        AlertDialog(
            onDismissRequest = { rejection = null },
            title = { Text(title) },
            text = { Text(body) },
            dismissButton = { TextButton(onClick = { rejection = null; state.exit() }) { Text("Cancel") } },
            // Adjust: the offending parts stay highlighted in red; the corners can be dragged to fix them.
            confirmButton = { TextButton(onClick = { rejection = null; state.tool = DrawingTool.POINTS }) { Text("Adjust Drawing") } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard drawing?") },
            text = { Text("What you drew hasn't been saved.") },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep Drawing") } },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; state.exit() }) { Text("Discard") } },
        )
    }
    confirmDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(if (d.status == DrawingStatus.FINISHED) "Delete Completed Territory?" else "Delete Territory Drawing?") },
            text = { Text("This drawing will be permanently removed from the map.") },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch {
                        runCatching { viewModel.delete(access, d) }
                            .onSuccess { state.selectedDrawingId = null; Toast.makeText(context, "Deleted.", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(context, "Couldn't delete it.", Toast.LENGTH_SHORT).show() }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

/** Below this zoom, precise corner placement is hard: entering Drawing Mode zooms in (around the long press) to [DRAWING_ZOOM]. */
private const val MIN_DRAWING_ZOOM = 14.5
private const val DRAWING_ZOOM = 16.5

/** A new corner closer than this to an existing one is a duplicate. */
private const val DUPLICATE_METERS = 1.0

/** Magnifier: source square (dp) copied from the screen, and the on-screen loupe diameter (dp). */
private const val LOUPE_SOURCE_DP = 44
private const val LOUPE_SIZE_DP = 112

private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Corner gestures of the Polygon Lasso, attached to the MAP view itself (the Initial pass): they act only when a finger
 * lands on a corner (drag to move it, tap to select it) or on a small midpoint dot (adds a corner there, which can be
 * dragged straight away). Anywhere else the touch is left alone, so the map pans, zooms and rotates normally and plain
 * taps place new corners (via the map's tap callback). Every move is validated against [restricted] — a group-level
 * user's corner simply refuses to leave their territory.
 */
fun Modifier.polygonCornerGestures(state: MapDrawingState, map: MapLibreMap?, density: Float, restricted: List<List<GeoPoint>>?): Modifier {
    if (map == null) return this
    return this.pointerInput(map, state, restricted) {
        val hitRadius = 30f * density
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!state.active || state.tool != DrawingTool.POINTS) return@awaitEachGesture
            val poly = state.activeItem ?: return@awaitEachGesture
            fun screen(p: GeoPoint): PointF = map.projection.toScreenLocation(LatLng(p.lat, p.lng))
            fun dist(o: Offset, p: PointF) = hypot(o.x - p.x, o.y - p.y)
            val verts = poly.ring.map(::screen)
            var index = verts.indices.minByOrNull { dist(down.position, verts[it]) }?.takeIf { dist(down.position, verts[it]) <= hitRadius }
            var inserted = false
            if (index == null) {
                // A midpoint dot of an edge: adds a corner right there.
                val edges = if (poly.closed) poly.ring.size else poly.ring.size - 1
                val mids = (0 until edges).map { i -> val a = verts[i]; val b = verts[(i + 1) % verts.size]; PointF((a.x + b.x) / 2, (a.y + b.y) / 2) }
                val m = mids.indices.minByOrNull { dist(down.position, mids[it]) }?.takeIf { dist(down.position, mids[it]) <= hitRadius }
                if (m == null || poly.ring.size >= DrawingGeometry.MAX_VERTICES) return@awaitEachGesture
                val a = poly.ring[m]
                val b = poly.ring[(m + 1) % poly.ring.size]
                state.insertCorner(m, GeoPoint((a.lat + b.lat) / 2, (a.lng + b.lng) / 2))
                index = m + 1
                inserted = true
            }
            down.consume()
            val rotateWas = map.uiSettings.isRotateGesturesEnabled
            map.uiSettings.setAllGesturesEnabled(false)
            var moved = false
            try {
                state.selectedVertex = index
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    if (!moved && dist(change.position, PointF(down.position.x, down.position.y)) > slop) moved = true
                    if (moved) {
                        val g = map.projection.fromScreenLocation(PointF(change.position.x, change.position.y)).toGeo()
                        if (restricted == null || DrawingGeometry.isAllowed(restricted, g, DrawingValidator.EDGE_TOLERANCE_METERS)) {
                            state.moveCorner(index, g)
                        }
                        state.dragScreen = change.position
                    }
                }
            } finally {
                map.uiSettings.setAllGesturesEnabled(true)
                map.uiSettings.isRotateGesturesEnabled = rotateWas
                state.dragScreen = null
                // One undoable step: the insert and/or the move.
                state.commitLive()
            }
        }
    }
}

/** The precision loupe shown while a corner is being dragged: a zoomed view of the pixels around the finger, held clear of it. */
@Composable
private fun BoxScope.VertexMagnifier(state: MapDrawingState, density: Float) {
    val context = LocalContext.current
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var boxOrigin by remember { mutableStateOf(Offset.Zero) }
    var loupe by remember { mutableStateOf<ImageBitmap?>(null) }
    val capturing = remember { AtomicBoolean(false) }

    // A non-interactive, full-size marker just to learn where this layer sits in the window.
    Box(Modifier.fillMaxSize().onGloballyPositioned { boxSize = it.size; boxOrigin = it.positionInWindow() })

    val finger = state.dragScreen
    LaunchedEffect(finger != null) {
        if (finger == null) { loupe = null; return@LaunchedEffect }
        while (isActive) {
            val o = state.dragScreen ?: break
            // Copies the pixels around the finger (API 26+; below that the loupe is just a crosshair).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val activity = context.findActivity()
                if (activity != null) {
                    val half = (LOUPE_SOURCE_DP * density / 2).roundToInt()
                    val cx = (boxOrigin.x + o.x).roundToInt()
                    val cy = (boxOrigin.y + o.y).roundToInt()
                    val rect = Rect(cx - half, cy - half, cx + half, cy + half)
                    val decor = activity.window.decorView
                    if (rect.left >= 0 && rect.top >= 0 && rect.right <= decor.width && rect.bottom <= decor.height && capturing.compareAndSet(false, true)) {
                        val bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
                        runCatching {
                            PixelCopy.request(activity.window, rect, bitmap, { result ->
                                capturing.set(false)
                                if (result == PixelCopy.SUCCESS) loupe = bitmap.asImageBitmap()
                            }, Handler(Looper.getMainLooper()))
                        }.onFailure { capturing.set(false) }
                    }
                }
            }
            delay(40)
        }
    }
    finger?.let { Magnifier(it, boxSize, density, loupe) }
}

/** The precision loupe: a zoomed view of the pixels around the finger with a crosshair, held clear of the finger. */
@Composable
private fun Magnifier(finger: Offset, box: IntSize, density: Float, image: ImageBitmap?) {
    val size = LOUPE_SIZE_DP * density
    val gap = 28f * density
    val pad = 4f * density
    // Above the finger; if there isn't room, to the side with more space.
    var cx = finger.x
    var cy = finger.y - gap - size / 2
    if (cy - size / 2 < pad) {
        cy = finger.y.coerceIn(size / 2 + pad, (box.height - size / 2).coerceAtLeast(size / 2 + pad))
        cx = if (finger.x > box.width / 2f) finger.x - gap - size / 2 else finger.x + gap + size / 2
    }
    cx = cx.coerceIn(size / 2 + pad, (box.width - size / 2).coerceAtLeast(size / 2 + pad))
    Box(
        Modifier
            .offset { IntOffset((cx - size / 2).roundToInt(), (cy - size / 2).roundToInt()) }
            .size(LOUPE_SIZE_DP.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(Color.White)
            .border(3.dp, Color.White, CircleShape),
    ) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low, modifier = Modifier.fillMaxSize())
        }
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val arm = 14.dp.toPx()
            val gapPx = 4.dp.toPx()
            fun cross(color: Color, w: Float) {
                drawLine(color, Offset(c.x - arm, c.y), Offset(c.x - gapPx, c.y), strokeWidth = w)
                drawLine(color, Offset(c.x + gapPx, c.y), Offset(c.x + arm, c.y), strokeWidth = w)
                drawLine(color, Offset(c.x, c.y - arm), Offset(c.x, c.y - gapPx), strokeWidth = w)
                drawLine(color, Offset(c.x, c.y + gapPx), Offset(c.x, c.y + arm), strokeWidth = w)
            }
            cross(Color.White, 4.dp.toPx())
            cross(Color(0xFFD93025), 2.dp.toPx())
            drawCircle(Color(0xFFD93025), 2.dp.toPx(), c)
        }
    }
}

/** The territory list for a map screen, from its loaded area boundaries — shared so every module builds it the same way. */
fun territoryBoundaries(
    congregationId: String,
    areas: List<TerritoryBoundaryInput>,
): List<TerritoryBoundary> = areas.mapNotNull { a ->
    val rings = BoundaryGeometry.outerRings(a.boundaryJson)
        .filter { it.size >= 3 }
        .map { ring -> ring.map { (lat, lng) -> GeoPoint(lat, lng) } }
    if (rings.isEmpty()) null else TerritoryBoundary(a.territoryId, a.name, a.groupId, congregationId, rings, a.provinceId, a.muncityId, a.barangayId)
}

/** One loaded territory as a map screen knows it. */
data class TerritoryBoundaryInput(
    val territoryId: String, val name: String, val groupId: String, val boundaryJson: String,
    val provinceId: Int = 0, val muncityId: Int = 0, val barangayId: Int = 0,
)
