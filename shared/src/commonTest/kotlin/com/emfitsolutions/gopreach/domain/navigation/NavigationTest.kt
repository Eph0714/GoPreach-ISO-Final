package com.emfitsolutions.gopreach.domain.navigation

import com.emfitsolutions.gopreach.data.navigation.ValhallaRoutingService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The route below is a real answer from a Valhalla server (about 1.9 km through Solano, Nueva Vizcaya). Driving it is SIMULATED: positions are
 * computed along the route, so these tests show the logic, not a real vehicle.
 */
class NavigationTest {
    private val route = ValhallaRoutingService.parse(RESPONSE)

    /** Position [meters] along the route, optionally pushed [sideMeters] to the side (north). */
    private fun at(meters: Double, sideMeters: Double = 0.0): Pair<Double, Double> {
        var left = meters
        for (i in 1 until route.points.size) {
            val seg = NavigationEngine.meters(route.points[i - 1], route.points[i])
            if (left <= seg || i == route.points.lastIndex) {
                val t = if (seg == 0.0) 0.0 else (left / seg).coerceIn(0.0, 1.0)
                val a = route.points[i - 1]; val b = route.points[i]
                return (a.lat + (b.lat - a.lat) * t + sideMeters / 110_540.0) to (a.lng + (b.lng - a.lng) * t)
            }
            left -= seg
        }
        return route.points.last().let { it.lat to it.lng }
    }

    @Test
    fun theRealResponseIsReadIntoARouteWithSpokenSteps() {
        assertEquals(7, route.steps.size)
        assertTrue(route.points.size > 20, "points: ${route.points.size}")
        assertEquals(Maneuver.LEFT, route.steps[1].maneuver)
        assertEquals(Maneuver.DESTINATION, route.steps.last().maneuver)
        assertEquals("Turn left onto J. P. Rizal Avenue.", route.steps[1].alert)
        assertEquals("J. P. Rizal Avenue", route.steps[1].street)
        assertTrue(abs(route.distanceMeters - 1939.0) < 1.0)
        assertTrue(abs(route.durationSeconds - 367.3) < 0.5)
        route.steps.forEach { assertTrue(it.beginIndex in 0..route.points.lastIndex && it.endIndex in it.beginIndex..route.points.lastIndex, "$it") }
        assertTrue(abs(route.points.first().lat - 16.5176) < 0.001 && abs(route.points.first().lng - 121.1830) < 0.001)
    }

    @Test
    fun drivingTheWholeRouteFollowsEveryStepAndAnnouncesEachTurnOnceAndArrives() {
        val engine = NavigationEngine(route)
        val said = mutableListOf<String>()
        var lastStep = 0
        var arrived = false
        var meters = 0.0
        while (meters <= route.distanceMeters + 20 && !arrived) {
            val (la, ln) = at(meters)
            val p = engine.update(la, ln, 6f, 11f)
            assertFalse(p.offRoute, "on the route at $meters m")
            assertTrue(p.stepIndex >= lastStep, "steps only advance")
            lastStep = p.stepIndex
            said += p.announcements
            arrived = p.arrived
            meters += 11.0
        }
        assertTrue(arrived, "arrives at the end")
        assertEquals(route.steps.lastIndex, lastStep)
        assertTrue(said.size <= route.steps.size * 3, "each step is announced at most three times (ahead, at the turn, after it): $said")
        assertTrue(said.first().startsWith("Drive southwest"), said.first())
        assertTrue(said.any { it.startsWith("Turn left onto J. P. Rizal Avenue") }, "the first turn is announced close up: $said")
        assertTrue(said.any { it.startsWith("In ") && it.contains("meters") }, "a turn is announced ahead of time: $said")
        assertTrue(said.last().contains("arrived"), said.last())
    }

    @Test
    fun remainingDistanceNeverGrowsWhileDriving() {
        val engine = NavigationEngine(route)
        var prevRemaining = Double.MAX_VALUE
        var meters = 0.0
        while (meters < route.distanceMeters - 40) {
            val (la, ln) = at(meters)
            val p = engine.update(la, ln, 5f, 10f)
            assertTrue(p.remainingMeters <= prevRemaining + 0.001, "remaining must not grow")
            prevRemaining = p.remainingMeters
            meters += 9.0
        }
        assertTrue(prevRemaining < route.distanceMeters)
    }

    @Test
    fun leavingTheRouteNeedsSeveralFixesAndASingleBadFixIsIgnored() {
        val engine = NavigationEngine(route)
        val (la, ln) = at(300.0)
        engine.update(la, ln, 5f, 10f)
        val (ox, oy) = at(320.0, sideMeters = 90.0)
        assertFalse(engine.update(ox, oy, 5f, 10f).offRoute, "one stray fix is not a detour")
        val (bx, by) = at(330.0)
        assertFalse(engine.update(bx, by, 5f, 10f).offRoute)
        var off = false
        repeat(3) { i -> val (x, y) = at(340.0 + i * 10, sideMeters = 90.0); off = engine.update(x, y, 5f, 10f).offRoute }
        assertTrue(off, "three fixes in a row off the road mean a reroute is needed")
        val (x, y) = at(380.0, sideMeters = 90.0)
        assertTrue(engine.update(x, y, 5f, 10f).announcements.isEmpty(), "nothing is announced while off the route")
    }

    @Test
    fun poorAccuracyWidensTheToleranceSoItDoesNotCauseAFalseReroute() {
        val engine = NavigationEngine(route)
        repeat(6) { i ->
            val (x, y) = at(100.0 + i * 20, sideMeters = 45.0)
            assertFalse(engine.update(x, y, 40f, 8f).offRoute, "45 m off with a 40 m accuracy is within the margin")
        }
    }

    @Test
    fun progressNeverJumpsBackwardsOnANoisyFix() {
        val engine = NavigationEngine(route)
        val (a, b) = at(500.0)
        val first = engine.update(a, b, 5f, 10f)
        val (c, d) = at(470.0)
        val second = engine.update(c, d, 5f, 10f)
        assertTrue(second.remainingMeters <= first.remainingMeters + 0.001)
    }

    @Test
    fun formattingReadsTheWayADriverSpeaks() {
        assertEquals("30 m", RouteFormat.distance(31.0)); assertEquals("350 m", RouteFormat.distance(351.0)); assertEquals("1.2 km", RouteFormat.distance(1190.0))
        assertEquals("200 meters", RouteFormat.spokenDistance(204.0)); assertEquals("1.2 kilometers", RouteFormat.spokenDistance(1190.0))
        assertEquals("6 min", RouteFormat.duration(367.0)); assertEquals("1 h 5 min", RouteFormat.duration(3900.0))
    }

    @Test
    fun badResponsesAndHttpFailuresBecomeSpecificErrors() = runTest {
        withContext(Dispatchers.Default) {
            fun service(status: HttpStatusCode, body: String = "{}") = ValhallaRoutingService(HttpClient(MockEngine { respond(body, status) }))
            assertEquals(RoutingFailure.RATE_LIMITED, assertFailsWith<RoutingException> { service(HttpStatusCode.TooManyRequests).route(1.0, 1.0, 2.0, 2.0) }.failure)
            assertEquals(RoutingFailure.SERVER, assertFailsWith<RoutingException> { service(HttpStatusCode.BadGateway).route(1.0, 1.0, 2.0, 2.0) }.failure)
            assertEquals(RoutingFailure.NO_ROUTE, assertFailsWith<RoutingException> { service(HttpStatusCode.BadRequest).route(1.0, 1.0, 2.0, 2.0) }.failure)
            assertEquals(RoutingFailure.NO_ROUTE, assertFailsWith<RoutingException> { service(HttpStatusCode.OK).route(1.0, 1.0, 2.0, 2.0) }.failure)
            assertEquals(RoutingFailure.BAD_RESPONSE, assertFailsWith<RoutingException> { service(HttpStatusCode.OK, "<html>").route(1.0, 1.0, 2.0, 2.0) }.failure)
            assertEquals(7, service(HttpStatusCode.OK, RESPONSE).route(16.5176, 121.183, 16.526, 121.19).steps.size)
        }
    }
}

// A real Valhalla answer (trimmed of nothing): Solano, 16.5176/121.1830 to 16.5260/121.1900, car.
private val RESPONSE = """
{"trip":{"locations":[{"type":"break","lat":16.5176,"lon":121.183,"side_of_street":"right","original_index":0},{"type":"break","lat":16.526,"lon":121.19,"original_index":1}],"legs":[{"maneuvers":[{"type":2,"instruction":"Drive southwest.","verbal_succinct_transition_instruction":"Drive southwest. Then Turn left onto J. P. Rizal Avenue.","verbal_pre_transition_instruction":"Drive southwest. Then Turn left onto J. P. Rizal Avenue.","verbal_post_transition_instruction":"Continue for 30 meters.","bearing_after":206,"time":4.123,"length":0.028,"cost":4.123,"begin_shape_index":0,"end_shape_index":1,"verbal_multi_cue":true,"travel_mode":"drive","travel_type":"car"},{"type":15,"instruction":"Turn left onto J. P. Rizal Avenue/1/AH26.","verbal_transition_alert_instruction":"Turn left onto J. P. Rizal Avenue.","verbal_succinct_transition_instruction":"Turn left.","verbal_pre_transition_instruction":"Turn left onto J. P. Rizal Avenue, 1.","verbal_post_transition_instruction":"Continue for 400 meters.","street_names":["J. P. Rizal Avenue","1","AH26"],"bearing_before":206,"bearing_after":114,"time":43.275,"length":0.419,"cost":63.71,"begin_shape_index":1,"end_shape_index":14,"travel_mode":"drive","travel_type":"car"},{"type":16,"instruction":"Bear left to stay on J. P. Rizal Avenue/1/AH26.","verbal_transition_alert_instruction":"Bear left to stay on J. P. Rizal Avenue.","verbal_succinct_transition_instruction":"Bear left.","verbal_pre_transition_instruction":"Bear left to stay on J. P. Rizal Avenue, 1.","verbal_post_transition_instruction":"Continue for 700 meters.","street_names":["J. P. Rizal Avenue","1","AH26"],"bearing_before":114,"bearing_after":79,"time":60.68,"length":0.67,"cost":61.8,"begin_shape_index":14,"end_shape_index":36,"travel_mode":"drive","travel_type":"car"},{"type":15,"instruction":"Turn left onto Ballesteros Street.","verbal_transition_alert_instruction":"Turn left onto Ballesteros Street.","verbal_succinct_transition_instruction":"Turn left.","verbal_pre_transition_instruction":"Turn left onto Ballesteros Street.","verbal_post_transition_instruction":"Continue for 200 meters.","street_names":["Ballesteros Street"],"bearing_before":36,"bearing_after":295,"time":57.42,"length":0.212,"cost":68.842,"begin_shape_index":36,"end_shape_index":39,"travel_mode":"drive","travel_type":"car"},{"type":10,"instruction":"Turn right onto Magsaysay Street.","verbal_transition_alert_instruction":"Turn right onto Magsaysay Street.","verbal_succinct_transition_instruction":"Turn right.","verbal_pre_transition_instruction":"Turn right onto Magsaysay Street.","verbal_post_transition_instruction":"Continue for 500 meters.","street_names":["Magsaysay Street"],"bearing_before":294,"bearing_after":23,"time":178.798,"length":0.532,"cost":195.491,"begin_shape_index":39,"end_shape_index":43,"travel_mode":"drive","travel_type":"car"},{"type":10,"instruction":"Turn right onto Magsaysay Street Extension.","verbal_transition_alert_instruction":"Turn right onto Magsaysay Street Extension.","verbal_succinct_transition_instruction":"Turn right.","verbal_pre_transition_instruction":"Turn right onto Magsaysay Street Extension.","verbal_post_transition_instruction":"Continue for 80 meters.","street_names":["Magsaysay Street Extension"],"bearing_before":22,"bearing_after":75,"time":23.027,"length":0.076,"cost":27.451,"begin_shape_index":43,"end_shape_index":47,"travel_mode":"drive","travel_type":"car"},{"type":4,"instruction":"You have arrived at your destination.","verbal_transition_alert_instruction":"You will arrive at your destination.","verbal_pre_transition_instruction":"You have arrived at your destination.","bearing_before":115,"time":0.0,"length":0.0,"cost":0.0,"begin_shape_index":47,"end_shape_index":47,"travel_mode":"drive","travel_type":"car"}],"summary":{"has_time_restrictions":false,"has_toll":false,"has_highway":false,"has_ferry":false,"min_lat":16.515693,"min_lon":121.18287,"max_lat":16.526229,"max_lon":121.189996,"time":367.326,"length":1.939,"cost":421.418},"shape":"qtco^{olcfFnMlFxDmLzAwEtIyWfEsMl@iBhBuFtAiEnSwn@pA_E|Tcr@dBiF|Lq_@`@mAAiC]uC{@cCaAuAuA_B{AcAWQ}F}B{e@yRor@eVcC}@u\\kL}b@{NeC}@wiAy`@uRmGmOeF{DqA}IsC}ImDyCeBqC{BqIzVsNz`@sVlv@{hAa`@uhA}`@mbA}]wz@iX{@eASgA@qAvMo`@"}],"summary":{"has_time_restrictions":false,"has_toll":false,"has_highway":false,"has_ferry":false,"min_lat":16.515693,"min_lon":121.18287,"max_lat":16.526229,"max_lon":121.189996,"time":367.326,"length":1.939,"cost":421.418},"status_message":"Found route between points","status":0,"units":"kilometers","language":"en-US"}}
"""
