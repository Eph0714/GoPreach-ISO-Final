package com.emfitsolutions.gopreach.ui.components.map

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.emfitsolutions.gopreach.data.model.DrawingStatus
import com.emfitsolutions.gopreach.data.model.TerritoryDrawing
import com.emfitsolutions.gopreach.domain.map.DrawingGeometry
import com.emfitsolutions.gopreach.domain.map.GeoPoint
import org.maplibre.android.geometry.LatLng
import java.util.UUID

/** Shown for a polygon whose status hasn't been picked yet — deliberately none of the three status colors. */
const val UNSET_STATUS_COLOR = "#9E9E9E"

/** Where every new drawing's fill opacity starts (10%); the user may change it afterwards. */
const val DEFAULT_DRAWING_OPACITY = 0.10f

/** A slightly darker shade of [fillHex], for outlines. */
fun darkerBorder(fillHex: String): String {
    val c = runCatching { android.graphics.Color.parseColor(fillHex) }.getOrDefault(android.graphics.Color.GRAY)
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c, hsv)
    hsv[2] = (hsv[2] * 0.68f).coerceIn(0f, 1f)
    return String.format("#%06X", 0xFFFFFF and android.graphics.Color.HSVToColor(hsv))
}

/** Which tool owns map taps while Drawing Mode is on. [PAN] only moves the map; [POINTS] is the Polygon Lasso (tap corners). */
enum class DrawingTool { PAN, POINTS }

/**
 * A polygon being built or edited. While [closed] is false it is the corner list of a polygon still being placed
 * (its outline is an OPEN line — the last corner is NOT yet joined to the first); Finish closes it. [status] is null
 * until picked (saving is blocked until then); the color follows the status and nothing else.
 */
data class DraftPolygon(
    val key: String,
    val ring: List<GeoPoint>,
    val opacity: Float,
    val status: DrawingStatus?,
    val remarks: String = "",
    val name: String = "",
    val closed: Boolean = true,
    val existing: TerritoryDrawing? = null,
) {
    val fillColor: String get() = status?.colorHex ?: UNSET_STATUS_COLOR
    val borderColor: String get() = status?.borderHex ?: darkerBorder(UNSET_STATUS_COLOR)
}

/**
 * All of Drawing Mode's UI state, kept apart from any map/Compose-view code so every map module drives the same behavior.
 *
 * Drawing is the **Polygon Lasso**: the user taps corners; a straight line joins each new corner to the previous one;
 * Finish closes the polygon. Corners can be dragged, selected, removed, or inserted between two others. The session is a
 * list of [DraftPolygon]s with a snapshot history, so Undo/Redo is a cursor over [history] (one step per corner added,
 * moved, removed, inserted, status/opacity change or clear).
 *
 * The status/name/remarks controls live in a dock *outside* the map (see DrawingDock), so this state is hoisted to the
 * screen and the dock drives save/cancel through handlers the overlay registers.
 */
@Stable
class MapDrawingState {
    /** Drawing Mode on ("DRAWING MODE" banner, dock below the map). */
    var active by mutableStateOf(false)
        private set
    var tool by mutableStateOf(DrawingTool.PAN)

    /** Opacity for new polygons. */
    var opacity by mutableFloatStateOf(DEFAULT_DRAWING_OPACITY)
        private set

    /** Map filter: show only polygons with this status (null = All). */
    var statusFilter: DrawingStatus? by mutableStateOf(null)

    /** Hide Icons / Show Icons (the map control, and Draw Mode's Hide Markers): hides every record / pin marker. One setting for the whole Territory Map — it survives switching views, opening a Barangay and leaving Draw Mode. */
    var markersHidden by mutableStateOf(false)

    /** Set while an existing drawing is being edited. */
    var editing: TerritoryDrawing? by mutableStateOf(null)
        private set

    private var history by mutableStateOf<List<List<DraftPolygon>>>(listOf(emptyList()))
    private var cursor by mutableIntStateOf(0)

    /** In-flight items while a corner/slider/text field is being changed — shown but not yet a history step. */
    var live: List<DraftPolygon>? by mutableStateOf(null)

    /** Parts of the outline outside the permitted territory, from the last failed validation. */
    var outsideRuns: List<List<GeoPoint>> by mutableStateOf(emptyList())

    /** The saved drawing whose info panel is open. */
    var selectedDrawingId: String? by mutableStateOf(null)

    /** The corner (index into the active polygon's ring) picked for removal/editing. */
    var selectedVertex: Int? by mutableStateOf(null)

    /** A tapped point that was refused (outside the user's territory, a duplicate…): flashed in red so it is obvious. */
    var rejectedPoint: GeoPoint? by mutableStateOf(null)

    /** Screen position (map-view pixels) of the corner being dragged — drives the precision magnifier; null otherwise. */
    var dragScreen: Offset? by mutableStateOf(null)

    /** Registered by the overlay: what a map tap does in Drawing Mode (true = handled). */
    var mapTapHandler: ((LatLng) -> Boolean)? = null

    /** Registered by the overlay: what the dock's Save / Cancel / Finish buttons do. */
    var saveHandler: (() -> Unit)? = null
    var cancelHandler: (() -> Unit)? = null
    var finishHandler: (() -> Unit)? = null
    var saving by mutableStateOf(false)

    /** Set by [enter]: where the long press happened, so the overlay can zoom in there if the map is too far out. */
    var focusPoint: GeoPoint? by mutableStateOf(null)
        private set

    val items: List<DraftPolygon> get() = live ?: history[cursor]
    val canUndo: Boolean get() = cursor > 0
    val canRedo: Boolean get() = cursor < history.lastIndex
    val hasDraft: Boolean get() = items.isNotEmpty()

    /** The polygon the dock edits: the last one in the session. */
    val activeItem: DraftPolygon? get() = items.lastOrNull()

    /** True while corners are still being placed (the polygon is not closed yet). */
    val isPlacingCorners: Boolean get() = activeItem?.closed == false

    /** Finish needs at least 3 corners. */
    val canFinish: Boolean get() = activeItem?.let { !it.closed && it.ring.size >= 3 } == true

    /** Why Save is blocked, or null when the draft is complete. */
    val saveBlocker: String?
        get() = when {
            items.isEmpty() -> "Add corner points on the map first."
            isPlacingCorners -> "Finish the polygon first (add at least 3 corner points, then tap Finish)."
            items.any { it.status == null } -> "Select a territory status first."
            else -> null
        }

    /** A long press on the map: turn Drawing Mode on with the Polygon Lasso ready. */
    fun enter(at: GeoPoint? = null) {
        reset()
        active = true
        tool = DrawingTool.POINTS
        focusPoint = at
    }

    /** Leaves Drawing Mode, discarding the draft. */
    fun exit() {
        reset()
        active = false
    }

    private fun reset() {
        history = listOf(emptyList())
        cursor = 0
        live = null
        outsideRuns = emptyList()
        editing = null
        tool = DrawingTool.PAN
        selectedDrawingId = null
        selectedVertex = null
        rejectedPoint = null
        dragScreen = null
        focusPoint = null
        saving = false
    }

    fun commit(next: List<DraftPolygon>) {
        history = history.take(cursor + 1) + listOf(next)
        cursor = history.lastIndex
        live = null
        outsideRuns = emptyList()
    }

    fun undo() { if (canUndo) { cursor--; live = null; outsideRuns = emptyList(); selectedVertex = null } }
    fun redo() { if (canRedo) { cursor++; live = null; outsideRuns = emptyList(); selectedVertex = null } }

    /** Clear: drop every point and start again (itself undoable). */
    fun clear() { if (items.isNotEmpty()) { commit(emptyList()); selectedVertex = null } }

    // ---- corners ------------------------------------------------------------------

    /** Adds a corner: starts a new open polygon, or extends the one being placed (the line to the previous corner is automatic). */
    fun addCorner(p: GeoPoint) {
        val last = items.lastOrNull()
        if (last != null && !last.closed) {
            commit(items.dropLast(1) + last.copy(ring = last.ring + p))
            selectedVertex = last.ring.size
        } else {
            commit(items + DraftPolygon(UUID.randomUUID().toString(), listOf(p), opacity, status = null, closed = false))
            selectedVertex = 0
        }
    }

    /** Closes the polygon being placed (the last corner joins the first). Needs 3+ corners. */
    fun finish(): Boolean {
        val last = activeItem ?: return false
        if (last.closed || last.ring.size < 3) return false
        commit(items.dropLast(1) + last.copy(closed = true))
        selectedVertex = null
        return true
    }

    /** Remove Last Point: the most recently added corner. */
    fun removeLastCorner() {
        val last = activeItem ?: return
        if (last.closed) return
        val ring = last.ring.dropLast(1)
        commit(if (ring.isEmpty()) items.dropLast(1) else items.dropLast(1) + last.copy(ring = ring))
        selectedVertex = null
    }

    /** Removes the selected corner (a finished polygon keeps at least 3). */
    fun removeSelectedCorner() {
        val last = activeItem ?: return
        val i = selectedVertex ?: return
        if (i !in last.ring.indices || (last.closed && last.ring.size <= 3)) return
        val ring = last.ring.toMutableList().also { it.removeAt(i) }
        commit(if (ring.isEmpty()) items.dropLast(1) else items.dropLast(1) + last.copy(ring = ring))
        selectedVertex = null
    }

    /** Adds a corner between corner [after] and the next one. */
    fun insertCorner(after: Int, p: GeoPoint) {
        val last = activeItem ?: return
        if (after !in last.ring.indices || last.ring.size >= DrawingGeometry.MAX_VERTICES) return
        val ring = last.ring.toMutableList().also { it.add(after + 1, p) }
        live = items.dropLast(1) + last.copy(ring = ring)
        selectedVertex = after + 1
    }

    /** Moves a corner while it is being dragged (not yet a history step — see [commitLive]). */
    fun moveCorner(index: Int, p: GeoPoint) {
        val last = activeItem ?: return
        if (index !in last.ring.indices) return
        live = items.dropLast(1) + last.copy(ring = last.ring.toMutableList().also { it[index] = p })
    }

    /** Ends a drag/insert: whatever [live] holds becomes one undoable step. */
    fun commitLive() {
        val l = live ?: return
        if (l != history[cursor]) commit(l) else live = null
    }

    fun flashRejected(p: GeoPoint) { rejectedPoint = p }

    // ---- status / opacity / name / remarks of the active polygon ------------------

    fun selectOpacity(value: Float) {
        opacity = value
        val last = activeItem ?: return
        live = items.dropLast(1) + last.copy(opacity = value)
    }

    fun commitOpacity() = commitLive()

    /** Picks the status — and with it the color — of the polygon, previewed on the map at once. */
    fun selectStatus(value: DrawingStatus) {
        val last = activeItem ?: return
        commit(items.dropLast(1) + last.copy(status = value))
    }

    fun setRemarks(text: String) {
        val last = activeItem ?: return
        live = items.dropLast(1) + last.copy(remarks = text)
    }

    fun setName(text: String) {
        val last = activeItem ?: return
        live = items.dropLast(1) + last.copy(name = text)
    }

    // ---- editing an existing drawing -------------------------------------------

    /** Opens [drawing] for moving corners / re-status / name / remarks. */
    fun startEditing(drawing: TerritoryDrawing) {
        reset()
        active = true
        editing = drawing
        val ring = DrawingGeometry.parsePolygon(drawing.geometryJson)
        if (ring == null) { exit(); return }
        history = listOf(listOf(DraftPolygon(drawing.id, ring, drawing.fillOpacity.toFloat(), drawing.status, drawing.remarks, drawing.name, closed = true, existing = drawing)))
        cursor = 0
        opacity = drawing.fillOpacity.toFloat()
        tool = DrawingTool.POINTS
    }
}
