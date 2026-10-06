package com.emfitsolutions.gopreach.ui.screens.territories

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.emfitsolutions.gopreach.data.model.MapPin
import com.emfitsolutions.gopreach.ui.components.map.BoundaryGeometry
import com.emfitsolutions.gopreach.ui.components.map.CurrentLocationLayer
import com.emfitsolutions.gopreach.domain.map.DrawingAccess
import com.emfitsolutions.gopreach.domain.map.TerritoryBoundary
import com.emfitsolutions.gopreach.ui.components.map.MapDrawingLayers
import com.emfitsolutions.gopreach.ui.components.map.MapDrawingOverlay
import com.emfitsolutions.gopreach.ui.components.map.MapDrawingState
import com.emfitsolutions.gopreach.ui.components.map.polygonCornerGestures
import com.emfitsolutions.gopreach.ui.components.map.MapLibreHost
import com.emfitsolutions.gopreach.ui.components.map.MapLoadState
import com.emfitsolutions.gopreach.ui.components.map.maptilerStyleUrl
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

enum class TerritoryBasemap(val label: String, val styleId: String) {
    STANDARD("Standard", "outdoor-v4"),
    SATELLITE("Satellite", "hybrid-v4"),
    NIGHT("Night", "basic-v2-dark"),
}

/** A record plus its distance from the user's current location (null when the
 * location is unknown). */
data class RecordWithDistance(val record: LocationRecord, val meters: Double?)

private const val SRC_AREAS = "tm-areas-src"
private const val LYR_AREA_FILL = "tm-area-fill"
private const val LYR_AREA_LINE = "tm-area-line"
private const val SRC_AREA_LABELS = "tm-area-labels-src"
private const val LYR_AREA_LABELS = "tm-area-labels"
private const val SRC_RECORDS = "tm-records-src"
private const val LYR_RECORDS = "tm-records"
private const val LYR_RECORD_LABELS = "tm-record-labels"
private const val LYR_CLUSTER = "tm-cluster"
private const val LYR_CLUSTER_COUNT = "tm-cluster-count"
private const val LYR_STACK = "tm-stack"
private const val LYR_STACK_COUNT = "tm-stack-count"
private const val SRC_SELECTED = "tm-selected-src"

/** From this zoom on, records sharing one spot fan out into separate icons around it; below it they show as one numbered stack. */
private const val SPREAD_ZOOM = 18.0
private const val LYR_SELECTED = "tm-selected"
private const val SRC_PINS = "tm-pins-src"
private const val LYR_PINS = "tm-pins"
private const val LYR_PIN_LABELS = "tm-pin-labels"
private const val IMG_NOTE_PIN = "tm-img-note-pin"
private const val SRC_FOCUS = "tm-focus-src"
private const val LYR_FOCUS = "tm-focus"
private const val IMG_FOCUS = "tm-img-focus"
private val FONT = arrayOf("Noto Sans Regular")
private val FONT_BOLD = arrayOf("Noto Sans Bold")

private fun recordImage(type: RecordType, selected: Boolean, ringHex: String? = null) =
    "tm-img-${type.name}${if (selected) "-sel" else ""}${ringHex?.let { "-" + it.removePrefix("#") } ?: ""}"

/** A teardrop pin whose tip is the bottom-centre of the bitmap (so the map's
 * BOTTOM icon anchor puts the tip on the coordinate), coloured per record type
 * and carrying that type's emoji; the selected variant is larger with a gold ring. */
private fun buildPin(type: RecordType, selected: Boolean, density: Float, ringHex: String? = null) =
    drawPin(type.colorHex, type.emoji, selected, density, ringHex)

private fun drawPin(colorHex: String, emoji: String, selected: Boolean, density: Float, ringHex: String? = null): Bitmap {
    val scale = if (selected) 1.25f else 1f
    val w = (34f * scale * density).toInt()
    val h = (44f * scale * density).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val r = w / 2f - 2f * density
    val cx = w / 2f
    val cy = r + 2f * density
    val shape = Path().apply {
        addCircle(cx, cy, r, Path.Direction.CW)
        val tail = Path().apply {
            moveTo(cx - r * 0.62f, cy + r * 0.78f)
            lineTo(cx, h - 1f * density)
            lineTo(cx + r * 0.62f, cy + r * 0.78f)
            close()
        }
        op(tail, Path.Op.UNION)
    }
    c.drawPath(shape, Paint().apply { color = Color.argb(60, 0, 0, 0); isAntiAlias = true; setShadowLayer(3f * density, 0f, 1f * density, Color.argb(90, 0, 0, 0)) })
    c.drawPath(shape, Paint().apply { color = Color.parseColor(colorHex); isAntiAlias = true })
    c.drawPath(
        shape,
        Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = (if (selected || ringHex != null) 3.5f else 2.5f) * density
            // Selected: gold. In "Show All FS Groups" the ring is the record's FS Group color; otherwise white.
            color = if (selected) Color.rgb(0xFF, 0xD6, 0x00) else (ringHex?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: Color.WHITE)
            isAntiAlias = true
        },
    )
    val emojiPaint = Paint().apply { textSize = r * 1.05f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
    c.drawText(emoji, cx, cy - (emojiPaint.ascent() + emojiPaint.descent()) / 2f, emojiPaint)
    return bmp
}

private fun buildFocusPin(density: Float): Bitmap {
    val size = (26f * density).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawCircle(size / 2f, size / 2f, size / 2f, Paint().apply { color = Color.WHITE; isAntiAlias = true })
    c.drawCircle(size / 2f, size / 2f, size / 2f - 3f * density, Paint().apply { color = Color.parseColor("#D93025"); isAntiAlias = true })
    return bmp
}

private fun pointOf(lat: Double, lng: Double) = Point.fromLngLat(lng, lat)

/**
 * The Territory Map's MapLibre layer stack: territory boundaries (subtle fill
 * + line, the selected one bolder) with name labels, distinct per-type record
 * markers, the user's pulsing current-location indicator, and an optional
 * focus pin (Share Location's "open in Territory Map"). Owns no business
 * data — everything is passed in; camera moves are driven by tokens.
 */
@Composable
fun TerritoryMapCanvas(
    areas: List<TerritoryArea>,
    boundaryJson: Map<String, String>,
    /** area id -> "#RRGGBB": each territory is drawn in its own FS Group's color code. */
    areaColors: Map<String, String>,
    /** group id -> "#RRGGBB" (marker ring color in "Show All FS Groups"). */
    groupColors: Map<String, String>,
    /** "Show All FS Groups": territories are labelled with their group and markers ringed in its color. */
    showAllGroups: Boolean,
    records: List<RecordWithDistance>,
    /** Text pins dropped by long-press ("Create a Pin"), saved online. */
    pins: List<MapPin>,
    onPinTap: (String) -> Unit,
    selectedAreaId: String?,
    selectedRecordId: String?,
    myLocation: Pair<Double, Double>?,
    myAccuracyMeters: Float? = null,
    focus: Triple<Double, Double, String>?,
    basemap: TerritoryBasemap,
    reloadToken: Int,
    /** Changes whenever the camera should re-fit to the current territories/records. */
    fitKey: String,
    fitReady: Boolean,
    recenterToken: Int,
    flyToRecord: LocationRecord?,
    onRecordTap: (String) -> Unit,
    onAreaTap: (String) -> Unit,
    /** Long-press on the map — the exact (lat, lng) pressed. */
    onLongPress: (lat: Double, lng: Double) -> Unit,
    onLoadStateChange: (MapLoadState) -> Unit,
    /** The shared drawing system (see MapDrawingOverlay): its UI state, who may draw, and the
     * territories (with boundaries) currently on the map that drawings are validated against. */
    drawingState: MapDrawingState,
    drawingAccess: DrawingAccess,
    drawingTerritories: List<TerritoryBoundary>,
    drawings: List<com.emfitsolutions.gopreach.data.model.TerritoryDrawing>,
    drawingCongregationId: String,
    /** Draw Mode's "Hide Markers": hides every record / pin marker (data and permissions untouched). */
    hideMarkers: Boolean = false,
    /** A numbered stack marker (several records at the same spot) was tapped: the ids of all its records. */
    onStackTap: (List<String>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalContext.current.resources.displayMetrics.density
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleVersion by remember { mutableIntStateOf(0) }
    var lastFitKey by remember { mutableStateOf<String?>(null) }
    var focusConsumed by remember { mutableStateOf(false) }

    val latestOnRecordTap = rememberUpdatedState(onRecordTap)
    val latestOnAreaTap = rememberUpdatedState(onAreaTap)
    val latestOnLongPress = rememberUpdatedState(onLongPress)
    val latestOnPinTap = rememberUpdatedState(onPinTap)
    val latestOnStackTap = rememberUpdatedState(onStackTap)

    MapLibreHost(
        styleUrl = maptilerStyleUrl(basemap.styleId),
        reloadToken = reloadToken,
        onLoadStateChange = onLoadStateChange,
        myLocation = myLocation,
        myAccuracyMeters = myAccuracyMeters,
        drawingLayers = true,
        recenterOnArrowTap = false,
        content = { m, styleVer ->
            MapDrawingOverlay(
                state = drawingState,
                map = m,
                styleVersion = styleVer,
                access = drawingAccess,
                territories = drawingTerritories,
                congregationId = drawingCongregationId,
                drawings = drawings,
            )
        },
        onMapClick = { m, latLng ->
            // Drawing Mode owns every tap while it is on.
            // Drawing Mode owns every tap: it places the next polygon corner (see MapDrawingOverlay).
            if (drawingState.active) return@MapLibreHost drawingState.mapTapHandler?.invoke(latLng) ?: true
            val screen = m.projection.toScreenLocation(latLng)
            val drawingId = MapDrawingLayers.hitTest(m, screen)
            // A cluster zooms in; a stack opens the list of its records; otherwise a single record.
            m.queryRenderedFeatures(screen, LYR_CLUSTER).firstOrNull()?.let { cluster ->
                // Zoom to frame exactly the records this cluster stands for (so a tap always reveals them).
                val leaves = m.style?.getSourceAs<GeoJsonSource>(SRC_RECORDS)?.getClusterLeaves(cluster, 500L, 0L)?.features().orEmpty()
                val pts = leaves.mapNotNull { (it.geometry() as? Point)?.let { p -> LatLng(p.latitude(), p.longitude()) } }
                val p = cluster.geometry() as? Point
                // Zooming in separates a cluster into its icons. Only when it truly cannot (already at the closest zoom, or its records are within a
                // couple of metres of each other) does it open as a list instead, so EVERY number on the map can still be opened.
                val tiny = pts.size < 2 || run {
                    val b = LatLngBounds.Builder().includes(pts).build()
                    Math.hypot((b.latitudeNorth - b.latitudeSouth) * 110_540.0, (b.longitudeEast - b.longitudeWest) * 111_320.0) < 2.0
                }
                if (tiny || m.cameraPosition.zoom >= 21.5) {
                    // Records on (almost) one spot: zoom in and they fan out into separate icons (see the records effect).
                    if (p != null && m.cameraPosition.zoom < SPREAD_ZOOM) {
                        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude(), p.longitude()), SPREAD_ZOOM + 0.5), 450)
                        return@MapLibreHost true
                    }
                    val ids = leaves.flatMap { f -> f.getStringProperty("ids")?.split(",").orEmpty() }.filter { it.isNotBlank() }.distinct()
                    if (ids.isNotEmpty()) { latestOnStackTap.value(ids); return@MapLibreHost true }
                }
                when {
                    pts.size >= 2 -> m.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(pts).build(), 140), 500)
                    p != null -> m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude(), p.longitude()), maxOf(m.cameraPosition.zoom + 2, 16.0).coerceAtMost(21.0)), 450)
                }
                return@MapLibreHost true
            }
            m.queryRenderedFeatures(screen, LYR_STACK).firstOrNull()?.let { f ->
                // Zoom in on the spot: the records fan out into separate icons; at the closest zoom the list is the fallback.
                val sp = f.geometry() as? Point
                if (sp != null && m.cameraPosition.zoom < SPREAD_ZOOM) {
                    m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(sp.latitude(), sp.longitude()), SPREAD_ZOOM + 0.5), 450)
                    return@MapLibreHost true
                }
                val ids = f.getStringProperty("ids")?.split(",").orEmpty().filter { it.isNotBlank() }
                if (ids.isNotEmpty()) { latestOnStackTap.value(ids); return@MapLibreHost true }
            }
            val recordId = (m.queryRenderedFeatures(screen, LYR_SELECTED) + m.queryRenderedFeatures(screen, LYR_RECORDS))
                .firstNotNullOfOrNull { if (it.hasProperty("id")) it.getStringProperty("id") else null }
            val pinId = if (recordId != null) null else m.queryRenderedFeatures(screen, LYR_PINS)
                .firstNotNullOfOrNull { if (it.hasProperty("id")) it.getStringProperty("id") else null }
            if (recordId != null) {
                latestOnRecordTap.value(recordId)
                true
            } else if (pinId != null) {
                latestOnPinTap.value(pinId)
                true
            } else if (myLocation != null && CurrentLocationLayer.isHit(m, screen, myLocation, 24f * density)) {
                // The My Location arrow (drawn below the records, so records and clusters win a shared tap first).
                m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(myLocation.first, myLocation.second), maxOf(m.cameraPosition.zoom, 16.0)), 450)
                true
            } else if (drawingId != null) {
                drawingState.selectedDrawingId = drawingId
                true
            } else {
                val areaId = m.queryRenderedFeatures(screen, LYR_AREA_FILL)
                    .firstNotNullOfOrNull { if (it.hasProperty("id")) it.getStringProperty("id") else null }
                if (areaId != null) {
                    latestOnAreaTap.value(areaId)
                    true
                } else {
                    false
                }
            }
        },
        onMapLongClick = { _, latLng ->
            latestOnLongPress.value(latLng.latitude, latLng.longitude)
            true
        },
        onStyleReady = { m, style ->
            RecordType.entries.forEach { t ->
                style.addImage(recordImage(t, false), buildPin(t, false, density))
                style.addImage(recordImage(t, true), buildPin(t, true, density))
            }
            style.addImage(IMG_FOCUS, buildFocusPin(density))

            style.addSource(GeoJsonSource(SRC_AREAS, FeatureCollection.fromFeatures(emptyList<Feature>())))
            style.addLayer(
                FillLayer(LYR_AREA_FILL, SRC_AREAS).withProperties(
                    PropertyFactory.fillColor(Expression.get("color")),
                    PropertyFactory.fillOpacity(
                        Expression.switchCase(
                            Expression.eq(Expression.get("sel"), Expression.literal("1")),
                            Expression.literal(0.22f),
                            Expression.literal(0.08f),
                        ),
                    ),
                ),
            )
            style.addLayer(
                LineLayer(LYR_AREA_LINE, SRC_AREAS).withProperties(
                    PropertyFactory.lineColor(Expression.get("color")),
                    PropertyFactory.lineWidth(
                        Expression.switchCase(
                            Expression.eq(Expression.get("sel"), Expression.literal("1")),
                            Expression.literal(5f),
                            Expression.literal(2.5f),
                        ),
                    ),
                    PropertyFactory.lineOpacity(0.95f),
                ),
            )
            style.addSource(GeoJsonSource(SRC_AREA_LABELS, FeatureCollection.fromFeatures(emptyList<Feature>())))
            style.addLayer(
                SymbolLayer(LYR_AREA_LABELS, SRC_AREA_LABELS).withProperties(
                    PropertyFactory.textField(Expression.get("name")),
                    PropertyFactory.textFont(FONT_BOLD),
                    PropertyFactory.textSize(13f),
                    PropertyFactory.textColor("#202124"),
                    PropertyFactory.textHaloColor("#FFFFFF"),
                    PropertyFactory.textHaloWidth(1.8f),
                    PropertyFactory.textAllowOverlap(false),
                ).also { it.minZoom = 10f },
            )

            // Records are CLUSTERED by MapLibre itself (one GeoJSON source, symbol/circle layers — no per-marker views):
            // zoomed out they merge into numbered clusters, zooming in separates them, and records at (almost) the same
            // spot arrive here already merged into one numbered "stack" marker (see the effect below).
            style.addSource(
                GeoJsonSource(
                    SRC_RECORDS,
                    FeatureCollection.fromFeatures(emptyList<Feature>()),
                    GeoJsonOptions()
                        .withCluster(true)
                        // Tiles (and so cluster splits) must exist for the deep zooms, or a cluster stays one blob past zoom 18.
                        .withMaxZoom(22)
                        .withClusterRadius(44)
                        .withClusterMaxZoom(21)
                        // "total" = how many records a cluster stands for (a stack counts as all of its records).
                        .withClusterProperty("total", Expression.sum(Expression.accumulated(), Expression.get("total")), Expression.get("count")),
                ),
            )
            val single = Expression.all(Expression.not(Expression.has("point_count")), Expression.eq(Expression.get("count"), Expression.literal(1)))
            val stack = Expression.all(Expression.not(Expression.has("point_count")), Expression.gt(Expression.get("count"), Expression.literal(1)))
            style.addLayer(
                SymbolLayer(LYR_RECORDS, SRC_RECORDS).withFilter(single).withProperties(
                    PropertyFactory.iconImage(Expression.get("img")),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                ),
            )
            style.addLayer(
                SymbolLayer(LYR_RECORD_LABELS, SRC_RECORDS).withFilter(single).withProperties(
                    PropertyFactory.textField(Expression.get("name")),
                    PropertyFactory.textFont(FONT),
                    PropertyFactory.textSize(11f),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                    PropertyFactory.textOffset(arrayOf(0f, 0.3f)),
                    PropertyFactory.textColor("#202124"),
                    PropertyFactory.textHaloColor("#FFFFFF"),
                    PropertyFactory.textHaloWidth(1.6f),
                    // Labels yield to each other (collision detection) instead of piling up.
                    PropertyFactory.textOptional(true),
                ).also { it.minZoom = 14.5f },
            )
            // Cluster of several markers: a numbered circle; tapping it zooms into that area.
            style.addLayer(
                CircleLayer(LYR_CLUSTER, SRC_RECORDS).withFilter(Expression.has("point_count")).withProperties(
                    PropertyFactory.circleColor("#5E35B1"),
                    PropertyFactory.circleRadius(Expression.step(Expression.get("total"), Expression.literal(16f), Expression.stop(10, 20f), Expression.stop(50, 25f))),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeWidth(2.5f),
                ),
            )
            style.addLayer(
                SymbolLayer(LYR_CLUSTER_COUNT, SRC_RECORDS).withFilter(Expression.has("point_count")).withProperties(
                    PropertyFactory.textField(Expression.toString(Expression.get("total"))),
                    PropertyFactory.textFont(FONT_BOLD),
                    PropertyFactory.textSize(13f),
                    PropertyFactory.textColor("#FFFFFF"),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                ),
            )
            // Several records at the same / nearly the same coordinates: one numbered marker; tapping opens the list.
            style.addLayer(
                CircleLayer(LYR_STACK, SRC_RECORDS).withFilter(stack).withProperties(
                    PropertyFactory.circleColor("#37474F"),
                    PropertyFactory.circleRadius(15f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeWidth(3f),
                ),
            )
            style.addLayer(
                SymbolLayer(LYR_STACK_COUNT, SRC_RECORDS).withFilter(stack).withProperties(
                    PropertyFactory.textField(Expression.toString(Expression.get("count"))),
                    PropertyFactory.textFont(FONT_BOLD),
                    PropertyFactory.textSize(13f),
                    PropertyFactory.textColor("#FFFFFF"),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                ),
            )
            // The selected record always stays visible and on top, whatever cluster it would otherwise be inside.
            style.addSource(GeoJsonSource(SRC_SELECTED, FeatureCollection.fromFeatures(emptyList<Feature>())))
            style.addLayer(
                SymbolLayer(LYR_SELECTED, SRC_SELECTED).withProperties(
                    PropertyFactory.iconImage(Expression.get("img")),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                ),
            )

            style.addImage(IMG_NOTE_PIN, drawPin("#D93025", "📌", false, density))
            style.addSource(GeoJsonSource(SRC_PINS, FeatureCollection.fromFeatures(emptyList<Feature>())))
            style.addLayer(
                SymbolLayer(LYR_PINS, SRC_PINS).withProperties(
                    PropertyFactory.iconImage(IMG_NOTE_PIN),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                ),
            )
            // The text mark is always visible next to the pin.
            style.addLayer(
                SymbolLayer(LYR_PIN_LABELS, SRC_PINS).withProperties(
                    PropertyFactory.textField(Expression.get("text")),
                    PropertyFactory.textFont(FONT_BOLD),
                    PropertyFactory.textSize(12.5f),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                    PropertyFactory.textOffset(arrayOf(0f, 0.25f)),
                    PropertyFactory.textMaxWidth(10f),
                    PropertyFactory.textColor("#B3261E"),
                    PropertyFactory.textHaloColor("#FFFFFF"),
                    PropertyFactory.textHaloWidth(2f),
                    PropertyFactory.textAllowOverlap(true),
                ),
            )
            style.addSource(GeoJsonSource(SRC_FOCUS, FeatureCollection.fromFeatures(emptyList<Feature>())))
            style.addLayer(
                SymbolLayer(LYR_FOCUS, SRC_FOCUS).withProperties(
                    PropertyFactory.iconImage(IMG_FOCUS),
                    PropertyFactory.iconAllowOverlap(true),
                ),
            )
            map = m
            styleVersion++
        },
        // Polygon Lasso corner gestures ride on the map itself, so the map still pans / zooms freely away from a corner.
        modifier = modifier.polygonCornerGestures(drawingState, map, density, drawingAccess.restrictedRings(drawingTerritories)),
    )

    // Territory boundaries + labels.
    LaunchedEffect(map, styleVersion, areas, boundaryJson, selectedAreaId, areaColors, showAllGroups) {
        val m = map ?: return@LaunchedEffect
        val style = m.style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        val features = mutableListOf<Feature>()
        val labels = mutableListOf<Feature>()
        areas.forEach { area ->
            val json = boundaryJson[area.id] ?: return@forEach
            val rings = BoundaryGeometry.outerRings(json).filter { it.size >= 3 }
            if (rings.isEmpty()) return@forEach
            val color = areaColors[area.id] ?: "#7E57C2"
            val sel = if (area.id == selectedAreaId) "1" else "0"
            rings.forEach { ring ->
                val closed = if (ring.first() == ring.last()) ring else ring + ring.first()
                features += Feature.fromGeometry(Polygon.fromLngLats(listOf(closed.map { (lat, lng) -> Point.fromLngLat(lng, lat) }))).also {
                    it.addStringProperty("id", area.id)
                    it.addStringProperty("color", color)
                    it.addStringProperty("sel", sel)
                }
            }
            val all = rings.flatten()
            val centerLat = (all.minOf { it.first } + all.maxOf { it.first }) / 2
            val centerLng = (all.minOf { it.second } + all.maxOf { it.second }) / 2
            labels += Feature.fromGeometry(pointOf(centerLat, centerLng)).also { it.addStringProperty("name", if (showAllGroups) area.barangay + "\n" + area.groupName else area.barangay) }
        }
        style.getSourceAs<GeoJsonSource>(SRC_AREAS)?.setGeoJson(FeatureCollection.fromFeatures(features))
        style.getSourceAs<GeoJsonSource>(SRC_AREA_LABELS)?.setGeoJson(FeatureCollection.fromFeatures(labels))
    }

    // Half-zoom steps: the stacks re-fan (their ring is sized in screen terms) as the user zooms.
    var zoomStep by remember { mutableIntStateOf(0) }
    DisposableEffect(map) {
        val m = map ?: return@DisposableEffect onDispose { }
        val listener = MapLibreMap.OnCameraIdleListener { zoomStep = Math.floor(m.cameraPosition.zoom * 2).toInt() }
        m.addOnCameraIdleListener(listener)
        onDispose { m.removeOnCameraIdleListener(listener) }
    }
    // Record markers. Records at (almost) the same coordinates are merged into ONE numbered stack marker so icons never
    // sit on top of each other; everything else is clustered / separated by MapLibre by zoom level. The selected record
    // is drawn separately (always visible, on top).
    LaunchedEffect(map, styleVersion, records, selectedRecordId, groupColors, showAllGroups, zoomStep) {
        val m = map ?: return@LaunchedEffect
        val style = m.style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        fun imageFor(r: LocationRecord, selected: Boolean): String {
            val ring = if (showAllGroups) r.groupId?.let { groupColors[it] } else null
            val imageId = recordImage(r.type, selected, ring)
            if (style.getImage(imageId) == null) style.addImage(imageId, buildPin(r.type, selected, density, ring))
            return imageId
        }
        // Only records at (virtually) the SAME coordinates (within ~2 m: one point, GPS noise) become ONE stack marker — anything else separates as you zoom in. Distance-based,
        // not a grid, so two records a metre apart can never straddle a cell edge and stay as a separate pair.
        val groups = ArrayList<MutableList<RecordWithDistance>>()
        records.forEach { rd ->
            val r = rd.record
            val home = groups.firstOrNull { g ->
                val c0 = g.first().record
                Math.hypot((c0.lat - r.lat) * 110_540.0, (c0.lng - r.lng) * 111_320.0 * Math.cos(Math.toRadians(c0.lat))) <= 2.0
            }
            if (home != null) home += rd else groups += mutableListOf(rd)
        }
        val spread = m.cameraPosition.zoom >= SPREAD_ZOOM
        val features = groups.flatMap { g ->
            if (g.size > 1 && spread) {
                // Zoomed in: fan the records of one spot out on a small ring (in screen terms ~30dp), each its own icon.
                val centerLat = g.map { it.record.lat }.average()
                val centerLng = g.map { it.record.lng }.average()
                val radiusMeters = 30f * density * m.projection.getMetersPerPixelAtLatitude(centerLat)
                g.mapIndexed { i, rd ->
                    val a = 2 * Math.PI * i / g.size - Math.PI / 2
                    val lat = centerLat + radiusMeters * Math.sin(a) / 110_540.0
                    val lng = centerLng + radiusMeters * Math.cos(a) / (111_320.0 * Math.cos(Math.toRadians(centerLat)))
                    Feature.fromGeometry(pointOf(lat, lng)).also {
                        it.addNumberProperty("count", 1)
                        it.addNumberProperty("total", 1)
                        it.addStringProperty("ids", rd.record.id)
                        it.addStringProperty("id", rd.record.id)
                        it.addStringProperty("name", rd.record.name)
                        it.addStringProperty("img", imageFor(rd.record, selected = false))
                    }
                }
            } else {
                val first = g.first().record
                listOf(
                    Feature.fromGeometry(pointOf(g.map { it.record.lat }.average(), g.map { it.record.lng }.average())).also {
                        it.addNumberProperty("count", g.size)
                        it.addNumberProperty("total", g.size)
                        it.addStringProperty("ids", g.joinToString(",") { rd -> rd.record.id })
                        it.addStringProperty("id", first.id)
                        it.addStringProperty("name", first.name)
                        it.addStringProperty("img", imageFor(first, selected = false))
                    },
                )
            }
        }
        style.getSourceAs<GeoJsonSource>(SRC_RECORDS)?.setGeoJson(FeatureCollection.fromFeatures(features))
        val sel = records.firstOrNull { it.record.id == selectedRecordId }?.record
        style.getSourceAs<GeoJsonSource>(SRC_SELECTED)?.setGeoJson(
            if (sel == null) FeatureCollection.fromFeatures(emptyList<Feature>())
            else FeatureCollection.fromFeature(Feature.fromGeometry(pointOf(sel.lat, sel.lng)).also {
                it.addStringProperty("id", sel.id)
                it.addStringProperty("img", imageFor(sel, selected = true))
            }),
        )
    }

    // Draw Mode's "Hide Markers": every record / pin marker layer off (boundaries, drawings, My Location stay). Hidden
    // layers render nothing and answer no taps; the data is untouched, so Show Markers restores them as they were.
    LaunchedEffect(map, styleVersion, hideMarkers) {
        val style = map?.style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        val visibility = PropertyFactory.visibility(if (hideMarkers) Property.NONE else Property.VISIBLE)
        listOf(LYR_RECORDS, LYR_RECORD_LABELS, LYR_CLUSTER, LYR_CLUSTER_COUNT, LYR_STACK, LYR_STACK_COUNT, LYR_SELECTED, LYR_PINS, LYR_PIN_LABELS, LYR_FOCUS)
            .forEach { style.getLayer(it)?.setProperties(visibility) }
    }


    // Text pins.
    LaunchedEffect(map, styleVersion, pins) {
        val m = map ?: return@LaunchedEffect
        val style = m.style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        val features = pins.map { p ->
            Feature.fromGeometry(pointOf(p.lat, p.lng)).also {
                it.addStringProperty("id", p.id)
                it.addStringProperty("text", p.text)
            }
        }
        style.getSourceAs<GeoJsonSource>(SRC_PINS)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    // Focus pin (Share Location's "open in Territory Map").
    LaunchedEffect(map, styleVersion, focus) {
        val m = map ?: return@LaunchedEffect
        val style = m.style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        val src = style.getSourceAs<GeoJsonSource>(SRC_FOCUS) ?: return@LaunchedEffect
        if (focus == null) {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        } else {
            src.setGeoJson(Feature.fromGeometry(pointOf(focus.first, focus.second)))
            if (!focusConsumed) {
                focusConsumed = true
                lastFitKey = fitKey // the focus takes priority over the first auto-fit
                m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(focus.first, focus.second), 16.0))
            }
        }
    }

    // Fit to the selected group's territories/records once per selection/filter.
    LaunchedEffect(map, styleVersion, fitKey, fitReady) {
        val m = map ?: return@LaunchedEffect
        if (!fitReady || lastFitKey == fitKey || m.style?.isFullyLoaded != true) return@LaunchedEffect
        val points = mutableListOf<LatLng>()
        areas.forEach { area ->
            boundaryJson[area.id]?.let { json ->
                BoundaryGeometry.outerRings(json).forEach { ring -> ring.forEach { points += LatLng(it.first, it.second) } }
            }
        }
        records.forEach { points += LatLng(it.record.lat, it.record.lng) }
        lastFitKey = fitKey
        when {
            points.size >= 2 -> m.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), 120), 500)
            points.size == 1 -> m.animateCamera(CameraUpdateFactory.newLatLngZoom(points[0], 15.0), 500)
            myLocation != null -> m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(myLocation.first, myLocation.second), 14.0), 500)
        }
    }

    // "My location" button.
    LaunchedEffect(recenterToken) {
        val m = map ?: return@LaunchedEffect
        val me = myLocation ?: return@LaunchedEffect
        // Camera only: keep the current zoom (within a sensible street-level range) so the map never jumps in too close.
        if (recenterToken > 0) m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(me.first, me.second), m.cameraPosition.zoom.coerceIn(14.0, 17.0)), 600)
    }

    // Selecting a record from the list/panel flies the map to it.
    LaunchedEffect(flyToRecord?.id) {
        val m = map ?: return@LaunchedEffect
        val r = flyToRecord ?: return@LaunchedEffect
        val zoom = maxOf(m.cameraPosition.zoom, 15.0)
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(r.lat, r.lng), zoom), 500)
    }
}
