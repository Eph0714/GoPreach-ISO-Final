package com.emfitsolutions.gopreach.ui.components.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.emfitsolutions.gopreach.data.location.LatLng as GpsPoint
import com.emfitsolutions.gopreach.domain.navigation.DrivingConfig
import com.emfitsolutions.gopreach.domain.navigation.DrivingSnapshot
import com.emfitsolutions.gopreach.domain.navigation.DrivingTracker
import com.emfitsolutions.gopreach.domain.navigation.FixRejection
import com.emfitsolutions.gopreach.domain.navigation.GpsFix
import com.emfitsolutions.gopreach.domain.navigation.GpsQuality
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/**
 * Driving Mode on the Territory Map: a navigation aid only. It filters the GPS fixes the screen already receives (it opens no GPS stream of
 * its own) and tells the map where to put the arrow and the camera. It never touches territories, assignments, statuses or reports.
 */
@Stable
class DrivingModeState(private val config: DrivingConfig = DrivingConfig()) {
    private var tracker = DrivingTracker(config)

    /** Walking Mode: the same navigation aid at walking pace (slower speeds count as moving, closer zoom, a walking marker). */
    var walking by mutableStateOf(false)
        private set

    /** The compass heading, used while walking when no direction of travel is known yet (standing still or very slow); changes under 5 degrees are ignored. */
    var compassDeg by mutableStateOf<Float?>(null)
        private set

    fun setCompass(deg: Float?) {
        val old = compassDeg
        if (deg == null || old == null) { compassDeg = deg; return }
        var d = deg - old; while (d > 180f) d -= 360f; while (d < -180f) d += 360f
        if (kotlin.math.abs(d) >= 5f) compassDeg = deg
    }

    var active by mutableStateOf(false)
        private set

    /** True: the map turns so the direction of travel is up. False: north stays up and only the arrow turns. */
    var headingUp by mutableStateOf(true)

    /** Camera tilt while following: 60 when the map is in 3D (the Waze-style forward view), 0 for a flat map. Set by the screen. */
    var tiltDeg by mutableStateOf(0.0)

    /** True while the camera is following the arrow; turns false when the person moves the map, and back on Recenter or after [RESUME_MS]. */
    var following by mutableStateOf(true)
        private set

    var snapshot by mutableStateOf(DrivingSnapshot())
        private set

    private var pausedAt = 0L

    /** Bumped by Recenter so the camera moves at once even when nothing else changed. */
    var recenterTick by mutableStateOf(0)
        private set

    fun start(walking: Boolean = false) {
        this.walking = walking
        tracker = DrivingTracker(if (walking) config.copy(minCourseSpeedMps = 0.5f, minDerivedMoveMeters = 3.0, courseAccuracyMeters = 35f) else config)
        tracker.reset()
        snapshot = DrivingSnapshot()
        following = true
        active = true
    }

    fun stop() {
        active = false
        walking = false
        compassDeg = null
        following = true
        tracker.reset()
        snapshot = DrivingSnapshot()
    }

    fun onFix(fix: GpsPoint, nowMillis: Long) {
        if (!active) return
        snapshot = tracker.onFix(GpsFix(fix.lat, fix.lng, fix.accuracyMeters, fix.speedMps, fix.bearingDeg, fix.timeMillis), nowMillis)
        // After the person has moved the map, following picks up again with the next fix once they have left it alone for a while.
        if (!following && nowMillis - pausedAt >= RESUME_MS) following = true
    }

    /** About once a second: lets a missing signal turn the status to stale. */
    fun tick(nowMillis: Long) {
        if (!active) return
        snapshot = tracker.snapshot(nowMillis)
    }

    fun userMovedMap(nowMillis: Long) {
        if (!active || !following) { if (active) pausedAt = nowMillis; return }
        following = false
        pausedAt = nowMillis
    }

    fun recenter() {
        following = true
        recenterTick++
    }

    /** One plain sentence for the GPS status control (also read aloud by screen readers). */
    fun statusText(online: Boolean): String {
        val s = snapshot
        val base = when (s.quality) {
            GpsQuality.ACQUIRING -> if (s.lastRejection == FixRejection.POOR_ACCURACY) "Weak GPS signal. Waiting for a more precise location." else "Looking for your GPS location..."
            GpsQuality.GOOD -> {
                val acc = s.accuracyMeters?.let { " Accuracy about ${it.toInt()} m." }.orEmpty()
                if (s.courseDeg == null) "GPS is working.$acc Direction appears once you start moving." else "GPS is working.$acc"
            }
            GpsQuality.WEAK -> "Weak GPS signal (about ${s.accuracyMeters?.toInt() ?: "?"} m). The arrow is not moving or turning until the signal improves."
            GpsQuality.STALE -> "No recent GPS update (${(s.ageMillis ?: 0) / 1000} s). The arrow shows your last known position, not your live position."
        }
        return if (online) base else "$base You are offline - the GPS still works, but map tiles may not load."
    }

    companion object {
        const val RESUME_MS = 3_000L
        const val NAV_ZOOM = 17.0
        const val MIN_FOLLOW_ZOOM = 15.5
        const val MAX_FOLLOW_ZOOM = 18.5
        /** The top padding of the map as a share of its height: the arrow sits in the middle of what is left below it, so with 0.60 it is about 80% of the way down (the lowest comfortable spot) and nearly all of the screen shows the road ahead. */
        const val LOWER_THIRD = 0.60
    }
}

/**
 * Moves the camera while Driving Mode follows the arrow: eases to the latest accepted position (never an estimated one), turns the map to the
 * direction of travel when [DrivingModeState.headingUp] is on, keeps the arrow in the lower-centre, and never changes the zoom suddenly (only when
 * it was outside street level). Moving the map by hand pauses it; turning Driving Mode off puts the padding and north-up back.
 */
@Composable
fun DrivingCameraEffect(map: MapLibreMap?, state: DrivingModeState?) {
    if (state == null) return
    val snap = state.snapshot

    // A hand gesture on the map pauses following.
    DisposableEffect(map, state.active) {
        val m = map
        if (m == null || !state.active) return@DisposableEffect onDispose { }
        val listener = MapLibreMap.OnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) state.userMovedMap(com.emfitsolutions.gopreach.platform.nowMillis())
        }
        m.addOnCameraMoveStartedListener(listener)
        onDispose { m.removeOnCameraMoveStartedListener(listener) }
    }

    // Leaving Driving Mode: back to the normal map.
    DisposableEffect(map, state.active) {
        val m = map
        if (m != null && state.active) m.setPadding(0, (m.height * DrivingModeState.LOWER_THIRD).toInt(), 0, 0)
        onDispose {
            if (m != null) {
                m.setPadding(0, 0, 0, 0)
                if (state.headingUp && m.cameraPosition.bearing != 0.0) m.easeCamera(CameraUpdateFactory.bearingTo(0.0), 500)
            }
        }
    }

    LaunchedEffect(map, state.active, state.following, state.headingUp, state.tiltDeg, state.recenterTick, snap.lat, snap.lng, snap.courseDeg, state.compassDeg) {
        val m = map ?: return@LaunchedEffect
        val lat = snap.lat ?: return@LaunchedEffect
        val lng = snap.lng ?: return@LaunchedEffect
        if (!state.active || !state.following) return@LaunchedEffect
        // Route previews and navigation reset the padding to zero: put the arrow back in the lowest position before the camera moves (changing it afterwards would cancel the animation).
        val top = (m.height * DrivingModeState.LOWER_THIRD).toInt()
        if (m.padding[1] != top) m.setPadding(0, top, 0, 0)
        val current = m.cameraPosition
        val zoom = if (current.zoom in DrivingModeState.MIN_FOLLOW_ZOOM..DrivingModeState.MAX_FOLLOW_ZOOM) current.zoom else if (state.walking) DrivingModeState.NAV_ZOOM + 1.0 else DrivingModeState.NAV_ZOOM
        val course = snap.courseDeg
        val bearing = when {
            !state.headingUp -> 0.0
            course != null -> course.toDouble()
            state.walking && state.compassDeg != null -> state.compassDeg!!.toDouble()   // standing still: the way the phone faces
            else -> current.bearing
        }
        val update = CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(lat, lng)).zoom(zoom).bearing(bearing).tilt(state.tiltDeg).build())
        // A recenter is quicker; ordinary follow updates glide for about a second so the map never jumps.
        m.easeCamera(update, 800)
    }
}

/** What the driver is told when Driving Mode cannot start; null when it can. */
enum class DrivingBlock { NO_PERMISSION, SERVICES_OFF }
