package com.emfitsolutions.gopreach.ui.screens.sharelocation

import android.graphics.Bitmap
import com.emfitsolutions.gopreach.data.model.displayName
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.data.location.formatCoordinatesDms
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.ui.components.isValidLatitude
import com.emfitsolutions.gopreach.ui.components.isValidLongitude
import com.emfitsolutions.gopreach.ui.components.map.MapLibreHost
import com.emfitsolutions.gopreach.ui.components.map.rememberCurrentLocation
import com.emfitsolutions.gopreach.ui.components.map.MapLoadState
import com.emfitsolutions.gopreach.ui.components.map.maptilerStyleUrl
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private enum class ShareMapStyle(val label: String, val styleId: String) {
    SATELLITE("Satellite", "hybrid-v4"),
    STANDARD("Standard", "outdoor-v4"),
    NIGHT("Night", "basic-v2-dark"),
}

private const val SRC_SHARE = "share-src"
private const val LYR_CLUSTER = "share-cluster"
private const val LYR_CLUSTER_COUNT = "share-cluster-count"
private const val LYR_PIN = "share-pin"
private const val IMG_PIN = "share-img-pin"
private const val IMG_PIN_SELECTED = "share-img-pin-selected"
private val LABEL_FONT = arrayOf("Noto Sans Regular")

/** The blue round "👤" marker the Leaflet version used (bigger, gold-ringed
 * when selected), drawn once per style load and registered as a map image. */
private fun buildSharePinBitmap(selected: Boolean, density: Float): Bitmap {
    val size = ((if (selected) 34f else 26f) * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val c = size / 2f
    val border = (if (selected) 3f else 2f) * density
    canvas.drawCircle(c, c, c, Paint().apply { color = if (selected) android.graphics.Color.rgb(0xFF, 0xD6, 0x00) else android.graphics.Color.WHITE; isAntiAlias = true })
    canvas.drawCircle(c, c, c - border, Paint().apply { color = android.graphics.Color.rgb(0x1A, 0x73, 0xE8); isAntiAlias = true })
    val emojiPaint = Paint().apply {
        textSize = (if (selected) 16f else 13f) * density
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    canvas.drawText("👤", c, c - (emojiPaint.ascent() + emojiPaint.descent()) / 2f, emojiPaint)
    return bitmap
}

private fun addShareLayers(style: Style, density: Float) {
    style.addImage(IMG_PIN, buildSharePinBitmap(selected = false, density = density))
    style.addImage(IMG_PIN_SELECTED, buildSharePinBitmap(selected = true, density = density))
    style.addSource(
        GeoJsonSource(
            SRC_SHARE,
            FeatureCollection.fromFeatures(emptyList<Feature>()),
            GeoJsonOptions().withCluster(true).withClusterRadius(50).withClusterMaxZoom(15),
        ),
    )
    style.addLayer(
        CircleLayer(LYR_CLUSTER, SRC_SHARE).withFilter(Expression.has("point_count")).withProperties(
            PropertyFactory.circleColor("#1A73E8"),
            PropertyFactory.circleRadius(18f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2f),
        ),
    )
    style.addLayer(
        SymbolLayer(LYR_CLUSTER_COUNT, SRC_SHARE).withFilter(Expression.has("point_count")).withProperties(
            PropertyFactory.textField(Expression.get("point_count_abbreviated")),
            PropertyFactory.textFont(LABEL_FONT),
            PropertyFactory.textSize(13f),
            PropertyFactory.textColor("#FFFFFF"),
            PropertyFactory.textAllowOverlap(true),
        ),
    )
    style.addLayer(
        SymbolLayer(LYR_PIN, SRC_SHARE).withFilter(Expression.not(Expression.has("point_count"))).withProperties(
            PropertyFactory.iconImage(
                Expression.switchCase(
                    Expression.eq(Expression.get("sel"), Expression.literal("1")),
                    Expression.literal(IMG_PIN_SELECTED),
                    Expression.literal(IMG_PIN),
                ),
            ),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.textField(Expression.get("name")),
            PropertyFactory.textFont(LABEL_FONT),
            PropertyFactory.textSize(12f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_LEFT),
            PropertyFactory.textOffset(arrayOf(1.6f, 0f)),
            PropertyFactory.textColor("#202124"),
            PropertyFactory.textHaloColor("#FFFFFF"),
            PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textOptional(true),
        ),
    )
}

/** "Publisher Type Filter" — the exact four dropdown entries the spec asks
 * for, no more (deliberately not every [PublisherCategory] — Irregular/
 * Inactive/Reproof/Removed aren't in the spec's own list). */
private enum class PublisherTypeFilter(val label: String, val category: PublisherCategory?) {
    ALL("All Publisher", null),
    REGULAR_PIONEER("Regular Pioneer", PublisherCategory.REGULAR_PIONEER),
    SPECIAL_PIONEER("Special Pioneer", PublisherCategory.SPECIAL_PIONEER),
    AUXILIARY_PIONEER("Auxiliary Pioneer", PublisherCategory.AUXILIARY_PIONEER),
    UNBAPTIZED_PUBLISHER("Unbaptized Publisher", PublisherCategory.UNBAPTIZED_PUBLISHER),
}

private data class SharePoint(
    val id: String,
    val row: SharedLocationRow,
    val lat: Double,
    val lng: Double,
)

/**
 * "Shared Location Module — List View and Map View" — a native MapLibre map
 * (see [MapLibreHost]) with the same Publisher-type filter, clustering,
 * marker selection and details sheet the Leaflet version had. [rows] is the
 * *same* list List View shows (own-scoped/searched already, one shared source
 * of truth for both views); this composable only adds the Publisher-type
 * dropdown filter on top of it.
 *
 * Live location updates only call `setGeoJson` on one source — the map, its
 * style and the camera are never reloaded for a data change, and the camera
 * only re-fits on the first load and when the filter itself changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareLocationMapView(
    rows: List<SharedLocationRow>,
    onOpenTerritoryMap: (lat: Double, lng: Double, name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val density = LocalContext.current.resources.displayMetrics.density
    val myLocation by rememberCurrentLocation()

    // STEP — validate every coordinate before it ever reaches the map: not
    // NaN/Infinite, and within real lat/lng range.
    val allPoints = remember(rows) {
        rows.mapNotNull { row ->
            val lat = row.location.lat
            val lng = row.location.lng
            if (lat.isFinite() && lng.isFinite() && isValidLatitude(lat) && isValidLongitude(lng)) {
                SharePoint(id = row.person.id, row = row, lat = lat, lng = lng)
            } else {
                null
            }
        }
    }
    val invalidCount = rows.size - allPoints.size

    var selectedFilter by remember { mutableStateOf(PublisherTypeFilter.ALL) }
    var filterMenuExpanded by remember { mutableStateOf(false) }
    val filteredPoints = remember(allPoints, selectedFilter) {
        val category = selectedFilter.category
        if (category == null) allPoints else allPoints.filter { it.row.category == category }
    }

    var selectedRowId by remember { mutableStateOf<String?>(null) }
    val selectedRow = remember(selectedRowId, allPoints) { allPoints.firstOrNull { it.id == selectedRowId }?.row }

    var mapStyle by remember { mutableStateOf(ShareMapStyle.SATELLITE) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var loadState by remember { mutableStateOf(MapLoadState.LOADING) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    // Bumped each time a style finishes loading (a style switch wipes every
    // source/layer) so the data below is pushed again.
    var styleVersion by remember { mutableIntStateOf(0) }
    var lastFitFilter by remember { mutableStateOf<PublisherTypeFilter?>(null) }

    // Pushes the current (filtered) set into the map's one source; re-fits the
    // camera only on the first non-empty load and when the filter changes.
    LaunchedEffect(map, styleVersion, filteredPoints, selectedRowId, selectedFilter) {
        val m = map ?: return@LaunchedEffect
        val style = m.style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        val source = style.getSourceAs<GeoJsonSource>(SRC_SHARE) ?: return@LaunchedEffect
        val features = filteredPoints.map { p ->
            val label = p.row.groupName?.let { "${p.row.person.fullName} ($it)" } ?: p.row.person.fullName
            Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).also {
                it.addStringProperty("id", p.id)
                it.addStringProperty("name", label)
                it.addStringProperty("sel", if (p.id == selectedRowId) "1" else "0")
            }
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
        if (filteredPoints.isNotEmpty() && lastFitFilter != selectedFilter) {
            lastFitFilter = selectedFilter
            if (filteredPoints.size == 1) {
                m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(filteredPoints[0].lat, filteredPoints[0].lng), 16.0))
            } else {
                val bounds = LatLngBounds.Builder().includes(filteredPoints.map { LatLng(it.lat, it.lng) }).build()
                m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
            }
        }
    }

    // "System message for actions" — fired once per filter change rather than
    // persistently (the inline empty-state text below already covers "still
    // empty" for as long as it stays that way).
    LaunchedEffect(selectedFilter) {
        if (loadState == MapLoadState.LOADED && filteredPoints.isEmpty() && allPoints.isNotEmpty()) {
            snackbarHostState.showSnackbar("No Publisher matches the selected filter.")
        }
    }

    Box(modifier = modifier) {
        MapLibreHost(
            styleUrl = maptilerStyleUrl(mapStyle.styleId),
            myLocation = myLocation,
            reloadToken = reloadToken,
            onLoadStateChange = { loadState = it },
            onMapClick = { m, latLng ->
                val screen = m.projection.toScreenLocation(latLng)
                if (m.queryRenderedFeatures(screen, LYR_CLUSTER).isNotEmpty()) {
                    // Tapping a cluster zooms in on it.
                    m.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, m.cameraPosition.zoom + 2), 400)
                    true
                } else {
                    val id = m.queryRenderedFeatures(screen, LYR_PIN)
                        .firstNotNullOfOrNull { if (it.hasProperty("id")) it.getStringProperty("id") else null }
                    if (id != null) {
                        selectedRowId = id
                        true
                    } else {
                        false
                    }
                }
            },
            onStyleReady = { m, style ->
                addShareLayers(style, density)
                map = m
                styleVersion++
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (loadState == MapLoadState.LOADING) {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (loadState == MapLoadState.FAILED) {
            Column(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Unable to load the map. Check your internet connection and try again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { reloadToken++ }) { Text("Retry") }
            }
        } else if (filteredPoints.isEmpty()) {
            // "Display an appropriate message if no Publisher matches the
            // selected filter" — and, distinctly, if no one is sharing at
            // all yet.
            Card(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
            ) {
                Text(
                    if (allPoints.isEmpty()) "No one has shared their location yet." else "No Publisher matches the selected filter.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        if (loadState == MapLoadState.LOADED && invalidCount > 0) {
            Card(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 76.dp).padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
            ) {
                Text(
                    "$invalidCount publisher${if (invalidCount == 1) "" else "s"} without a valid GPS location ${if (invalidCount == 1) "isn't" else "aren't"} shown on the map.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        // "Add a dropdown filter that allows users to filter Publishers by
        // category" — same floating-Surface-over-the-map design language as
        // Territory Map's own category dropdown.
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp, start = 16.dp, end = 16.dp).fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 4.dp,
        ) {
            Box {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { filterMenuExpanded = true }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Rounded.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(selectedFilter.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = filterMenuExpanded, onDismissRequest = { filterMenuExpanded = false }) {
                    PublisherTypeFilter.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                filterMenuExpanded = false
                                selectedFilter = option
                            },
                        )
                    }
                }
            }
        }

        // Satellite / Standard / Night — the basemap choice the Leaflet layers
        // control used to offer.
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                .padding(4.dp),
        ) {
            ShareMapStyle.entries.forEach { style ->
                FilterChip(
                    selected = style == mapStyle,
                    onClick = { mapStyle = style },
                    label = { Text(style.label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
                )
            }
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
    }

    if (selectedRow != null) {
        SharePointDetailsSheet(
            row = selectedRow,
            onDismiss = { selectedRowId = null },
            onOpenTerritoryMap = {
                onOpenTerritoryMap(selectedRow.location.lat, selectedRow.location.lng, selectedRow.person.fullName)
                selectedRowId = null
            },
        )
    }
}

/** "Clicking or tapping a marker should display relevant Publisher
 * information" — Name/Group, Category, Congregation, Last Updated, and
 * clickable coordinates that open Territory Map, same field set List View's
 * own row already shows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharePointDetailsSheet(
    row: SharedLocationRow,
    onDismiss: () -> Unit,
    onOpenTerritoryMap: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF1A73E8)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("👤", style = MaterialTheme.typography.titleLarge)
                }
                Column {
                    Text(
                        row.groupName?.let { "${row.person.fullName} ($it)" } ?: row.person.fullName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        row.category?.displayName?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Publisher",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            DetailRow(label = "Congregation", value = row.congregationName)
            DetailRow(label = "Coordinates", value = formatCoordinatesDms(row.location.lat, row.location.lng))
            DetailRow(label = "Last Updated", value = formatShareRelativeTime(row.location.updatedAt))
            Button(onClick = onOpenTerritoryMap, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text("View in Territory Map")
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(top = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** "Last Updated: Just now" — same coarse human buckets as Territory Map's
 * own `formatRelativeTime` (private to that file, so duplicated here rather
 * than exported for a four-line function). */
private fun formatShareRelativeTime(updatedAtMillis: Long): String {
    val minutes = (System.currentTimeMillis() - updatedAtMillis) / 60_000
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes minute${if (minutes == 1L) "" else "s"} ago"
        minutes < 24 * 60 -> "${minutes / 60} hour${if (minutes / 60 == 1L) "" else "s"} ago"
        else -> "${minutes / (24 * 60)} day${if (minutes / (24 * 60) == 1L) "" else "s"} ago"
    }
}
