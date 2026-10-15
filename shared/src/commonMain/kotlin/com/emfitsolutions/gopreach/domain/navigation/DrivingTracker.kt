package com.emfitsolutions.gopreach.domain.navigation

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One GPS reading. [bearingDeg] is the course of travel reported by the GPS (clockwise from true north), never the phone's compass. */
data class GpsFix(
    val lat: Double,
    val lng: Double,
    val accuracyMeters: Float?,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val timeMillis: Long,
)

enum class GpsQuality {
    /** Waiting for the first usable fix. */
    ACQUIRING,
    GOOD,
    /** Fixes are arriving but too imprecise to move the arrow or turn it. */
    WEAK,
    /** The last accepted fix is too old to call live. */
    STALE,
}

/** Why the most recent fix was not used (null when it was). Shown to the driver as a short status. */
enum class FixRejection { INVALID, OLD, OUT_OF_ORDER, POOR_ACCURACY, JUMP }

data class DrivingSnapshot(
    /** The last fix that passed every check - the arrow is only ever drawn here, never at an estimated spot. */
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracyMeters: Float? = null,
    /** Smoothed direction of travel in degrees clockwise from true north (0..360), or null until one reliable course has been seen. */
    val courseDeg: Float? = null,
    /** True when the current movement is fast enough to trust the course; false when it is the last reliable one being kept. */
    val courseIsLive: Boolean = false,
    val speedKmh: Float? = null,
    val quality: GpsQuality = GpsQuality.ACQUIRING,
    val lastRejection: FixRejection? = null,
    /** Milliseconds since the last accepted fix, at the time of this snapshot. */
    val ageMillis: Long? = null,
) {
    val hasPosition: Boolean get() = lat != null && lng != null
}

/** Tunable limits. The defaults suit driving through territory. */
data class DrivingConfig(
    /** Fixes less precise than this are ignored entirely. */
    val maxAccuracyMeters: Float = 60f,
    /** Precision needed before a course is trusted. */
    val courseAccuracyMeters: Float = 35f,
    /** Slowest speed at which the direction of travel is believable (about 7 km/h). */
    val minCourseSpeedMps: Float = 2f,
    /** Distance that must be covered before a course is derived from positions when the GPS gives no bearing. */
    val minDerivedMoveMeters: Double = 7.0,
    /** Anything older than this when it arrives is a cached position, not a live one. */
    val maxFixAgeMillis: Long = 15_000L,
    /** The arrow is flagged stale after this long without an accepted fix. */
    val staleAfterMillis: Long = 8_000L,
    /** A jump faster than this between fixes (about 216 km/h) is a glitch. */
    val maxSpeedMps: Double = 60.0,
    /** Changes smaller than this are ignored so the arrow does not shake. */
    val courseDeadbandDeg: Float = 2.5f,
    /** After this many consecutive glitch jumps that agree with each other, the device is taken to have really moved (a tunnel, a long gap). */
    val relocateAfter: Int = 3,
)

/**
 * Turns raw GPS fixes into what the Driving Mode arrow and camera need: a trustworthy position, a smoothed direction of travel and an honest
 * quality state. Pure logic with no clock or platform calls, so a simulated route can be replayed in a test.
 *
 * Rules, in order, for every fix:
 *  1. invalid coordinates, a cached/old fix, or one older than a fix already used -> ignored;
 *  2. accuracy worse than [DrivingConfig.maxAccuracyMeters] -> ignored (quality WEAK), the arrow neither moves nor turns;
 *  3. an impossible jump -> ignored, unless several in a row agree, which means the device really went there;
 *  4. otherwise the position is accepted. The course is updated only when the speed and accuracy are good enough:
 *     the GPS bearing when it exists, else the direction between positions that are far enough apart. Slow or stationary
 *     readings keep the last reliable course, so the arrow never spins while stopped.
 */
class DrivingTracker(private val config: DrivingConfig = DrivingConfig()) {
    private var lat: Double? = null
    private var lng: Double? = null
    private var accuracy: Float? = null
    private var lastTime = 0L
    private var course: Float? = null
    private var courseLive = false
    private var speedMps: Float? = null
    private var rejection: FixRejection? = null
    private var weak = false

    // anchor for deriving a course from positions
    private var anchorLat: Double? = null
    private var anchorLng: Double? = null

    // glitch jump handling
    private var jumpCount = 0
    private var jumpLat = 0.0
    private var jumpLng = 0.0

    // a big turn at low speed has to be seen twice before the arrow follows it
    private var pendingRaw: Float? = null

    fun reset() {
        lat = null; lng = null; accuracy = null; lastTime = 0L; course = null; courseLive = false; speedMps = null
        rejection = null; weak = false; anchorLat = null; anchorLng = null; jumpCount = 0; pendingRaw = null
    }

    fun onFix(fix: GpsFix, nowMillis: Long): DrivingSnapshot {
        accept(fix, nowMillis)
        return snapshot(nowMillis)
    }

    /** Call about once a second so a missing signal turns into STALE even when no fix arrives. */
    fun snapshot(nowMillis: Long): DrivingSnapshot {
        val age = if (lat == null) null else (nowMillis - lastTime).coerceAtLeast(0L)
        val quality = when {
            lat == null -> if (weak) GpsQuality.WEAK else GpsQuality.ACQUIRING
            age != null && age > config.staleAfterMillis -> GpsQuality.STALE
            weak -> GpsQuality.WEAK
            else -> GpsQuality.GOOD
        }
        return DrivingSnapshot(
            lat = lat, lng = lng, accuracyMeters = accuracy, courseDeg = course, courseIsLive = courseLive && quality == GpsQuality.GOOD,
            speedKmh = speedMps?.let { it * 3.6f }, quality = quality, lastRejection = rejection, ageMillis = age,
        )
    }

    private fun accept(fix: GpsFix, now: Long) {
        if (!validCoordinates(fix.lat, fix.lng)) { rejection = FixRejection.INVALID; return }
        val time = if (fix.timeMillis > 0) fix.timeMillis else now
        if (now - time > config.maxFixAgeMillis) { rejection = FixRejection.OLD; return }
        if (lat != null && time < lastTime) { rejection = FixRejection.OUT_OF_ORDER; return }
        val acc = fix.accuracyMeters
        if (acc == null || acc.isNaN() || acc <= 0f || acc > config.maxAccuracyMeters) { weak = true; rejection = FixRejection.POOR_ACCURACY; return }

        val pLat = lat
        val pLng = lng
        var dist = 0.0
        var dt = 0.0
        if (pLat != null && pLng != null) {
            dist = distanceMeters(pLat, pLng, fix.lat, fix.lng)
            dt = (time - lastTime) / 1000.0
            val implied = if (dt > 0.2) dist / dt else 0.0
            val limit = maxOf(100.0, acc * 3.0)
            if (dist > limit && implied > config.maxSpeedMps) {
                // Several jumps to the same new place in a row mean the device really is there now.
                if (jumpCount > 0 && distanceMeters(jumpLat, jumpLng, fix.lat, fix.lng) < 100.0) jumpCount++ else { jumpCount = 1 }
                jumpLat = fix.lat; jumpLng = fix.lng
                if (jumpCount < config.relocateAfter) { rejection = FixRejection.JUMP; return }
                anchorLat = null; anchorLng = null; pendingRaw = null
            }
            jumpCount = 0
        }

        weak = false
        rejection = null
        lat = fix.lat; lng = fix.lng; accuracy = acc; lastTime = time

        val derivedSpeed = if (pLat != null && dt in 0.5..10.0) (dist / dt).toFloat() else null
        val speed = fix.speedMps?.takeIf { it >= 0f && !it.isNaN() } ?: derivedSpeed
        speedMps = speed
        updateCourse(fix, acc, speed)
    }

    private fun updateCourse(fix: GpsFix, acc: Float, speed: Float?) {
        val moving = speed != null && speed >= config.minCourseSpeedMps
        if (!moving || acc > config.courseAccuracyMeters) {
            courseLive = false
            // Standing still: the next move starts a fresh baseline instead of reusing a far-away anchor.
            if (!moving) { anchorLat = fix.lat; anchorLng = fix.lng; pendingRaw = null }
            return
        }
        var raw: Float? = fix.bearingDeg?.takeIf { it in 0f..360f && !it.isNaN() }?.let { normalize(it) }
        if (raw == null) {
            val aLat = anchorLat
            val aLng = anchorLng
            if (aLat == null || aLng == null) { anchorLat = fix.lat; anchorLng = fix.lng; courseLive = false; return }
            if (distanceMeters(aLat, aLng, fix.lat, fix.lng) < config.minDerivedMoveMeters) { courseLive = course != null; return }
            raw = bearingBetween(aLat, aLng, fix.lat, fix.lng)
            anchorLat = fix.lat; anchorLng = fix.lng
        } else {
            anchorLat = fix.lat; anchorLng = fix.lng
        }
        courseLive = true
        val current = course
        if (current == null) { course = raw; pendingRaw = null; return }
        val delta = shortestDelta(current, raw)
        // A near-reversal while barely moving is more likely noise than a U-turn: require a second reading that agrees.
        if (abs(delta) > 100f && (speed ?: 0f) < 4f) {
            val pending = pendingRaw
            if (pending == null || abs(shortestDelta(pending, raw)) > 30f) { pendingRaw = raw; return }
        }
        pendingRaw = null
        if (abs(delta) < config.courseDeadbandDeg) return
        val alpha = (0.3f + (speed ?: 0f) / 30f).coerceIn(0.3f, 0.75f)
        course = normalize(current + delta * alpha)
    }

    companion object {
        fun validCoordinates(lat: Double, lng: Double): Boolean =
            !lat.isNaN() && !lng.isNaN() && !lat.isInfinite() && !lng.isInfinite() && lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)

        /** Degrees wrapped into 0..360. */
        fun normalize(deg: Float): Float {
            val r = deg % 360f
            return if (r < 0f) r + 360f else if (r >= 360f) 0f else r
        }

        /** The shortest signed turn from [from] to [to], in -180..180 - never the long way round. */
        fun shortestDelta(from: Float, to: Float): Float {
            var d = (to - from) % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
        }

        /**
         * The rotation to draw something that points [courseDeg] from true north on a surface that is itself turned [mapBearingDeg]
         * (the camera's bearing): the on-screen angle, clockwise from the top of the screen. With a north-up map the two agree; with a
         * map that rotates to the direction of travel the arrow stays pointing up.
         */
        fun screenRotation(courseDeg: Float, mapBearingDeg: Float): Float = normalize(courseDeg - mapBearingDeg)

        fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val r = 6_371_000.0
            val p1 = lat1 * PI / 180
            val p2 = lat2 * PI / 180
            val dp = (lat2 - lat1) * PI / 180
            val dl = (lng2 - lng1) * PI / 180
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * r * atan2(sqrt(a), sqrt(1 - a))
        }

        /** Initial bearing from the first point to the second, degrees clockwise from true north. */
        fun bearingBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
            val p1 = lat1 * PI / 180
            val p2 = lat2 * PI / 180
            val dl = (lng2 - lng1) * PI / 180
            val y = sin(dl) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
            return normalize((atan2(y, x) * 180 / PI).toFloat())
        }
    }
}
