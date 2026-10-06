package com.emfitsolutions.gopreach.ui.components.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * The one "My Location" marker every GoPreach map uses: a single arrow. Its tip is the
 * front of the user and it turns with the device heading, so it shows which way you are
 * facing, not just where you are. No walking/car/person/dot/compass icon, no travel mode.
 *
 * Added **last** by [MapLibreHost] (so it is above territories, drawings and pins). The sprite has a
 * white outline and soft shadow so it stays visible on satellite imagery and on light maps alike.
 */
object CurrentLocationLayer {
    private const val SRC = "gp-loc-src"
    private const val LYR_ARROW = "gp-loc-arrow"
    private const val SRC_ACC = "gp-loc-acc-src"
    private const val LYR_ACC = "gp-loc-acc"
    private const val IMG_ARROW = "gp-loc-img-arrow"

    /** Adds the arrow layer on top of everything already in [style]. Safe to call again after a style reload. */
    fun install(style: Style, density: Float) {
        if (style.getLayer(LYR_ARROW) != null) return
        style.addImage(IMG_ARROW, buildArrowBitmap(density))
        style.addSource(GeoJsonSource(SRC, FeatureCollection.fromFeatures(emptyList<Feature>())))
        // Accuracy: a faint circle of the GPS error radius under the arrow (only drawn when the fix is meaningfully imprecise).
        style.addSource(GeoJsonSource(SRC_ACC, FeatureCollection.fromFeatures(emptyList<Feature>())))
        style.addLayer(
            org.maplibre.android.style.layers.CircleLayer(LYR_ACC, SRC_ACC).withProperties(
                PropertyFactory.circleColor("#1A73E8"),
                PropertyFactory.circleOpacity(0.12f),
                PropertyFactory.circleStrokeColor("#1A73E8"),
                PropertyFactory.circleStrokeWidth(1f),
                PropertyFactory.circleStrokeOpacity(0.35f),
                PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
            ),
        )
        style.addLayer(
            SymbolLayer(LYR_ARROW, SRC).withProperties(
                PropertyFactory.iconImage(IMG_ARROW),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
                // Rotation is relative to true north even when the map itself is turned or tilted.
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            ),
        )
    }

    /** The layer id, for hit-testing taps on the arrow. */
    const val LAYER_ID = LYR_ARROW

    /** Removes the layer again — for a screen that rebuilds its own layers in place and re-[install]s this last. */
    fun remove(style: Style) {
        style.removeLayer(LYR_ARROW)
        style.removeLayer(LYR_ACC)
        style.removeSource(SRC_ACC)
        style.removeSource(SRC)
        style.removeImage(IMG_ARROW)
    }

    /** Puts the arrow at [location] (null hides it). */
    fun setLocation(style: Style, location: Pair<Double, Double>?) {
        val source = style.getSourceAs<GeoJsonSource>(SRC) ?: return
        if (location == null) source.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        else source.setGeoJson(Feature.fromGeometry(Point.fromLngLat(location.second, location.first)))
    }

    /** Draws the GPS accuracy circle ([meters] null or tiny hides it). Radius is metres on the ground at every zoom. */
    fun setAccuracy(style: Style, location: Pair<Double, Double>?, meters: Float?) {
        val source = style.getSourceAs<GeoJsonSource>(SRC_ACC) ?: return
        if (location == null || meters == null || meters < 8f) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            return
        }
        source.setGeoJson(Feature.fromGeometry(Point.fromLngLat(location.second, location.first)))
        val m = meters.coerceAtMost(500f).toDouble()
        // pixels = metres * 2^zoom / (78271.517 * cos(lat)), written as two zoom stops of an exponential(2) ramp.
        val r0 = m / (78271.517 * kotlin.math.cos(Math.toRadians(location.first)))
        style.getLayerAs<org.maplibre.android.style.layers.CircleLayer>(LYR_ACC)?.setProperties(
            PropertyFactory.circleRadius(
                org.maplibre.android.style.expressions.Expression.interpolate(
                    org.maplibre.android.style.expressions.Expression.exponential(2f),
                    org.maplibre.android.style.expressions.Expression.zoom(),
                    org.maplibre.android.style.expressions.Expression.stop(0, r0.toFloat()),
                    org.maplibre.android.style.expressions.Expression.stop(22, (r0 * 4194304.0).toFloat()),
                ),
            ),
        )
    }

    /** Points the arrow's tip [degrees] clockwise from true north, with the blink's [opacity] and [size] scale. */
    fun setAppearance(style: Style, degrees: Float, opacity: Float, size: Float) {
        style.getLayerAs<SymbolLayer>(LYR_ARROW)?.setProperties(
            PropertyFactory.iconRotate(degrees),
            PropertyFactory.iconOpacity(opacity),
            PropertyFactory.iconSize(size),
        )
    }

    /** True when [screen] (px in the map view) is on the arrow — used for "tap the arrow to center on me". */
    fun isHit(map: MapLibreMap, screen: PointF, location: Pair<Double, Double>, radiusPx: Float): Boolean {
        // Distance from the tap to where the arrow is drawn (its icon box is much bigger than the arrow itself, so a
        // box query would swallow taps meant for the markers around it).
        val at = map.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(location.first, location.second))
        return Math.hypot((screen.x - at.x).toDouble(), (screen.y - at.y).toDouble()) <= radiusPx
    }

    /** An arrowhead pointing up: tip = front. Dark blue body, white outline, soft drop shadow. */
    private fun buildArrowBitmap(density: Float): Bitmap {
        val w = (56f * density).toInt()
        val h = (64f * density).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2f
        val top = 8f * density
        val bottom = h - 10f * density
        val half = 16f * density
        val notch = bottom - 11f * density
        val arrow = Path().apply {
            moveTo(cx, top)                // tip (front)
            lineTo(cx + half, bottom)
            lineTo(cx, notch)              // notch in the tail
            lineTo(cx - half, bottom)
            close()
        }
        c.drawPath(arrow, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66000000
            style = Paint.Style.FILL
            setShadowLayer(5f * density, 0f, 2f * density, 0x99000000.toInt())
        })
        c.drawPath(arrow, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 5f * density; strokeJoin = Paint.Join.ROUND
        })
        c.drawPath(arrow, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A73E8.toInt(); style = Paint.Style.FILL })
        return bmp
    }
}

/**
 * Smooths what the GPS reports into marker motion that looks like a navigation app's:
 *  - the displayed position glides toward the latest fix (exponential easing, frame-rate independent) instead of jumping;
 *  - while moving, it keeps gliding along the recent velocity for a couple of seconds if the next fix is late,
 *    and is eased back to the real position when a fix arrives;
 *  - a big correction (over [SNAP_METERS]) snaps rather than gliding across the map.
 */
internal class LocationTrack {
    private var target: Pair<Double, Double>? = null
    private var shown: Pair<Double, Double>? = null
    private var velLat = 0.0 // deg/s
    private var velLng = 0.0
    private var lastFixMs = 0L

    /** Moving faster than a slow walk (≈ 0.8 m/s). */
    var moving = false
        private set

    fun reset() { target = null; shown = null; velLat = 0.0; velLng = 0.0; moving = false }

    fun onFix(fix: Pair<Double, Double>, nowMs: Long) {
        val prev = target
        if (prev != null && prev != fix) {
            val dt = (nowMs - lastFixMs) / 1000.0
            if (dt in 0.3..12.0) {
                // Blend with the previous estimate so one noisy fix can't whip the velocity around.
                velLat = 0.5 * velLat + 0.5 * (fix.first - prev.first) / dt
                velLng = 0.5 * velLng + 0.5 * (fix.second - prev.second) / dt
            }
        }
        val speed = metersPerSecond()
        moving = speed > 0.8
        if (!moving) { velLat = 0.0; velLng = 0.0 }
        target = fix
        lastFixMs = nowMs
        val s = shown
        if (s == null || distanceMeters(s, fix) > SNAP_METERS) shown = fix
    }

    private fun metersPerSecond(): Double {
        val t = target ?: return 0.0
        return Math.hypot(velLat * 110_540.0, velLng * 111_320.0 * Math.cos(Math.toRadians(t.first)))
    }

    /** Advances the displayed position by [dtSec]; returns it, or null when there is no fix. */
    fun step(nowMs: Long, dtSec: Double): Pair<Double, Double>? {
        val t = target ?: return null
        val s = shown ?: t
        val ahead = if (moving) ((nowMs - lastFixMs) / 1000.0).coerceIn(0.0, MAX_EXTRAPOLATE_SEC) else 0.0
        val aimLat = t.first + velLat * ahead
        val aimLng = t.second + velLng * ahead
        val k = 1.0 - Math.exp(-dtSec / EASE_SEC)
        val next = (s.first + (aimLat - s.first) * k) to (s.second + (aimLng - s.second) * k)
        shown = next
        return next
    }

    private fun distanceMeters(a: Pair<Double, Double>, b: Pair<Double, Double>): Double =
        Math.hypot((a.first - b.first) * 110_540.0, (a.second - b.second) * 111_320.0 * Math.cos(Math.toRadians(a.first)))

    private companion object {
        const val SNAP_METERS = 150.0
        const val MAX_EXTRAPOLATE_SEC = 2.5
        const val EASE_SEC = 0.45
    }
}

/**
 * Keeps the arrow on [location] and turns it to the heading, like a live navigation marker:
 *  - **position** glides (see [LocationTrack]) rather than jumping between GPS fixes;
 *  - **heading** is stabilized — changes under 3° are ignored, the rest are eased along the shortest way round,
 *    and when the compass goes unreliable ([headingDeg] null) the last good direction is kept;
 *  - **stationary**: the arrow gently blinks (a slow opacity/size pulse); while moving it stays solid.
 * Only runs while the screen is RESUMED, so nothing animates in the background.
 */
@Composable
fun CurrentLocationUpdater(map: MapLibreMap?, styleVersion: Int, location: Pair<Double, Double>?, headingDeg: Float?) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val lastHeading = remember { FloatArray(2) } // [0] = stabilized heading, [1] = 1 once seeded
    if (headingDeg != null) {
        var delta = headingDeg - lastHeading[0]
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        if (lastHeading[1] == 0f || abs(delta) >= HEADING_DEADBAND_DEG) { lastHeading[0] = headingDeg; lastHeading[1] = 1f }
    }
    val track = remember { LocationTrack() }
    val latestLocation = androidx.compose.runtime.rememberUpdatedState(location)

    LaunchedEffect(location) {
        if (location == null) track.reset() else track.onFix(location, SystemClock.elapsedRealtime())
    }
    val hasLocation = location != null
    LaunchedEffect(map, styleVersion, hasLocation) {
        if (!hasLocation || map == null) return@LaunchedEffect
        var shownHeading = lastHeading[0]
        var last = SystemClock.elapsedRealtime()
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            last = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(33)
                val now = SystemClock.elapsedRealtime()
                val dt = ((now - last) / 1000.0).coerceIn(0.0, 0.25)
                last = now
                val style = map.style?.takeIf { it.isFullyLoaded } ?: continue
                track.step(now, dt)?.let { CurrentLocationLayer.setLocation(style, it) }
                var delta = lastHeading[0] - shownHeading
                while (delta > 180f) delta -= 360f
                while (delta < -180f) delta += 360f
                shownHeading = ((shownHeading + delta * (1.0 - exp(-dt / 0.22)).toFloat()) % 360f + 360f) % 360f
                // Stationary: a slow, subtle blink. Moving: steady.
                val phase = (now % BLINK_PERIOD_MS).toFloat() / BLINK_PERIOD_MS
                val wave = 0.5f + 0.5f * sin(phase * 2f * PI.toFloat())
                val opacity = if (track.moving) 1f else 0.62f + 0.38f * wave
                val size = if (track.moving) 1f else 1f + 0.06f * wave
                CurrentLocationLayer.setAppearance(style, shownHeading, opacity, size)
            }
        }
    }
    latestLocation.value
}

private const val HEADING_DEADBAND_DEG = 3f
private const val BLINK_PERIOD_MS = 1800L
