package com.emfitsolutions.gopreach.ui.components.map

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.emfitsolutions.gopreach.BuildConfig
import com.emfitsolutions.gopreach.data.export.MapImageExporter
import com.emfitsolutions.gopreach.data.repository.AreaFeature
import com.emfitsolutions.gopreach.data.repository.Landmark
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/** One named boundary to draw — a list since a future caller could overlay
 * more than one, though [BarangayBoundaryDialog][com.emfitsolutions.gopreach
 * .ui.screens.territoryassignments.BarangayBoundaryDialog] only ever passes a
 * single barangay. [colorHex] ("#RRGGBB") overrides [OsmBoundaryMap]'s own
 * `boundaryColorHex` for just this one boundary — e.g. "show every Group's
 * territory at once" draws each barangay in its own assigned Group's color
 * on one shared map, rather than every boundary sharing one color. */
data class NamedBoundary(val name: String, val geometryJson: String, val colorHex: String? = null)

private enum class MapStyle(val label: String, val maptilerId: String) {
    SATELLITE("Satellite", "hybrid-v4"),
    STANDARD("Standard", "outdoor-v4"),
    NIGHT("Night", "basic-v2-dark"),
}

/** MapTiler vector style per [MapStyle] (needs [BuildConfig.MAPTILER_API_KEY]);
 * without a key, Standard falls back to OpenFreeMap's keyless "Liberty" style
 * and the other two to it as well, so the map is never blank. */
private fun styleUrl(style: MapStyle): String =
    if (BuildConfig.MAPTILER_API_KEY.isBlank()) "https://tiles.openfreemap.org/styles/liberty"
    else "https://api.maptiler.com/maps/${style.maptilerId}/style.json?key=${BuildConfig.MAPTILER_API_KEY}"

private const val SRC_BOUNDARY = "gp-boundary-src"
private const val LYR_BOUNDARY_FILL = "gp-boundary-fill"
private const val LYR_BOUNDARY_LINE = "gp-boundary-line"
private const val SRC_LANDMARKS = "gp-landmarks-src"
private const val LYR_LANDMARKS = "gp-landmarks"
private const val SRC_AREAS = "gp-areas-src"
private const val LYR_AREAS = "gp-areas"
private const val SRC_ME_LINE = "gp-me-line-src"
private const val LYR_ME_LINE = "gp-me-line"
private const val SRC_PT = "gp-pt-src"
private const val LYR_PT = "gp-pt"
private const val SRC_PT_LINE = "gp-pt-line-src"
private const val LYR_PT_LINE = "gp-pt-line"
private const val LYR_3D = "gp-3d-buildings"
private const val IMG_PT = "gp-img-pt"

private val OUR_LAYERS = listOf(LYR_3D, LYR_PT, LYR_PT_LINE, LYR_ME_LINE, LYR_LANDMARKS, LYR_AREAS, LYR_BOUNDARY_LINE, LYR_BOUNDARY_FILL)
private val OUR_SOURCES = listOf(SRC_PT, SRC_PT_LINE, SRC_ME_LINE, SRC_LANDMARKS, SRC_AREAS, SRC_BOUNDARY)

private fun emptyCollection() = FeatureCollection.fromFeatures(emptyList<Feature>())

private fun pointFeature(lat: Double, lng: Double, vararg props: Pair<String, String>): Feature =
    Feature.fromGeometry(Point.fromLngLat(lng, lat)).also { f -> props.forEach { (k, v) -> f.addStringProperty(k, v) } }

private fun toLatLng(p: Pair<Double, Double>) = LatLng(p.first, p.second)

/**
 * MapLibre (native, vector) boundary map for Territory Assignment — replaces
 * the earlier osmdroid raster map. Same Satellite/Standard/Night choice via
 * MapTiler's vector styles, plus a 3D toggle (tilted camera + extruded
 * buildings where the style carries building data). Only the boundary
 * outline, landmark pins, area labels, and the user's own markers are drawn
 * by this component; roads and buildings are the style's own layers.
 *
 * The public signature is unchanged from the osmdroid version so the three
 * call sites (barangay, group, all territories) needed no edits.
 */
@Composable
fun OsmBoundaryMap(
    boundaries: List<NamedBoundary>,
    landmarks: List<Landmark> = emptyList(),
    areas: List<AreaFeature> = emptyList(),
    /** "#RRGGBB" — the assigned Field Service Group's own
     * [com.emfitsolutions.gopreach.ui.components.GroupColorPalette] color, so
     * the boundary reads as "whose territory is this" the same way Group
     * color already does everywhere else in the app. Null (no Group
     * assigned) falls back to a plain neutral blue. */
    boundaryColorHex: String? = null,
    /** Fires with the tapped [NamedBoundary.name] when a boundary polygon is
     * clicked — e.g. [com.emfitsolutions.gopreach.ui.screens
     * .territoryassignments.GroupTerritoryMapDialog] uses this to drill from
     * "all this Group's territory" down into one barangay's own boundary
     * dialog. `null` (the default) leaves boundaries non-interactive. */
    onBoundaryClick: ((name: String) -> Unit)? = null,
    /** Names the file/share-sheet entry [MapImageExporter] produces when the
     * print/export action is used — e.g. the barangay name, the Group name,
     * or "All Territories". */
    exportTitle: String = "territory_map",
    myLocation: Pair<Double, Double>? = null,
    pickModeEnabled: Boolean = false,
    onDistanceToCenterComputed: (meters: Double) -> Unit = {},
    onPointDistanceComputed: (meters: Double) -> Unit = {},
    onPointNeedsLocation: () -> Unit = {},
    onOpenDirections: (originLat: Double, originLng: Double, destLat: Double, destLng: Double) -> Unit = { _, _, _, _ -> },
    /** Full-screen mode is owned by the caller (it hides its own app bar/chrome);
     * pass [onFullScreenChange] to show the toggle button on the map. */
    fullScreen: Boolean = false,
    onFullScreenChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    remember { MapLibre.getInstance(context) }

    var selectedStyle by remember { mutableStateOf(MapStyle.STANDARD) }
    var is3d by remember { mutableStateOf(false) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    // Bumped every time a style finishes loading — switching style wipes every
    // source/layer/image, so everything below re-adds itself off this.
    var styleVersion by remember { mutableIntStateOf(0) }
    var selectedPoint by remember { mutableStateOf<LatLng?>(null) }
    var boundaryCenter by remember { mutableStateOf<LatLng?>(null) }
    var hasFitBoundary by remember { mutableStateOf(false) }
    var hasFitMyLocation by remember { mutableStateOf(false) }
    val imageIds = remember { mutableListOf<String>() }

    // Shared compass + orientation behavior (same as every MapLibreHost map).
    val heading by rememberDeviceHeading(myLocation)
    val latestHeading = rememberUpdatedState(heading)
    var orientationMode by remember { mutableStateOf(OrientationMode.NORTH_UP) }
    androidx.compose.runtime.LaunchedEffect(heading == null) { if (heading == null) orientationMode = OrientationMode.NORTH_UP }

    val latestPickMode = rememberUpdatedState(pickModeEnabled)
    val latestMyLocation = rememberUpdatedState(myLocation)
    val latestSelectedPoint = rememberUpdatedState(selectedPoint)
    val latestOnPointNeedsLocation = rememberUpdatedState(onPointNeedsLocation)
    val latestOnBoundaryClick = rememberUpdatedState(onBoundaryClick)
    val latestOnOpenDirections = rememberUpdatedState(onOpenDirections)
    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            getMapAsync { m ->
                m.uiSettings.isLogoEnabled = false
                m.uiSettings.isCompassEnabled = false
                m.setMinZoomPreference(3.0)
                m.setMaxZoomPreference(20.0)
                m.cameraPosition = CameraPosition.Builder().target(LatLng(12.8797, 121.7740)).zoom(5.0).build()

                m.addOnMapClickListener { latLng ->
                    val screen = m.projection.toScreenLocation(latLng)
                    // A tap on the picked point opens directions to it.
                    val me = latestMyLocation.value
                    val picked = latestSelectedPoint.value
                    if (me != null && CurrentLocationLayer.isHit(m, screen, me, 24f * context.resources.displayMetrics.density)) {
                        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(me.first, me.second), maxOf(m.cameraPosition.zoom, 16.0)), 400)
                        return@addOnMapClickListener true
                    }
                    if (me != null && picked != null && m.queryRenderedFeatures(screen, LYR_PT).isNotEmpty()) {
                        latestOnOpenDirections.value(me.first, me.second, picked.latitude, picked.longitude)
                        return@addOnMapClickListener true
                    }
                    if (latestOnBoundaryClick.value != null) {
                        val name = m.queryRenderedFeatures(screen, LYR_BOUNDARY_FILL)
                            .firstNotNullOfOrNull { if (it.hasProperty("name")) it.getStringProperty("name") else null }
                        if (name != null) {
                            latestOnBoundaryClick.value?.invoke(name)
                            return@addOnMapClickListener true
                        }
                    }
                    if (!latestPickMode.value) return@addOnMapClickListener false
                    if (latestMyLocation.value == null) {
                        latestOnPointNeedsLocation.value()
                        return@addOnMapClickListener true
                    }
                    selectedPoint = latLng
                    true
                }

                // "Long press open Google Maps exactly on the pressed location"
                // — a plain search deep link so it works through whatever the
                // device resolves it to.
                m.addOnMapLongClickListener { latLng ->
                    val uri = android.net.Uri.parse(
                        "https://www.google.com/maps/search/?api=1&query=${latLng.latitude},${latLng.longitude}",
                    )
                    runCatching {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                    }
                    true
                }
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

    // (Re)load the style; everything else re-adds itself when it finishes.
    LaunchedEffect(map, selectedStyle) {
        val m = map ?: return@LaunchedEffect
        m.setStyle(Style.Builder().fromUri(styleUrl(selectedStyle))) { styleVersion++ }
    }

    // Boundary outline, area labels, and landmark pins.
    LaunchedEffect(map, styleVersion, boundaries, landmarks, areas, boundaryColorHex) {
        val m = map ?: return@LaunchedEffect
        val style = m.style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect

        CurrentLocationLayer.remove(style)
        OUR_LAYERS.forEach { style.removeLayer(it) }
        OUR_SOURCES.forEach { style.removeSource(it) }
        imageIds.forEach { style.removeImage(it) }
        imageIds.clear()

        val allRings = boundaries.flatMap { BoundaryGeometry.outerRings(it.geometryJson) }
        val allPoints = mutableListOf<LatLng>()
        val defaultColor = boundaryColorHex?.takeIf { runCatching { Color.parseColor(it) }.isSuccess } ?: "#4285F4"

        val boundaryFeatures = mutableListOf<Feature>()
        boundaries.forEach { boundary ->
            val color = boundary.colorHex?.takeIf { runCatching { Color.parseColor(it) }.isSuccess } ?: defaultColor
            BoundaryGeometry.outerRings(boundary.geometryJson).forEach { ring ->
                if (ring.size >= 3) {
                    allPoints.addAll(ring.map(::toLatLng))
                    val closed = if (ring.first() == ring.last()) ring else ring + ring.first()
                    val polygon = Polygon.fromLngLats(listOf(closed.map { (lat, lng) -> Point.fromLngLat(lng, lat) }))
                    boundaryFeatures += Feature.fromGeometry(polygon).also {
                        it.addStringProperty("name", boundary.name)
                        it.addStringProperty("color", color)
                    }
                }
            }
        }
        boundaryCenter = if (allPoints.isNotEmpty()) {
            val b = LatLngBounds.Builder().includes(allPoints).build()
            LatLng((b.latitudeNorth + b.latitudeSouth) / 2, (b.longitudeEast + b.longitudeWest) / 2)
        } else {
            null
        }
        style.addSource(GeoJsonSource(SRC_BOUNDARY, FeatureCollection.fromFeatures(boundaryFeatures)))
        // The near-transparent fill exists so taps inside the outline can be hit-tested.
        style.addLayer(
            FillLayer(LYR_BOUNDARY_FILL, SRC_BOUNDARY).withProperties(
                PropertyFactory.fillColor(Expression.get("color")),
                PropertyFactory.fillOpacity(0.05f),
            ),
        )
        style.addLayer(
            LineLayer(LYR_BOUNDARY_LINE, SRC_BOUNDARY).withProperties(
                PropertyFactory.lineColor(Expression.get("color")),
                PropertyFactory.lineWidth(3f),
                PropertyFactory.lineOpacity(0.95f),
            ),
        )

        // What's actually on the ground (rice fields, orchards, forest, ...).
        val areaFeatures = areas.mapIndexed { i, area ->
            val inside = BoundaryGeometry.containsPoint(allRings, area.lat, area.lng)
            val id = "gp-area-$i"
            style.addImage(id, buildTextLabelBitmap(area.name, dimmed = !inside, dark = false, italic = true))
            imageIds += id
            pointFeature(area.lat, area.lng, "icon" to id)
        }
        style.addSource(GeoJsonSource(SRC_AREAS, FeatureCollection.fromFeatures(areaFeatures)))
        style.addLayer(
            SymbolLayer(LYR_AREAS, SRC_AREAS).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )

        // Real named landmarks. Every pin bitmap has the same height, so one
        // offset puts each pin's tip (not its name chip) on the coordinate.
        var tipOffsetY = 0f
        val landmarkFeatures = landmarks.mapIndexed { i, landmark ->
            val inside = BoundaryGeometry.containsPoint(allRings, landmark.lat, landmark.lng)
            val built = buildLandmarkPinBitmap(landmark, dimmed = !inside)
            tipOffsetY = built.bitmap.height / 2f - built.tipAnchor.y * built.bitmap.height
            val id = "gp-landmark-$i"
            style.addImage(id, built.bitmap)
            imageIds += id
            pointFeature(landmark.lat, landmark.lng, "icon" to id)
        }
        style.addSource(GeoJsonSource(SRC_LANDMARKS, FeatureCollection.fromFeatures(landmarkFeatures)))
        style.addLayer(
            SymbolLayer(LYR_LANDMARKS, SRC_LANDMARKS).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
                PropertyFactory.iconOffset(arrayOf(0f, tipOffsetY)),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )

        // The picked point (data filled by the effects below). The user's own location is the shared
        // CurrentLocationLayer, installed last (below) so it stays above everything here.
        style.addImage(IMG_PT, buildPickedPointBitmap())
        imageIds += IMG_PT
        style.addSource(GeoJsonSource(SRC_ME_LINE, emptyCollection()))
        style.addLayer(
            LineLayer(LYR_ME_LINE, SRC_ME_LINE).withProperties(
                PropertyFactory.lineColor("#1A73E8"),
                PropertyFactory.lineWidth(2.5f),
                PropertyFactory.lineDasharray(arrayOf(3f, 3f)),
            ),
        )
        style.addSource(GeoJsonSource(SRC_PT_LINE, emptyCollection()))
        style.addLayer(
            LineLayer(LYR_PT_LINE, SRC_PT_LINE).withProperties(
                PropertyFactory.lineColor("#E8710A"),
                PropertyFactory.lineWidth(2.5f),
                PropertyFactory.lineDasharray(arrayOf(2f, 4f)),
            ),
        )
        style.addSource(GeoJsonSource(SRC_PT, emptyCollection()))
        style.addLayer(
            SymbolLayer(LYR_PT, SRC_PT).withProperties(
                PropertyFactory.iconImage(IMG_PT),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )

        // Shared "You Are Here" radar indicator — always the topmost layer.
        CurrentLocationLayer.install(style, context.resources.displayMetrics.density)
        CurrentLocationLayer.setLocation(style, latestMyLocation.value)

        if (!hasFitBoundary && allPoints.size >= 2) {
            hasFitBoundary = true
            m.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(allPoints).build(), 80))
        }
    }

    // 3D: tilted camera + extruded buildings from the style's own vector
    // building data (where it has any — the satellite style usually doesn't).
    LaunchedEffect(map, styleVersion, is3d) {
        val m = map ?: return@LaunchedEffect
        val style = m.style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        style.removeLayer(LYR_3D)
        if (is3d) {
            val source = style.sources.filterIsInstance<VectorSource>().firstOrNull()
            if (source != null) {
                val layer = FillExtrusionLayer(LYR_3D, source.id).apply {
                    sourceLayer = "building"
                    minZoom = 14f
                    setProperties(
                        PropertyFactory.fillExtrusionColor("#D9D9D9"),
                        PropertyFactory.fillExtrusionOpacity(0.85f),
                        PropertyFactory.fillExtrusionHeight(
                            Expression.coalesce(Expression.get("render_height"), Expression.literal(8)),
                        ),
                        PropertyFactory.fillExtrusionBase(
                            Expression.coalesce(Expression.get("render_min_height"), Expression.literal(0)),
                        ),
                    )
                }
                if (style.getLayer(LYR_BOUNDARY_FILL) != null) style.addLayerBelow(layer, LYR_BOUNDARY_FILL) else style.addLayer(layer)
            }
        }
        m.animateCamera(CameraUpdateFactory.tiltTo(if (is3d) 60.0 else 0.0), 600)
    }

    // "Add my location, then compare the distance to the selected barangay" —
    // called on every fix of a continuous location subscription by the caller.
    LaunchedEffect(map, styleVersion, myLocation, boundaryCenter) {
        val m = map ?: return@LaunchedEffect
        val style = m.style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        val meLineSource = style.getSourceAs<GeoJsonSource>(SRC_ME_LINE)
        val fix = myLocation
        if (fix == null) {
            meLineSource?.setGeoJson(emptyCollection())
            selectedPoint = null
            hasFitMyLocation = false
            return@LaunchedEffect
        }
        val point = LatLng(fix.first, fix.second)
        val center = boundaryCenter
        if (center != null) {
            meLineSource?.setGeoJson(
                Feature.fromGeometry(
                    LineString.fromLngLats(listOf(Point.fromLngLat(point.longitude, point.latitude), Point.fromLngLat(center.longitude, center.latitude))),
                ),
            )
            onDistanceToCenterComputed(point.distanceTo(center))
        }
        if (!hasFitMyLocation && center != null) {
            hasFitMyLocation = true
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().include(point).include(center).build(), 100), 600)
        }
    }

    // "Let the user select a point then calculate the distance from location"
    // — [selectedPoint] is set by the tap handler while [pickModeEnabled] is on.
    LaunchedEffect(map, styleVersion, selectedPoint, myLocation) {
        val m = map ?: return@LaunchedEffect
        val style = m.style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        val ptSource = style.getSourceAs<GeoJsonSource>(SRC_PT)
        val ptLineSource = style.getSourceAs<GeoJsonSource>(SRC_PT_LINE)
        val point = selectedPoint
        val fix = myLocation
        if (point == null || fix == null) {
            ptSource?.setGeoJson(emptyCollection())
            ptLineSource?.setGeoJson(emptyCollection())
            return@LaunchedEffect
        }
        val origin = LatLng(fix.first, fix.second)
        ptSource?.setGeoJson(pointFeature(point.latitude, point.longitude))
        ptLineSource?.setGeoJson(
            Feature.fromGeometry(
                LineString.fromLngLats(listOf(Point.fromLngLat(origin.longitude, origin.latitude), Point.fromLngLat(point.longitude, point.latitude))),
            ),
        )
        onPointDistanceComputed(origin.distanceTo(point))
    }


    CurrentLocationUpdater(map, styleVersion, myLocation, heading)
    MapOrientationEffects(map, heading, orientationMode)
    val mapBearing by rememberMapBearing(map)

    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize())
        MapOrientationIndicator(
            mapBearing = mapBearing,
            headingDeg = heading,
            mode = orientationMode,
            onClick = {
                if (heading != null && orientationMode == OrientationMode.NORTH_UP) {
                    orientationMode = OrientationMode.FOLLOW_HEADING
                } else {
                    orientationMode = OrientationMode.NORTH_UP
                    map?.animateCamera(CameraUpdateFactory.bearingTo(0.0), 300)
                }
            },
            modifier = Modifier.align(Alignment.CenterStart).padding(8.dp),
        )
        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                    .padding(4.dp),
            ) {
                MapStyle.entries.forEach { style ->
                    FilterChip(
                        selected = style == selectedStyle,
                        onClick = { selectedStyle = style },
                        label = { Text(style.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
                FilterChip(
                    selected = is3d,
                    onClick = { is3d = !is3d },
                    label = { Text("3D") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }
            if (onFullScreenChange != null) {
                FilledTonalIconButton(onClick = { onFullScreenChange(!fullScreen) }, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(
                        if (fullScreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                        contentDescription = if (fullScreen) "Exit full screen" else "Full screen",
                    )
                }
            }
            Box(modifier = Modifier.padding(top = 4.dp)) {
                var exportMenuExpanded by remember { mutableStateOf(false) }
                FilledTonalIconButton(onClick = { exportMenuExpanded = true }) {
                    Icon(Icons.Rounded.Print, contentDescription = "Export map")
                }
                DropdownMenu(expanded = exportMenuExpanded, onDismissRequest = { exportMenuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Export as Image") },
                        leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null) },
                        onClick = {
                            exportMenuExpanded = false
                            // A GL map can't be drawn into a Canvas — ask it for a snapshot.
                            map?.snapshot { bitmap -> MapImageExporter.exportBitmapAsImage(context, bitmap, exportTitle) }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Export as PDF") },
                        leadingIcon = { Icon(Icons.Rounded.PictureAsPdf, contentDescription = null) },
                        onClick = {
                            exportMenuExpanded = false
                            map?.snapshot { bitmap -> MapImageExporter.exportBitmapAsPdf(context, bitmap, exportTitle) }
                        },
                    )
                }
            }
        }
    }
}

/** "Make the text outside the selected barangay less opacity" — halves a
 * color's own alpha when [dim] is true, otherwise returns it unchanged. */
private fun dimIf(dim: Boolean, argb: Int): Int {
    if (!dim) return argb
    val alpha = (Color.alpha(argb) * 0.4f).toInt()
    return Color.argb(alpha, Color.red(argb), Color.green(argb), Color.blue(argb))
}


private fun buildPickedPointBitmap(): Bitmap {
    val size = 24
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f
    canvas.drawCircle(center, center, 8f, Paint().apply { color = Color.WHITE; isAntiAlias = true })
    canvas.drawCircle(center, center, 6f, Paint().apply { color = Color.argb(255, 232, 113, 10); isAntiAlias = true })
    return bitmap
}

/** A plain text label with no pin — street names (dark text, light halo, so
 * it reads sitting on top of the street line itself) and area names (light,
 * italic text with a dark halo, the same soft area-name convention Google
 * Maps uses for parks/farmland/forest). */
private fun buildTextLabelBitmap(text: String, dimmed: Boolean, dark: Boolean, italic: Boolean): Bitmap {
    val textPaint = Paint().apply {
        textSize = if (italic) 15f else 13f
        isAntiAlias = true
        isFakeBoldText = !italic
        this.isUnderlineText = false
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, if (italic) android.graphics.Typeface.ITALIC else android.graphics.Typeface.NORMAL)
        color = if (dark) Color.rgb(0x20, 0x21, 0x24) else Color.rgb(0xDC, 0xED, 0xC8)
        textAlign = Paint.Align.CENTER
    }
    val haloPaint = Paint(textPaint).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = if (dark) Color.argb(220, 255, 255, 255) else Color.argb(200, 0, 0, 0)
    }
    val alpha = if (dimmed) 110 else 255
    val width = (textPaint.measureText(text) + 8f).toInt().coerceAtLeast(1)
    val height = (textPaint.textSize + 8f).toInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.saveLayerAlpha(null, alpha)
    val baselineY = height / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
    canvas.drawText(text, width / 2f, baselineY, haloPaint)
    canvas.drawText(text, width / 2f, baselineY, textPaint)
    canvas.restore()
    return bitmap
}

/** A built landmark pin bitmap plus where its actual pin tip (not the chip
 * hanging below it) sits within it, as a fractional anchor. */
private data class BuiltPin(val bitmap: Bitmap, val tipAnchor: PointF)

/** A modern pin+label bitmap (rounded head + pointed tail, like a Google
 * Maps pin, with a white rounded-rect name chip hanging below it) built
 * fresh per landmark since each one bakes its own name into the image. */
private fun buildLandmarkPinBitmap(landmark: Landmark, dimmed: Boolean = false): BuiltPin {
    val category = landmark.category
    val pinRadius = 15f
    val tailHeight = 9f
    val gap = 5f
    val chipPaddingH = 9f
    val chipPaddingV = 5f

    val textPaint = Paint().apply {
        textSize = 14f
        isAntiAlias = true
        color = Color.rgb(0x20, 0x21, 0x24)
        textAlign = Paint.Align.CENTER
    }
    val textWidth = textPaint.measureText(landmark.name)
    val chipWidth = textWidth + chipPaddingH * 2
    val chipHeight = textPaint.textSize + chipPaddingV * 2

    val width = (maxOf(pinRadius * 2, chipWidth) + 8f).toInt()
    val centerX = width / 2f
    val pinTopY = 4f
    val pinCenterY = pinTopY + pinRadius
    val pinTipY = pinCenterY + pinRadius + tailHeight
    val chipTopY = pinTipY + gap
    val height = (chipTopY + chipHeight + 4f).toInt()

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val layerAlpha = if (dimmed) 110 else 255
    canvas.saveLayerAlpha(null, layerAlpha)

    val head = Path().apply { addCircle(centerX, pinCenterY, pinRadius, Path.Direction.CW) }
    val tail = Path().apply {
        moveTo(centerX - 7f, pinCenterY + pinRadius - 4f)
        lineTo(centerX, pinTipY)
        lineTo(centerX + 7f, pinCenterY + pinRadius - 4f)
        close()
    }
    val pinShape = Path().apply { op(head, tail, Path.Op.UNION) }

    canvas.drawPath(
        pinShape,
        Paint().apply {
            color = Color.argb(70, 0, 0, 0)
            isAntiAlias = true
            maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
        },
    )
    canvas.drawPath(pinShape, Paint().apply { color = category.colorArgb; isAntiAlias = true })
    canvas.drawPath(
        pinShape,
        Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        },
    )
    val emojiPaint = Paint().apply {
        textSize = pinRadius * 1.1f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    val emojiY = pinCenterY - (emojiPaint.ascent() + emojiPaint.descent()) / 2f
    canvas.drawText(category.emoji, centerX, emojiY, emojiPaint)

    val chipRect = RectF(centerX - chipWidth / 2f, chipTopY, centerX + chipWidth / 2f, chipTopY + chipHeight)
    val chipRadius = chipHeight / 2f
    canvas.drawRoundRect(
        chipRect,
        chipRadius,
        chipRadius,
        Paint().apply {
            color = Color.argb(60, 0, 0, 0)
            isAntiAlias = true
            maskFilter = BlurMaskFilter(3f, BlurMaskFilter.Blur.NORMAL)
        },
    )
    canvas.drawRoundRect(chipRect, chipRadius, chipRadius, Paint().apply { color = Color.WHITE; isAntiAlias = true })
    val textY = chipTopY + chipHeight / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
    canvas.drawText(landmark.name, centerX, textY, textPaint)
    canvas.restore()

    return BuiltPin(bitmap, PointF(0.5f, pinTipY / height))
}
