package com.emfitsolutions.gopreach.domain.navigation

import com.emfitsolutions.gopreach.data.navigation.RoutingService
import com.emfitsolutions.gopreach.data.navigation.ValhallaRoutingService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FreeDriveMatcherTest {
    /** A routing service whose only job here is to answer [matchTrail] with [answer] and count the calls. */
    private class FakeService(var answer: MatchedFix?) : RoutingService {
        var calls = 0
        override suspend fun route(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, walking: Boolean): Route = error("not used")
        override suspend fun matchTrail(trail: List<TrailPoint>, walking: Boolean): MatchedFix? { calls++; return answer }
    }

    // About 11 m per 0.0001 degrees of latitude.
    private fun lat(step: Int) = 14.0 + step * 0.0001

    @Test fun parsesTheLastMatchedPointAndTheRoadDirection() {
        val text = """{"matched_points":[
            {"lat":14.0000,"lon":121.0,"type":"matched"},
            {"lat":14.0003,"lon":121.0,"type":"unmatched"},
            {"lat":14.0010,"lon":121.0,"type":"matched"}]}"""
        val fix = assertNotNull(ValhallaRoutingService.parseMatch(text))
        assertEquals(14.0010, fix.lat, 1e-9)
        val bearing = assertNotNull(fix.bearingDeg)
        assertTrue(bearing < 1f || bearing > 359f, "driving north along the road: $bearing")
    }

    @Test fun nothingMatchedOrGarbageGivesNoFix() {
        assertNull(ValhallaRoutingService.parseMatch("""{"matched_points":[{"lat":14.0,"lon":121.0,"type":"unmatched"}]}"""))
        assertNull(ValhallaRoutingService.parseMatch("not json"))
        assertNull(ValhallaRoutingService.parseMatch("{}"))
    }

    @Test fun aMatchNearTheFixIsUsedAndAFarOneIsNeverDrawn() = runTest {
        val near = MatchedFix(lat(5) + 0.00005, 121.0, 0f)   // about 5 m from the fix
        val service = FakeService(near)
        val matcher = FreeDriveMatcher(service, walking = false, minIntervalMillis = 0)
        var last = MatchOutcome(false, null)
        for (i in 0..5) last = matcher.onFix(lat(i), 121.0, 8f, 12f, i * 1000L)
        assertEquals(near, last.fix)

        service.answer = MatchedFix(lat(5) + 0.001, 121.0, 0f)   // about 110 m away: another street
        matcher.reset()
        for (i in 0..5) last = matcher.onFix(lat(i), 121.0, 8f, 12f, 10_000L + i * 1000L)
        assertTrue(last.updated)
        assertNull(last.fix)
    }

    @Test fun standingStillOrPoorGpsNeverAsksTheServerForAMatch() = runTest {
        val service = FakeService(MatchedFix(14.0, 121.0, 0f))
        val matcher = FreeDriveMatcher(service, walking = false, minIntervalMillis = 0)
        for (i in 0..8) matcher.onFix(lat(i), 121.0, 8f, 0.2f, i * 1000L)   // parked: the speed is almost zero
        assertEquals(0, service.calls)
        val poor = matcher.onFix(lat(9), 121.0, 80f, 12f, 9_000L)           // 80 m accuracy
        assertEquals(0, service.calls)
        assertTrue(poor.updated)
        assertNull(poor.fix)
    }

    @Test fun theServerIsAskedNoMoreOftenThanTheInterval() = runTest {
        val service = FakeService(null)
        val matcher = FreeDriveMatcher(service, walking = false, minIntervalMillis = 3_000)
        for (i in 0..8) matcher.onFix(lat(i), 121.0, 8f, 12f, i * 500L)   // 4.5 seconds of driving
        assertTrue(service.calls in 1..2, "calls: ${service.calls}")
    }

    @Test fun walkingCountsAsMovingAtAWalkingPace() = runTest {
        val service = FakeService(null)
        val matcher = FreeDriveMatcher(service, walking = true, minIntervalMillis = 0)
        for (i in 0..5) matcher.onFix(lat(i), 121.0, 8f, 1.3f, i * 1000L)
        assertFalse(service.calls == 0)
    }
}
