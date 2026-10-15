package com.emfitsolutions.gopreach.domain.navigation

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Simulated GPS routes only - these tests replay computed coordinates, not a real moving device. */
class DrivingTrackerTest {
    private var lat = 16.5
    private var lng = 121.2
    private var clock = 1_000_000L

    private fun fix(
        accuracy: Float? = 8f, speed: Float? = 10f, bearing: Float? = null, la: Double = lat, ln: Double = lng, time: Long = clock,
    ) = GpsFix(la, ln, accuracy, speed, bearing, time)

    /** Moves the simulated vehicle [meters] along [bearing] and returns the fix one second later. */
    private fun drive(bearing: Float, meters: Double, withGpsBearing: Boolean = true, speed: Float? = meters.toFloat(), accuracy: Float? = 8f): GpsFix {
        val r = 6_371_000.0
        val d = meters / r
        val b = bearing * PI / 180
        val p1 = lat * PI / 180
        val l1 = lng * PI / 180
        val p2 = kotlin.math.asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + kotlin.math.atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        lat = p2 * 180 / PI
        lng = l2 * 180 / PI
        clock += 1000
        return fix(accuracy = accuracy, speed = speed, bearing = if (withGpsBearing) bearing else null)
    }

    private fun DrivingTracker.feed(f: GpsFix) = onFix(f, clock)

    @Test
    fun theArrowTurnsToTheDirectionOfTravelAndFollowsATurn() {
        val t = DrivingTracker()
        var s = t.feed(fix(speed = 0f))
        repeat(6) { s = t.feed(drive(0f, 12.0)) }
        assertTrue(abs(DrivingTracker.shortestDelta(0f, s.courseDeg!!)) < 3f, "heading north, was ${s.courseDeg}")
        repeat(10) { s = t.feed(drive(90f, 12.0)) }
        assertTrue(abs(DrivingTracker.shortestDelta(90f, s.courseDeg!!)) < 6f, "after turning east, was ${s.courseDeg}")
        assertTrue(s.courseIsLive); assertEquals(GpsQuality.GOOD, s.quality)
    }

    @Test
    fun theCourseComesFromMovementEvenWhenTheGpsGivesNoBearing() {
        val t = DrivingTracker()
        var s = t.feed(fix(speed = null))
        repeat(8) { s = t.feed(drive(180f, 15.0, withGpsBearing = false, speed = null)) }
        assertNotNull(s.courseDeg)
        assertTrue(abs(DrivingTracker.shortestDelta(180f, s.courseDeg!!)) < 5f, "derived course was ${s.courseDeg}")
    }

    @Test
    fun aStationaryDeviceNeverSpinsTheArrow() {
        val t = DrivingTracker()
        t.feed(fix(speed = 0f))
        repeat(6) { t.feed(drive(45f, 12.0)) }
        val held = t.snapshot(clock).courseDeg!!
        val random = listOf(10f, 200f, 95f, 330f, 170f, 40f, 280f, 5f)
        random.forEach { b ->
            clock += 1000
            val s = t.feed(fix(speed = 0.2f, bearing = b, la = lat + 0.000001, ln = lng))
            assertEquals(held, s.courseDeg, "course must be kept while stationary (bearing $b)")
            assertFalse(s.courseIsLive)
        }
    }

    @Test
    fun poorAccuracyNeitherMovesNorTurnsTheArrow() {
        val t = DrivingTracker()
        t.feed(fix(speed = 0f))
        repeat(5) { t.feed(drive(0f, 12.0)) }
        val before = t.snapshot(clock)
        clock += 1000
        val s = t.feed(fix(accuracy = 120f, speed = 12f, bearing = 270f, la = lat + 0.0004, ln = lng - 0.0004))
        assertEquals(before.lat, s.lat); assertEquals(before.lng, s.lng); assertEquals(before.courseDeg, s.courseDeg)
        assertEquals(GpsQuality.WEAK, s.quality); assertEquals(FixRejection.POOR_ACCURACY, s.lastRejection)
        val back = t.feed(drive(0f, 12.0))
        assertEquals(GpsQuality.GOOD, back.quality); assertNull(back.lastRejection)
    }

    @Test
    fun anImpossibleJumpIsIgnoredButARealRelocationIsAcceptedAfterItRepeats() {
        val t = DrivingTracker()
        t.feed(fix(speed = 0f))
        repeat(4) { t.feed(drive(0f, 12.0)) }
        val before = t.snapshot(clock)
        clock += 1000
        val far = fix(la = lat + 0.5, ln = lng, speed = 12f)
        val one = t.feed(far)
        assertEquals(FixRejection.JUMP, one.lastRejection); assertEquals(before.lat, one.lat)
        clock += 1000
        t.feed(far.copy(timeMillis = clock))
        clock += 1000
        val third = t.feed(far.copy(timeMillis = clock))
        assertEquals(far.lat, third.lat, "three agreeing fixes in a row are a real move")
        assertNull(third.lastRejection)
    }

    @Test
    fun invalidOldAndOutOfOrderFixesAreIgnored() {
        val t = DrivingTracker()
        assertEquals(FixRejection.INVALID, t.feed(fix(la = Double.NaN)).lastRejection)
        assertEquals(FixRejection.INVALID, t.feed(fix(la = 0.0, ln = 0.0)).lastRejection)
        assertEquals(FixRejection.INVALID, t.feed(fix(la = 95.0)).lastRejection)
        assertEquals(FixRejection.OLD, t.feed(fix(time = clock - 60_000)).lastRejection)
        assertFalse(t.snapshot(clock).hasPosition, "an ignored fix never places the arrow")
        t.feed(fix())
        assertEquals(FixRejection.OUT_OF_ORDER, t.feed(fix(time = clock - 5_000)).lastRejection)
    }

    @Test
    fun aMissingSignalBecomesStaleAndTheLastPositionIsNotCalledLive() {
        val t = DrivingTracker()
        t.feed(fix())
        assertEquals(GpsQuality.GOOD, t.snapshot(clock + 2_000).quality)
        val stale = t.snapshot(clock + 12_000)
        assertEquals(GpsQuality.STALE, stale.quality)
        assertTrue(stale.hasPosition); assertFalse(stale.courseIsLive)
        assertEquals(12_000L, stale.ageMillis)
        assertEquals(GpsQuality.ACQUIRING, DrivingTracker().snapshot(clock).quality)
    }

    @Test
    fun theLastReliableDirectionIsKeptWhenTheVehicleStops() {
        val t = DrivingTracker()
        t.feed(fix(speed = 0f))
        repeat(6) { t.feed(drive(270f, 12.0)) }
        val moving = t.snapshot(clock).courseDeg!!
        repeat(5) { clock += 1000; t.feed(fix(speed = 0f)) }
        assertEquals(moving, t.snapshot(clock).courseDeg)
    }

    @Test
    fun aNoisyReversalAtLowSpeedNeedsConfirmationButARealUTurnIsFollowed() {
        val t = DrivingTracker()
        t.feed(fix(speed = 0f))
        repeat(6) { t.feed(drive(0f, 3.0, speed = 3f)) }
        val north = t.snapshot(clock).courseDeg!!
        clock += 1000
        val glitch = t.feed(fix(speed = 3f, bearing = 180f))
        assertEquals(north, glitch.courseDeg, "one opposite reading at low speed is ignored")
        var s = glitch
        repeat(12) { s = t.feed(drive(180f, 3.0, speed = 3f)) }
        assertTrue(abs(DrivingTracker.shortestDelta(180f, s.courseDeg!!)) < 10f, "a sustained U-turn is followed, was ${s.courseDeg}")
    }

    @Test
    fun anglesAreNormalizedAndAlwaysTurnTheShortWay() {
        assertEquals(350f, DrivingTracker.normalize(-10f)); assertEquals(0f, DrivingTracker.normalize(360f)); assertEquals(5f, DrivingTracker.normalize(725f))
        assertEquals(20f, DrivingTracker.shortestDelta(350f, 10f)); assertEquals(-20f, DrivingTracker.shortestDelta(10f, 350f))
        assertEquals(-170f, DrivingTracker.shortestDelta(0f, 190f))
        val t = DrivingTracker()
        t.feed(fix(speed = 0f))
        var last = t.feed(drive(355f, 12.0)).courseDeg
        repeat(6) { last = t.feed(drive(355f, 12.0)).courseDeg }
        // heading drifts across north: it must never swing the long way round
        var prev = last!!
        listOf(358f, 2f, 6f, 9f).forEach { b ->
            val c = t.feed(drive(b, 12.0)).courseDeg!!
            assertTrue(abs(DrivingTracker.shortestDelta(prev, c)) < 15f, "$prev -> $c")
            prev = c
        }
    }

    @Test
    fun arrowRotationIsCorrectForANorthUpAndAHeadingUpMap() {
        assertEquals(90f, DrivingTracker.screenRotation(90f, 0f))        // north-up: east points right
        assertEquals(0f, DrivingTracker.screenRotation(90f, 90f))        // map turned to the course: the arrow points up
        assertEquals(315f, DrivingTracker.screenRotation(0f, 45f))       // map turned 45 deg: north now appears 45 deg to the left
        assertEquals(10f, DrivingTracker.screenRotation(5f, 355f))
    }

    @Test
    fun bearingAndDistanceBetweenPointsAreCorrect() {
        assertTrue(abs(DrivingTracker.bearingBetween(16.0, 121.0, 17.0, 121.0) - 0f) < 0.1f)
        assertTrue(abs(DrivingTracker.bearingBetween(16.0, 121.0, 16.0, 122.0) - 90f) < 0.6f)
        assertTrue(abs(DrivingTracker.bearingBetween(16.0, 121.0, 15.0, 121.0) - 180f) < 0.1f)
        assertTrue(abs(DrivingTracker.distanceMeters(16.0, 121.0, 17.0, 121.0) - 111_195.0) < 200.0)
    }
}
