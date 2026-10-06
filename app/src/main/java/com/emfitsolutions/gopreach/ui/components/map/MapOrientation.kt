package com.emfitsolutions.gopreach.ui.components.map

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate as rotateScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.abs

/** Whether the map keeps North at the top, or turns so the way the device faces is "up". */
enum class OrientationMode { NORTH_UP, FOLLOW_HEADING }

/**
 * The device's compass heading in degrees clockwise from TRUE north, or null when
 * the device has no usable compass — no rotation-vector sensor, or the sensor
 * currently reports unreliable accuracy. Null is never replaced by a guess: the
 * orientation UI shows nothing compass-derived (and the map stays North-up) rather
 * than showing an inaccurate direction.
 *
 * Only listens while the screen is started. [location] (when known) is used to
 * correct magnetic north to true north.
 */
@Composable
fun rememberDeviceHeading(location: Pair<Double, Double>?): State<Float?> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val heading = remember { mutableStateOf<Float?>(null) }
    val latestLocation = rememberUpdatedState(location)

    DisposableEffect(lifecycle) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensorManager == null || rotationSensor == null) {
            heading.value = null
            return@DisposableEffect onDispose { }
        }
        var reliable = true
        var smoothed: Float? = null
        val rotationMatrix = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)

        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                reliable = accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_LOW
                if (!reliable) { smoothed = null; heading.value = null }
            }

            override fun onSensorChanged(event: SensorEvent) {
                if (!reliable) return
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val (axisX, axisY) = when (displayRotation(context)) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remapped)
                SensorManager.getOrientation(remapped, orientation)
                var azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                latestLocation.value?.let { (lat, lng) ->
                    azimuth += GeomagneticField(lat.toFloat(), lng.toFloat(), 0f, System.currentTimeMillis()).declination
                }
                val target = ((azimuth % 360f) + 360f) % 360f
                // Circular low-pass filter, so the wedge doesn't jitter.
                val prev = smoothed
                val next = if (prev == null) target else {
                    var delta = target - prev
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    ((prev + delta * 0.2f) % 360f + 360f) % 360f
                }
                smoothed = next
                val published = heading.value
                if (published == null || angleDiff(published, next) >= 2f) heading.value = next
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
                Lifecycle.Event.ON_STOP -> sensorManager.unregisterListener(listener)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose {
            lifecycle.removeObserver(observer)
            sensorManager.unregisterListener(listener)
        }
    }
    return heading
}

@Suppress("DEPRECATION")
private fun displayRotation(context: Context): Int =
    (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation ?: Surface.ROTATION_0

private fun angleDiff(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}

/** The map's current bearing in degrees (0 = North up), updated as the camera moves. */
@Composable
fun rememberMapBearing(map: MapLibreMap?): State<Float> {
    val bearing = remember { mutableStateOf(0f) }
    DisposableEffect(map) {
        if (map == null) return@DisposableEffect onDispose { }
        val listener = MapLibreMap.OnCameraMoveListener {
            val b = map.cameraPosition.bearing.toFloat()
            if (abs(b - bearing.value) > 0.5f) bearing.value = b
        }
        bearing.value = map.cameraPosition.bearing.toFloat()
        map.addOnCameraMoveListener(listener)
        onDispose { map.removeOnCameraMoveListener(listener) }
    }
    return bearing
}

/**
 * Applies the orientation rules to [map]:
 *  - no trustworthy compass → rotation gestures off and the map pinned North-up;
 *  - compass available → rotation allowed; in [OrientationMode.FOLLOW_HEADING] the
 *    map turns to the device heading.
 */
@Composable
fun MapOrientationEffects(map: MapLibreMap?, headingDeg: Float?, mode: OrientationMode) {
    val compassAvailable = headingDeg != null
    androidx.compose.runtime.LaunchedEffect(map, compassAvailable) {
        val m = map ?: return@LaunchedEffect
        m.uiSettings.isRotateGesturesEnabled = compassAvailable
        if (!compassAvailable && abs(m.cameraPosition.bearing) > 0.1) {
            m.animateCamera(CameraUpdateFactory.bearingTo(0.0), 300)
        }
    }
    androidx.compose.runtime.LaunchedEffect(map, mode, headingDeg?.let { (it / 3f).toInt() }) {
        val m = map ?: return@LaunchedEffect
        if (mode == OrientationMode.FOLLOW_HEADING && headingDeg != null) {
            m.animateCamera(CameraUpdateFactory.bearingTo(headingDeg.toDouble()), 250)
        }
    }
}

/**
 * A compass rose with N, E, S and W: the rose turns with the map ([mapBearing]) so
 * "N" always points to true north, and — only when a trustworthy compass [headingDeg]
 * exists — a blue pointer shows which way the device faces. Tapping toggles
 * [OrientationMode] (or, with no compass, just squares the map back to North-up).
 */
@Composable
fun MapOrientationIndicator(
    mapBearing: Float,
    headingDeg: Float?,
    mode: OrientationMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = buildString {
        append("Map orientation. North is ")
        append(if (abs(mapBearing) < 1f) "at the top." else "${((-mapBearing % 360f) + 360f).toInt() % 360} degrees from the top.")
        append(if (headingDeg == null) " Compass unavailable." else if (mode == OrientationMode.FOLLOW_HEADING) " Following your heading." else " Tap to follow your heading.")
    }
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 3.dp,
        border = BorderStroke(if (mode == OrientationMode.FOLLOW_HEADING) 2.dp else 0.5.dp, if (mode == OrientationMode.FOLLOW_HEADING) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.size(54.dp).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            // The rose, turned opposite to the map so letters stay fixed to the ground.
            Box(Modifier.size(54.dp).rotate(-mapBearing)) {
                RoseLetter("N", Alignment.TopCenter, mapBearing, Color(0xFFD93025))
                RoseLetter("E", Alignment.CenterEnd, mapBearing, MaterialTheme.colorScheme.onSurfaceVariant)
                RoseLetter("S", Alignment.BottomCenter, mapBearing, MaterialTheme.colorScheme.onSurfaceVariant)
                RoseLetter("W", Alignment.CenterStart, mapBearing, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (headingDeg != null) {
                val pointer = MaterialTheme.colorScheme.primary
                Canvas(Modifier.size(54.dp)) {
                    // Device heading relative to the screen's "up".
                    rotateScope(headingDeg - mapBearing, pivot = center) {
                        val tip = Offset(center.x, 9.dp.toPx())
                        val path = Path().apply {
                            moveTo(tip.x, tip.y)
                            lineTo(center.x - 4.dp.toPx(), center.y)
                            lineTo(center.x + 4.dp.toPx(), center.y)
                            close()
                        }
                        drawPath(path, pointer.copy(alpha = 0.85f))
                    }
                }
            }
        }
    }
}

@Composable
private fun RoseLetter(letter: String, alignment: Alignment, mapBearing: Float, color: Color) {
    Box(Modifier.size(54.dp), contentAlignment = alignment) {
        // Counter-rotate so the letter itself stays upright while the rose turns.
        Text(
            letter,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.rotate(mapBearing).then(Modifier.size(14.dp)),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
