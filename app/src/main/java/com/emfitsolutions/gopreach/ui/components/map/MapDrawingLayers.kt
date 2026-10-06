package com.emfitsolutions.gopreach.ui.components.map

import android.graphics.Color
import android.graphics.PointF
import com.emfitsolutions.gopreach.data.model.DrawingStatus
import com.emfitsolutions.gopreach.data.model.DrawingSyncState
import com.emfitsolutions.gopreach.data.model.TerritoryDrawing
import com.emfitsolutions.gopreach.domain.map.DrawingGeometry
import com.emfitsolutions.gopreach.domain.map.GeoPoint
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
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * The MapLibre layers of the drawing system — saved polygons, the in-progress draft, edit
 * handles and the red "outside your territory" highlight. All GeoJSON in real lat/lng, so
 * everything stays put through zoom, pan, rotation and resize.
 *
 * [MapLibreHost] installs these BEFORE the screen adds its own layers, so territory drawings sit
 * under the Searching / Return Visit / Bible Study record icons: a drawing can never hide or
 * block a record (taps reach the records first as well). Layer order, bottom → top: base map,
 * drawings, My Location arrow, records.
 */
object MapDrawingLayers {
    private const val SRC_SAVED = "gp-draw-src"
    private const val LYR_FILL = "gp-draw-fill"
    private const val LYR_LINE = "gp-draw-line"
    private const val LYR_LINE_SYNC = "gp-draw-line-sync"
    private const val SRC_LABELS = "gp-draw-labels-src"
    private const val LYR_LABELS = "gp-draw-labels"

    private const val SRC_DRAFT = "gp-draft-src"
    private const val LYR_DRAFT_FILL = "gp-draft-fill"
    private const val LYR_DRAFT_LINE = "gp-draft-line"
    private const val LYR_DRAFT_LINE_CASING = "gp-draft-line-casing"
    private const val SRC_DRAFT_LINE = "gp-draft-line-src"
    private const val SRC_REJECT = "gp-draft-reject-src"
    private const val LYR_REJECT = "gp-draft-reject"
    private const val SRC_HANDLES = "gp-draft-handles-src"
    private const val LYR_HANDLES = "gp-draft-handles"
    private const val SRC_MIDS = "gp-draft-mids-src"
    private const val LYR_MIDS = "gp-draft-mids"
    private const val SRC_OUTSIDE = "gp-draft-outside-src"
    private const val LYR_OUTSIDE = "gp-draft-outside"
    private val FONT_BOLD = arrayOf("Noto Sans Bold")

    private fun empty() = FeatureCollection.fromFeatures(emptyList<Feature>())

    fun install(style: Style) {
        if (style.getLayer(LYR_FILL) != null) return
        style.addSource(GeoJsonSource(SRC_SAVED, empty()))
        style.addLayer(
            FillLayer(LYR_FILL, SRC_SAVED).withProperties(
                PropertyFactory.fillColor(Expression.get("fill")),
                PropertyFactory.fillOpacity(Expression.get("opacity")),
            ),
        )
        // A strong outline keeps even a 10%-opacity polygon easy to identify.
        style.addLayer(
            LineLayer(LYR_LINE, SRC_SAVED).withProperties(
                PropertyFactory.lineColor(Expression.get("border")),
                PropertyFactory.lineWidth(
                    Expression.switchCase(Expression.eq(Expression.get("sel"), Expression.literal("1")), Expression.literal(5f), Expression.literal(3f)),
                ),
                PropertyFactory.lineOpacity(0.95f),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        // Not yet on the server (pending / failed): a dashed overlay on the outline — white while waiting, red when it failed.
        style.addLayer(
            LineLayer(LYR_LINE_SYNC, SRC_SAVED).withFilter(Expression.neq(Expression.get("sync"), Expression.literal("ok"))).withProperties(
                PropertyFactory.lineColor(Expression.switchCase(Expression.eq(Expression.get("sync"), Expression.literal("failed")), Expression.color(Color.parseColor("#D93025")), Expression.color(Color.WHITE))),
                PropertyFactory.lineWidth(1.8f),
                PropertyFactory.lineDasharray(arrayOf(1.5f, 2.5f)),
            ),
        )
        // The drawing's own text status, written on it (no pins).
        style.addSource(GeoJsonSource(SRC_LABELS, empty()))
        style.addLayer(
            SymbolLayer(LYR_LABELS, SRC_LABELS).withProperties(
                PropertyFactory.textField(Expression.get("status")),
                PropertyFactory.textFont(FONT_BOLD),
                PropertyFactory.textSize(12f),
                PropertyFactory.textColor(Expression.get("border")),
                PropertyFactory.textHaloColor("#FFFFFF"),
                PropertyFactory.textHaloWidth(2f),
                PropertyFactory.textOptional(true),
            ).also { it.minZoom = 13f },
        )

        // ---- draft (the Polygon Lasso in progress / being edited) ----
        // Fill: a translucent preview of the area as soon as there are 3 corners.
        style.addSource(GeoJsonSource(SRC_DRAFT, empty()))
        style.addLayer(
            FillLayer(LYR_DRAFT_FILL, SRC_DRAFT).withProperties(
                PropertyFactory.fillColor(Expression.get("fill")),
                PropertyFactory.fillOpacity(Expression.get("opacity")),
            ),
        )
        // Outline: straight lines corner to corner. OPEN while corners are being placed (the last corner is not joined to the
        // first until Finish), closed once finished.
        style.addSource(GeoJsonSource(SRC_DRAFT_LINE, empty()))
        style.addLayer(
            LineLayer(LYR_DRAFT_LINE_CASING, SRC_DRAFT_LINE).withProperties(
                PropertyFactory.lineColor("#FFFFFF"),
                PropertyFactory.lineWidth(7f),
                PropertyFactory.lineOpacity(0.85f),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            ),
        )
        style.addLayer(
            LineLayer(LYR_DRAFT_LINE, SRC_DRAFT_LINE).withProperties(
                PropertyFactory.lineColor(Expression.get("border")),
                PropertyFactory.lineWidth(3.5f),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            ),
        )
        // Midpoint handles: tap or drag one to add a corner between two others.
        style.addSource(GeoJsonSource(SRC_MIDS, empty()))
        style.addLayer(
            CircleLayer(LYR_MIDS, SRC_MIDS).withProperties(
                PropertyFactory.circleRadius(5f),
                PropertyFactory.circleColor("#1A73E8"),
                PropertyFactory.circleOpacity(0.6f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(1.5f),
            ),
        )
        // Corner points: normal = white with a blue ring; the LATEST corner is larger and green; the selected one is orange.
        style.addSource(GeoJsonSource(SRC_HANDLES, empty()))
        style.addLayer(
            CircleLayer(LYR_HANDLES, SRC_HANDLES).withProperties(
                PropertyFactory.circleRadius(
                    Expression.match(Expression.get("kind"), Expression.literal(8f), Expression.stop("last", 11f), Expression.stop("selected", 11f)),
                ),
                PropertyFactory.circleColor(
                    Expression.match(Expression.get("kind"), Expression.color(Color.WHITE), Expression.stop("last", Expression.color(Color.parseColor("#2E7D32"))), Expression.stop("selected", Expression.color(Color.parseColor("#F57C00")))),
                ),
                PropertyFactory.circleStrokeColor(
                    Expression.match(Expression.get("kind"), Expression.color(Color.parseColor("#1A73E8")), Expression.stop("last", Expression.color(Color.WHITE)), Expression.stop("selected", Expression.color(Color.WHITE))),
                ),
                PropertyFactory.circleStrokeWidth(3f),
            ),
        )
        // A point that was refused (outside the territory, duplicate, would cross a line): flashed red.
        style.addSource(GeoJsonSource(SRC_REJECT, empty()))
        style.addLayer(
            CircleLayer(LYR_REJECT, SRC_REJECT).withProperties(
                PropertyFactory.circleRadius(14f),
                PropertyFactory.circleColor("#D93025"),
                PropertyFactory.circleOpacity(0.35f),
                PropertyFactory.circleStrokeColor("#D93025"),
                PropertyFactory.circleStrokeWidth(3f),
            ),
        )
        style.addSource(GeoJsonSource(SRC_OUTSIDE, empty()))
        style.addLayer(
            LineLayer(LYR_OUTSIDE, SRC_OUTSIDE).withProperties(
                PropertyFactory.lineColor("#D93025"),
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineOpacity(0.95f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
    }

    private fun polygonFeature(ring: List<GeoPoint>): Feature {
        val closed = ring + ring.first()
        return Feature.fromGeometry(Polygon.fromLngLats(listOf(closed.map { Point.fromLngLat(it.lng, it.lat) })))
    }

    /** Draws every saved drawing. [hiddenIds] are drawings currently open for editing (shown as the draft instead). */
    fun setSaved(
        style: Style,
        drawings: List<TerritoryDrawing>,
        syncStates: Map<String, DrawingSyncState>,
        selectedId: String?,
        hiddenIds: Set<String>,
        statusFilter: DrawingStatus? = null,
    ) {
        val polygons = ArrayList<Feature>()
        val labels = ArrayList<Feature>()
        for (d in drawings) {
            if (d.id in hiddenIds) continue
            if (statusFilter != null && d.status != statusFilter) continue
            val ring = DrawingGeometry.parsePolygon(d.geometryJson) ?: continue
            val sync = when (syncStates[d.id] ?: DrawingSyncState.SYNCED) {
                DrawingSyncState.SYNCED -> "ok"
                DrawingSyncState.SYNC_FAILED -> "failed"
                else -> "pending"
            }
            polygons += polygonFeature(ring).also {
                it.addStringProperty("id", d.id)
                // The color is the status color, always — never an arbitrary stored one.
                it.addStringProperty("fill", d.status.colorHex)
                it.addStringProperty("border", d.status.borderHex)
                it.addNumberProperty("opacity", d.fillOpacity)
                it.addStringProperty("sync", sync)
                it.addStringProperty("sel", if (d.id == selectedId) "1" else "0")
            }
            val c = DrawingGeometry.interiorPoint(ring)
            labels += Feature.fromGeometry(Point.fromLngLat(c.lng, c.lat)).also {
                it.addStringProperty("status", d.status.label)
                it.addStringProperty("border", d.status.borderHex)
            }
        }
        style.getSourceAs<GeoJsonSource>(SRC_SAVED)?.setGeoJson(FeatureCollection.fromFeatures(polygons))
        style.getSourceAs<GeoJsonSource>(SRC_LABELS)?.setGeoJson(FeatureCollection.fromFeatures(labels))
    }

    /**
     * Draws the Polygon Lasso session. Every polygon shows its straight outline (open while corners are being placed);
     * with [showCorners] the corner points are drawn too (the latest one distinct, [selectedVertex] highlighted) plus
     * midpoint handles for adding a corner between two others. [rejected] flashes a refused tap in red.
     */
    fun setDraft(style: Style, items: List<DraftPolygon>, showCorners: Boolean, selectedVertex: Int?, rejected: GeoPoint?) {
        val fills = ArrayList<Feature>()
        val lines = ArrayList<Feature>()
        val handles = ArrayList<Feature>()
        val mids = ArrayList<Feature>()
        items.forEachIndexed { index, item ->
            if (item.ring.size >= 3) {
                fills += polygonFeature(item.ring).also {
                    it.addStringProperty("fill", item.fillColor)
                    it.addNumberProperty("opacity", item.opacity.toDouble())
                }
            }
            if (item.ring.size >= 2) {
                val path = if (item.closed) item.ring + item.ring.first() else item.ring
                lines += Feature.fromGeometry(LineString.fromLngLats(path.map { Point.fromLngLat(it.lng, it.lat) })).also {
                    it.addStringProperty("border", item.borderColor)
                }
            }
            if (showCorners) {
                val isActive = index == items.lastIndex
                item.ring.forEachIndexed { i, p ->
                    val kind = when {
                        isActive && i == selectedVertex -> "selected"
                        isActive && !item.closed && i == item.ring.lastIndex -> "last"
                        else -> "normal"
                    }
                    handles += Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).also { it.addStringProperty("kind", kind) }
                }
                if (isActive) {
                    val edges = if (item.closed) item.ring.size else item.ring.size - 1
                    for (i in 0 until edges) {
                        val p = item.ring[i]
                        val q = item.ring[(i + 1) % item.ring.size]
                        mids += Feature.fromGeometry(Point.fromLngLat((p.lng + q.lng) / 2, (p.lat + q.lat) / 2))
                    }
                }
            }
        }
        style.getSourceAs<GeoJsonSource>(SRC_DRAFT)?.setGeoJson(FeatureCollection.fromFeatures(fills))
        style.getSourceAs<GeoJsonSource>(SRC_DRAFT_LINE)?.setGeoJson(FeatureCollection.fromFeatures(lines))
        style.getSourceAs<GeoJsonSource>(SRC_HANDLES)?.setGeoJson(FeatureCollection.fromFeatures(handles))
        style.getSourceAs<GeoJsonSource>(SRC_MIDS)?.setGeoJson(FeatureCollection.fromFeatures(mids))
        style.getSourceAs<GeoJsonSource>(SRC_REJECT)?.setGeoJson(
            FeatureCollection.fromFeatures(
                if (rejected == null) emptyList() else listOf(Feature.fromGeometry(Point.fromLngLat(rejected.lng, rejected.lat))),
            ),
        )
    }

    /** Highlights the parts of a rejected drawing that fall outside the permitted territory. */
    fun setOutside(style: Style, runs: List<List<GeoPoint>>) {
        val features = runs.filter { it.size >= 2 }.map { run ->
            Feature.fromGeometry(LineString.fromLngLats(run.map { Point.fromLngLat(it.lng, it.lat) }))
        }
        style.getSourceAs<GeoJsonSource>(SRC_OUTSIDE)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /** The id of the saved drawing under [screen]; null if none. */
    fun hitTest(map: MapLibreMap, screen: PointF): String? =
        map.queryRenderedFeatures(screen, LYR_FILL)
            .firstNotNullOfOrNull { if (it.hasProperty("id")) it.getStringProperty("id") else null }
}
