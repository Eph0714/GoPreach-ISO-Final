package com.emfitsolutions.gopreach.domain.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawingGeometryTest {
    // ~1.1 km x ~1.07 km square near Bayombong.
    private val square = listOf(
        GeoPoint(16.00, 121.00), GeoPoint(16.00, 121.01), GeoPoint(16.01, 121.01), GeoPoint(16.01, 121.00),
    )

    private fun p(lat: Double, lng: Double) = GeoPoint(lat, lng)

    @Test
    fun strokeWithAGapIsClosedIntoAPolygon() {
        // A finger drawing round a block and stopping short of where it started.
        val stroke = listOf(
            p(16.002, 121.002), p(16.002, 121.005), p(16.002, 121.008),
            p(16.005, 121.008), p(16.008, 121.008),
            p(16.008, 121.005), p(16.008, 121.002),
            p(16.005, 121.002), p(16.0035, 121.002),
        )
        val ring = DrawingGeometry.closeStroke(stroke, toleranceMeters = 5.0)
        assertNotNull(ring)
        assertTrue(ring!!.size >= 4)
        assertTrue(DrawingGeometry.isSimple(ring))
        // The result covers the block that was circled (about 650 m x 670 m).
        assertTrue(DrawingGeometry.areaSqMeters(ring) > 300_000)
        assertTrue(DrawingGeometry.contains(ring, p(16.005, 121.005)))
    }

    @Test
    fun simplificationDropsRedundantPointsButKeepsTheShape() {
        val straight = (0..50).map { p(16.0, 121.0 + it * 0.0001) } + p(16.01, 121.005) + p(16.0, 121.0)
        val ring = DrawingGeometry.closeStroke(straight, toleranceMeters = 3.0)!!
        assertTrue("expected a handful of vertices, got ${ring.size}", ring.size <= 4)
    }

    @Test
    fun closedRingIsCounterClockwise() {
        val clockwise = square.reversed()
        val ring = DrawingGeometry.closeStroke(clockwise, toleranceMeters = 1.0)!!
        val parsed = DrawingGeometry.parsePolygon(DrawingGeometry.polygonJson(ring))!!
        // Counter-clockwise: the signed area (lng as x, lat as y) is positive.
        var sum = 0.0
        for (i in parsed.indices) {
            val a = parsed[i]
            val b = parsed[(i + 1) % parsed.size]
            sum += a.lng * b.lat - b.lng * a.lat
        }
        assertTrue(sum > 0)
    }

    @Test
    fun aLoopedStrokeBecomesASimplePolygon() {
        // A big square with a small knot where the finger crossed its own trail.
        val stroke = listOf(
            p(16.000, 121.000), p(16.000, 121.010), p(16.010, 121.010),
            p(16.0102, 121.0002), p(16.0098, 121.0004),
            p(16.0102, 121.0006), p(16.010, 121.000),
        )
        val ring = DrawingGeometry.closeStroke(stroke, toleranceMeters = 0.5)
        assertNotNull(ring)
        assertTrue(DrawingGeometry.isSimple(ring!!))
    }

    @Test
    fun aFigureEightBecomesOneSimpleLoop() {
        val figureEight = listOf(p(16.000, 121.000), p(16.010, 121.010), p(16.000, 121.010), p(16.010, 121.000))
        assertFalse(DrawingGeometry.isSimple(figureEight))
        val ring = DrawingGeometry.makeSimple(figureEight)
        assertNotNull(ring)
        assertTrue(DrawingGeometry.isSimple(ring!!))
    }

    @Test
    fun tinyOrDegenerateStrokesAreRejected() {
        assertNull(DrawingGeometry.closeStroke(listOf(p(16.0, 121.0), p(16.0, 121.0)), 3.0))
        assertNull(DrawingGeometry.closeStroke(listOf(p(16.0, 121.0), p(16.0000001, 121.0000001), p(16.0000002, 121.0)), 3.0))
        // A straight line has no area.
        assertNull(DrawingGeometry.closeStroke((0..20).map { p(16.0, 121.0 + it * 0.0005) }, 3.0))
    }

    @Test
    fun geoJsonRoundTripKeepsCoordinatesInLngLatOrder() {
        val json = DrawingGeometry.polygonJson(square)
        assertTrue(json.contains("\"Polygon\""))
        val parsed = DrawingGeometry.parsePolygon(json)!!
        assertEquals(4, parsed.size)
        assertTrue(parsed.any { it.lat == 16.01 && it.lng == 121.01 })
        val point = DrawingGeometry.parsePoint(DrawingGeometry.pointJson(p(16.5, 121.5)))!!
        assertEquals(16.5, point.lat, 0.0)
        assertEquals(121.5, point.lng, 0.0)
        // GeoJSON positions are [lng, lat].
        assertTrue(DrawingGeometry.pointJson(p(16.5, 121.5)).contains("[121.5,16.5]"))
        assertNull(DrawingGeometry.parsePolygon("{\"type\":\"Point\",\"coordinates\":[1,2]}"))
        assertNull(DrawingGeometry.parsePolygon("not json"))
    }

    @Test
    fun boundsAreTheBoundingBox() {
        val b = DrawingGeometry.bounds(square)
        assertEquals(16.00, b.minLat, 0.0)
        assertEquals(16.01, b.maxLat, 0.0)
        assertEquals(121.00, b.minLng, 0.0)
        assertEquals(121.01, b.maxLng, 0.0)
        assertTrue(b.contains(GeoBounds(16.002, 16.004, 121.002, 121.004)))
        assertFalse(b.contains(GeoBounds(16.002, 16.02, 121.002, 121.004)))
    }

    @Test
    fun drawingInsideATerritoryHasNoOutsideParts() {
        val inside = listOf(p(16.002, 121.002), p(16.002, 121.008), p(16.008, 121.008), p(16.008, 121.002))
        assertTrue(DrawingGeometry.outsideRuns(inside, listOf(square)).isEmpty())
        assertTrue(DrawingGeometry.isWithin(inside, listOf(square)))
    }

    @Test
    fun drawingThatLeavesATerritoryReportsTheOutsideStretch() {
        // Sticks 0.005 deg (~555 m) out of the east side.
        val leaking = listOf(p(16.002, 121.002), p(16.002, 121.015), p(16.008, 121.015), p(16.008, 121.002))
        val runs = DrawingGeometry.outsideRuns(leaking, listOf(square))
        assertTrue(runs.isNotEmpty())
        // Every point of every run lies east of the territory (or on its edge).
        assertTrue(runs.flatten().all { it.lng >= 121.0099 })
        assertFalse(DrawingGeometry.isWithin(leaking, listOf(square)))
    }

    @Test
    fun aFewMetersOverTheEdgeIsToleratedButMoreIsNot() {
        // 0.00002 deg is ~2.2 m past the east edge.
        val grazing = listOf(p(16.002, 121.002), p(16.002, 121.01002), p(16.008, 121.01002), p(16.008, 121.002))
        assertTrue(DrawingGeometry.isWithin(grazing, listOf(square), toleranceMeters = 3.0))
        val over = listOf(p(16.002, 121.002), p(16.002, 121.0101), p(16.008, 121.0101), p(16.008, 121.002))
        assertFalse(DrawingGeometry.isWithin(over, listOf(square), toleranceMeters = 3.0))
    }

    @Test
    fun aDrawingSpanningTwoAdjacentTerritoriesIsInsideTheirUnion() {
        val east = square.map { GeoPoint(it.lat, it.lng + 0.01) }
        val spanning = listOf(p(16.002, 121.005), p(16.002, 121.015), p(16.008, 121.015), p(16.008, 121.005))
        assertTrue(DrawingGeometry.isWithin(spanning, listOf(square, east)))
        assertFalse(DrawingGeometry.isWithin(spanning, listOf(square)))
    }

    @Test
    fun interiorPointOfAConcaveShapeIsInsideIt() {
        // A "U": the centroid sits in the notch, outside the shape.
        val u = listOf(
            p(16.000, 121.000), p(16.000, 121.009), p(16.009, 121.009), p(16.009, 121.006),
            p(16.003, 121.006), p(16.003, 121.003), p(16.009, 121.003), p(16.009, 121.000),
        )
        assertTrue(DrawingGeometry.contains(u, DrawingGeometry.interiorPoint(u)))
    }

    @Test
    fun cornerByCornerPathsAreCheckedForCrossingsBeforeTheyAreClosed() {
        // An "L" then a corner back across its own first line: P1 → P2 → P3 → P4 crosses P1-P2.
        val ok = listOf(p(16.000, 121.000), p(16.000, 121.010), p(16.010, 121.010), p(16.010, 121.000))
        assertFalse(DrawingGeometry.openPathCrossesItself(ok))
        // The closing edge only exists after Finish: this open path is fine, but closing it would not be.
        val crossing = listOf(p(16.000, 121.000), p(16.010, 121.010), p(16.000, 121.010), p(16.010, 121.000))
        assertTrue(DrawingGeometry.openPathCrossesItself(crossing))
        assertFalse(DrawingGeometry.openPathCrossesItself(listOf(p(16.0, 121.0), p(16.0, 121.01), p(16.01, 121.01))))
    }
}
