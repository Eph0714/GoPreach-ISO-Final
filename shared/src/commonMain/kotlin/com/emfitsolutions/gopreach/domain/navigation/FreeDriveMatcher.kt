package com.emfitsolutions.gopreach.domain.navigation

import com.emfitsolutions.gopreach.data.navigation.RoutingService

/** One filtered GPS position of the recent trail. */
data class TrailPoint(val lat: Double, val lng: Double)

/** Where the road server says the vehicle is: a point ON a road and the direction that road runs. */
data class MatchedFix(val lat: Double, val lng: Double, val bearingDeg: Float?)

/** What [FreeDriveMatcher.onFix] decided: [updated] false = keep what is drawn; true with a null [fix] = the arrow is not on a known road, use the plain GPS. */
data class MatchOutcome(val updated: Boolean, val fix: MatchedFix?)

/**
 * Keeps the arrow on the road when driving or walking WITHOUT a planned route (Drive / Walk with no destination), like a navigation app's "snap to road".
 * It keeps the last few good fixes, and about every [minIntervalMillis] asks the routing server to match that trail to the road network; the answer is only
 * used when it lies close to the real fix, so a wrong match can never drag the arrow onto another street. Never used when the phone is standing still, when
 * the GPS is poor, or when the server cannot be reached (the arrow then simply follows the GPS, as before).
 */
class FreeDriveMatcher(
    private val service: RoutingService,
    private val walking: Boolean,
    private val minIntervalMillis: Long = 3_000L,
    private val maxTrail: Int = 10,
) {
    private val trail = ArrayList<TrailPoint>()
    private var lastCallAt = Long.MIN_VALUE / 2
    private var busy = false

    fun reset() { trail.clear(); lastCallAt = Long.MIN_VALUE / 2; busy = false }

    suspend fun onFix(lat: Double, lng: Double, accuracyMeters: Float?, speedMps: Float?, nowMillis: Long): MatchOutcome {
        val acc = accuracyMeters ?: return MatchOutcome(false, null)
        if (acc > MAX_ACCURACY_METERS) return MatchOutcome(true, null)
        val moving = (speedMps ?: 0f) >= if (walking) MIN_WALK_MPS else MIN_DRIVE_MPS
        if (!moving) return MatchOutcome(false, null)
        val last = trail.lastOrNull()
        if (last == null || DrivingTracker.distanceMeters(last.lat, last.lng, lat, lng) >= MIN_STEP_METERS) {
            trail += TrailPoint(lat, lng)
            while (trail.size > maxTrail) trail.removeAt(0)
        }
        if (trail.size < 3 || busy || nowMillis - lastCallAt < minIntervalMillis) return MatchOutcome(false, null)
        busy = true
        lastCallAt = nowMillis
        return try {
            val matched = service.matchTrail(trail.toList(), walking)
            val tolerance = maxOf(if (walking) 12.0 else 25.0, acc.toDouble())
            if (matched != null && DrivingTracker.distanceMeters(matched.lat, matched.lng, lat, lng) <= tolerance) MatchOutcome(true, matched) else MatchOutcome(true, null)
        } finally {
            busy = false
        }
    }

    companion object {
        const val MAX_ACCURACY_METERS = 40f
        const val MIN_DRIVE_MPS = 3f
        const val MIN_WALK_MPS = 0.8f
        const val MIN_STEP_METERS = 4.0
    }
}
