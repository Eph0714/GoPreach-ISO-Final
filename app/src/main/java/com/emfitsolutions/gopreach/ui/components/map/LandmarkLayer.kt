package com.emfitsolutions.gopreach.ui.components.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Water
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.Church
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import com.emfitsolutions.gopreach.data.repository.MapLandmark
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** What kind of place a [MapLandmark] is: its name in the popup, the badge colour and the one simple icon. The ids are the server's (`backend/src/landmarks.js`) and the website's. */
enum class LandmarkKind(val id: String, val label: String, val colorHex: String, val icon: ImageVector) {
    KINGDOM_HALL("kingdom_hall", "Kingdom Hall", "#4A3FA0", Icons.Filled.MenuBook),
    WORSHIP("worship", "Church / place of worship", "#7A5C45", Icons.Filled.Church),
    SCHOOL("school", "School", "#8E4FA8", Icons.Filled.School),
    HEALTH("health", "Hospital / clinic", "#C0392B", Icons.Filled.LocalHospital),
    GOVERNMENT("government", "Government office", "#40596B", Icons.Filled.AccountBalance),
    POLICE("police", "Police", "#2C5FA8", Icons.Filled.LocalPolice),
    FIRE("fire", "Fire station", "#B23A2E", Icons.Filled.LocalFireDepartment),
    MARKET("market", "Market / store", "#D2791F", Icons.Filled.ShoppingCart),
    SHOP("shop", "Shop", "#D2791F", Icons.Filled.Store),
    BANK("bank", "Bank", "#2A7A9B", Icons.Filled.AttachMoney),
    FUEL("fuel", "Gas station", "#C4551B", Icons.Filled.LocalGasStation),
    PARK("park", "Park / plaza", "#3C8A4A", Icons.Filled.Park),
    BRIDGE("bridge", "Bridge", "#5E6B73", Icons.Filled.Waves),
    WATER("water", "River / water", "#2D7FBF", Icons.Filled.Water),
    TRANSPORT("transport", "Bus terminal", "#33708A", Icons.Filled.DirectionsBus),
    LANDMARK("landmark", "Landmark", "#5E6B73", Icons.Filled.Flag);

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: LANDMARK
    }
}

/**
 * The landmark layer of the Territory Map: named places from OpenStreetMap as real map layers (symbols in the map's own coordinates), so they move, turn and zoom
 * with the map and never drift. Three layers, one per importance tier, appear at zoom 12 / 14 / 16; the map's own collision detection then hides whatever would
 * overlap, most important first, and the name shows only when zoomed in a bit further than the icon. The layers sit BELOW the territory labels and the records,
 * which therefore always win a collision and a tap.
 */
object LandmarkLayer {
    private const val SRC = "lm-src"
    private const val IMG = "lm-img-"
    private val TIERS = listOf(1 to 12f, 2 to 14f, 3 to 16f)
    private val FONT = arrayOf("Noto Sans Regular")
    private val LAYERS = TIERS.map { "lm-layer-${it.first}" }

    /** Adds the source, the icons and the layers. [below] is the first of the app's own layers; the landmarks go under it. */
    fun install(style: Style, density: Float, below: String?) {
        LandmarkKind.entries.forEach { style.addImage(IMG + it.id, badge(it, density)) }
        style.addSource(GeoJsonSource(SRC, FeatureCollection.fromFeatures(emptyList<Feature>())))
        TIERS.forEach { (tier, minZoom) ->
            val layer = SymbolLayer("lm-layer-$tier", SRC).withFilter(Expression.eq(Expression.get("tier"), Expression.literal(tier))).withProperties(
                PropertyFactory.iconImage(Expression.concat(Expression.literal(IMG), Expression.get("cat"))),
                PropertyFactory.iconSize(if (tier == 3) 0.8f else 0.92f),
                PropertyFactory.iconAllowOverlap(false),
                PropertyFactory.iconIgnorePlacement(false),
                PropertyFactory.iconOptional(false),
                // the name appears two zoom levels after the icon, and only where there is room
                PropertyFactory.textField(Expression.step(Expression.zoom(), Expression.literal(""), Expression.stop(minZoom + 1.5f, Expression.get("name")))),
                PropertyFactory.textFont(FONT),
                PropertyFactory.textSize(11f),
                PropertyFactory.textColor("#2B2F33"),
                PropertyFactory.textHaloColor("#FFFFFF"),
                PropertyFactory.textHaloWidth(1.5f),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
                PropertyFactory.textMaxWidth(7f),
                PropertyFactory.textOptional(true),
                PropertyFactory.textAllowOverlap(false),
                PropertyFactory.symbolSortKey(tier.toFloat()),
            )
            layer.minZoom = minZoom
            if (below != null && style.getLayer(below) != null) style.addLayerBelow(layer, below) else style.addLayer(layer)
        }
    }

    fun setLandmarks(style: Style, landmarks: List<MapLandmark>) {
        val src = style.getSourceAs<GeoJsonSource>(SRC) ?: return
        src.setGeoJson(
            FeatureCollection.fromFeatures(
                landmarks.map { l ->
                    Feature.fromGeometry(Point.fromLngLat(l.lng, l.lat), null, l.id).also {
                        it.addStringProperty("id", l.id)
                        it.addStringProperty("name", l.name)
                        it.addStringProperty("cat", LandmarkKind.of(l.cat).id)
                        it.addNumberProperty("tier", l.tier.coerceIn(1, 3))
                        it.addNumberProperty("lat", l.lat)
                        it.addNumberProperty("lng", l.lng)
                        l.address?.let { v -> it.addStringProperty("address", v) }
                        l.phone?.let { v -> it.addStringProperty("phone", v) }
                        l.website?.let { v -> it.addStringProperty("website", v) }
                        l.hours?.let { v -> it.addStringProperty("hours", v) }
                    }
                },
            ),
        )
    }

    fun setVisible(style: Style, visible: Boolean) {
        LAYERS.forEach { style.getLayer(it)?.setProperties(PropertyFactory.visibility(if (visible) Property.VISIBLE else Property.NONE)) }
    }

    /** The landmark under a tap, or null. */
    fun hit(map: MapLibreMap, screen: android.graphics.PointF, density: Float): MapLandmark? {
        val slop = 16f * density
        val box = android.graphics.RectF(screen.x - slop, screen.y - slop, screen.x + slop, screen.y + slop)
        val f = map.queryRenderedFeatures(box, *LAYERS.toTypedArray()).firstOrNull() ?: return null
        val p = f.geometry() as? Point ?: return null
        fun s(k: String) = if (f.hasProperty(k)) f.getStringProperty(k) else null
        return MapLandmark(
            id = s("id") ?: return null, name = s("name") ?: return null, cat = s("cat") ?: "landmark",
            tier = if (f.hasProperty("tier")) f.getNumberProperty("tier").toInt() else 2,
            lat = p.latitude(), lng = p.longitude(), address = s("address"), phone = s("phone"), website = s("website"), hours = s("hours"),
        )
    }

    /** A round badge with one white icon, 28 dp: simple and the same weight for every category. */
    private fun badge(kind: LandmarkKind, density: Float): Bitmap {
        val size = (28f * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        c.drawCircle(r, r + 0.5f * density, r - 1.5f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) })
        c.drawCircle(r, r, r - 2f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(kind.colorHex) })
        c.drawCircle(r, r, r - 2f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * density; color = Color.WHITE })
        val glyph = Path()
        collect(kind.icon.root, glyph)
        val inner = size - 12f * density
        val scale = inner / kind.icon.viewportWidth
        c.save()
        c.translate(6f * density, 6f * density)
        c.scale(scale, scale)
        c.drawPath(glyph, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        c.restore()
        return bmp
    }

    private fun collect(node: VectorNode, into: Path) {
        when (node) {
            is VectorGroup -> node.forEach { collect(it, into) }
            is VectorPath -> into.addPath(node.pathData.toPath().asAndroidPath())
        }
    }
}
