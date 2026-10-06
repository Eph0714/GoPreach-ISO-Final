package com.emfitsolutions.gopreach.domain.map

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A geographic point. Pure Kotlin on purpose — no MapLibre/Android types — so
 * every rule below can be unit-tested on the JVM. */
data class GeoPoint(val lat: Double, val lng: Double)

data class GeoBounds(val minLat: Double, val maxLat: Double, val minLng: Double, val maxLng: Double) {
    fun contains(other: GeoBounds, epsilon: Double = 0.0) =
        other.minLat >= minLat - epsilon && other.maxLat <= maxLat + epsilon &&
            other.minLng >= minLng - epsilon && other.maxLng <= maxLng + epsilon
}

/**
 * Geometry for the territory drawing tools: closing/simplifying a freehand
 * stroke into a valid polygon, GeoJSON round-tripping, and the containment
 * maths the boundary validation uses. All inputs/outputs are real lat/lng — the
 * only place screen coordinates ever exist is the transient stroke before it is
 * converted (see MapDrawingOverlay).
 *
 * Distances use a local equirectangular projection (meters around a reference
 * latitude), which is accurate to well under a meter at the scale of a drawing
 * (a few km at most).
 */
object DrawingGeometry {
    const val MAX_VERTICES = 400
    private const val M_PER_DEG_LAT = 110_540.0
    private const val M_PER_DEG_LNG = 111_320.0

    // ---- projection ---------------------------------------------------------

    private class Proj(refLat: Double) {
        val kx = M_PER_DEG_LNG * cos(Math.toRadians(refLat))
        fun x(p: GeoPoint) = p.lng * kx
        fun y(p: GeoPoint) = p.lat * M_PER_DEG_LAT
    }

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val proj = Proj((a.lat + b.lat) / 2)
        return hypot(proj.x(a) - proj.x(b), proj.y(a) - proj.y(b))
    }

    // ---- stroke -> polygon --------------------------------------------------

    /**
     * Turns a freehand stroke (start..end, not necessarily meeting) into a
     * closed, simple polygon ring (returned WITHOUT a repeated closing point),
     * or null if the stroke is too small/degenerate to be an area.
     *
     * The gap between the end and the start is closed implicitly (a ring's last
     * edge always runs back to its first point); the stroke is simplified with
     * Douglas-Peucker at [toleranceMeters]; and if the finger crossed its own
     * path, the self-crossing loop is cut away, keeping the largest loop.
     */
    fun closeStroke(stroke: List<GeoPoint>, toleranceMeters: Double, minAreaSqMeters: Double = 4.0): List<GeoPoint>? {
        val deduped = stroke.fold(ArrayList<GeoPoint>()) { acc, p -> if (acc.lastOrNull() != p) acc.add(p); acc }
        if (deduped.size < 3) return null
        var ring: List<GeoPoint> = simplifyRing(deduped, toleranceMeters)
        if (ring.size < 3) return null
        ring = makeSimple(ring) ?: return null
        // Respect the vertex budget even for a very detailed stroke.
        var tol = toleranceMeters
        while (ring.size > MAX_VERTICES) {
            tol *= 1.5
            ring = simplifyRing(ring, tol)
        }
        if (ring.size < 3 || areaSqMeters(ring) < minAreaSqMeters) return null
        return ensureCounterClockwise(ring)
    }

    /** Douglas-Peucker over the open polyline [points]. Always keeps the first and last point. */
    fun simplify(points: List<GeoPoint>, toleranceMeters: Double): List<GeoPoint> {
        if (points.size < 3) return points
        val proj = Proj(points.map { it.lat }.average())
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        // Iterative stack, so a long stroke can't overflow the call stack.
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.lastIndex)
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            var maxDist = -1.0
            var index = -1
            for (i in first + 1 until last) {
                val d = pointToSegmentMeters(proj, points[i], points[first], points[last])
                if (d > maxDist) { maxDist = d; index = i }
            }
            if (index != -1 && maxDist > toleranceMeters) {
                keep[index] = true
                stack.addLast(first to index)
                stack.addLast(index to last)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** Simplifies a ring: as a polyline from the first point back to itself so
     * the closing edge is considered too, then drops that duplicate end. */
    private fun simplifyRing(ring: List<GeoPoint>, toleranceMeters: Double): List<GeoPoint> {
        val simplified = simplify(ring + ring.first(), toleranceMeters)
        return if (simplified.size > 1 && simplified.last() == simplified.first()) simplified.dropLast(1) else simplified
    }

    /** Cuts self-crossing loops out of [ring], keeping the largest simple loop; null if nothing usable remains. */
    fun makeSimple(ring: List<GeoPoint>): List<GeoPoint>? {
        var current = ring
        // Every pass strictly removes vertices, so this terminates.
        repeat(ring.size + 2) {
            if (current.size < 3) return null
            val cross = firstSelfIntersection(current) ?: return current
            val (i, j, x) = cross // edge i (p[i]..p[i+1]) crosses edge j (p[j]..p[j+1]), i < j
            val loop = listOf(x) + current.subList(i + 1, j + 1)
            val rest = current.subList(0, i + 1) + x + current.subList(j + 1, current.size)
            current = if (areaSqMeters(loop) >= areaSqMeters(rest)) loop else rest
        }
        return if (firstSelfIntersection(current) == null && current.size >= 3) current else null
    }

    fun isSimple(ring: List<GeoPoint>): Boolean = ring.size >= 3 && firstSelfIntersection(ring) == null

    /** True when the OPEN path through [points] (no closing edge) crosses over itself — used while corners are being placed. */
    fun openPathCrossesItself(points: List<GeoPoint>): Boolean {
        if (points.size < 4) return false
        val proj = Proj(points.map { it.lat }.average())
        for (i in 0 until points.size - 1) {
            for (j in i + 2 until points.size - 1) {
                if (intersection(proj, points[i], points[i + 1], points[j], points[j + 1]) != null) return true
            }
        }
        return false
    }

    private data class Crossing(val i: Int, val j: Int, val at: GeoPoint)

    private fun firstSelfIntersection(ring: List<GeoPoint>): Crossing? {
        val n = ring.size
        val proj = Proj(ring.map { it.lat }.average())
        for (i in 0 until n) {
            val a = ring[i]
            val b = ring[(i + 1) % n]
            for (j in i + 2 until n) {
                // Edge n-1 and edge 0 share the closing vertex — adjacent, not a crossing.
                if (i == 0 && j == n - 1) continue
                val c = ring[j]
                val d = ring[(j + 1) % n]
                intersection(proj, a, b, c, d)?.let { return Crossing(i, j, it) }
            }
        }
        return null
    }

    /** Proper intersection point of segments ab and cd (interiors crossing), or null. */
    private fun intersection(proj: Proj, a: GeoPoint, b: GeoPoint, c: GeoPoint, d: GeoPoint): GeoPoint? {
        val ax = proj.x(a); val ay = proj.y(a)
        val bx = proj.x(b); val by = proj.y(b)
        val cx = proj.x(c); val cy = proj.y(c)
        val dx = proj.x(d); val dy = proj.y(d)
        val denom = (bx - ax) * (dy - cy) - (by - ay) * (dx - cx)
        if (abs(denom) < 1e-12) return null // parallel / collinear
        val t = ((cx - ax) * (dy - cy) - (cy - ay) * (dx - cx)) / denom
        val u = ((cx - ax) * (by - ay) - (cy - ay) * (bx - ax)) / denom
        val eps = 1e-9
        if (t <= eps || t >= 1 - eps || u <= eps || u >= 1 - eps) return null
        return GeoPoint(a.lat + t * (b.lat - a.lat), a.lng + t * (b.lng - a.lng))
    }

    // ---- measures -----------------------------------------------------------

    /** Signed shoelace area in m² (positive = counter-clockwise). */
    private fun signedArea(ring: List<GeoPoint>): Double {
        val proj = Proj(ring.map { it.lat }.average())
        var sum = 0.0
        for (i in ring.indices) {
            val p = ring[i]
            val q = ring[(i + 1) % ring.size]
            sum += proj.x(p) * proj.y(q) - proj.x(q) * proj.y(p)
        }
        return sum / 2
    }

    fun areaSqMeters(ring: List<GeoPoint>): Double = if (ring.size < 3) 0.0 else abs(signedArea(ring))

    fun ensureCounterClockwise(ring: List<GeoPoint>): List<GeoPoint> = if (signedArea(ring) < 0) ring.reversed() else ring

    fun bounds(points: List<GeoPoint>): GeoBounds = GeoBounds(
        minLat = points.minOf { it.lat }, maxLat = points.maxOf { it.lat },
        minLng = points.minOf { it.lng }, maxLng = points.maxOf { it.lng },
    )

    /** A point guaranteed to be inside [ring]: its centroid when that is inside (it is for any convex-ish
     * shape), else the midpoint of the longest horizontal scan chord through the centre latitude. */
    fun interiorPoint(ring: List<GeoPoint>): GeoPoint {
        val proj = Proj(ring.map { it.lat }.average())
        val a = signedArea(ring)
        if (abs(a) > 1e-9) {
            var cx = 0.0
            var cy = 0.0
            for (i in ring.indices) {
                val p = ring[i]
                val q = ring[(i + 1) % ring.size]
                val cross = proj.x(p) * proj.y(q) - proj.x(q) * proj.y(p)
                cx += (proj.x(p) + proj.x(q)) * cross
                cy += (proj.y(p) + proj.y(q)) * cross
            }
            val c = GeoPoint(cy / (6 * a) / M_PER_DEG_LAT, cx / (6 * a) / proj.kx)
            if (contains(ring, c)) return c
        }
        // Scan-line fallback for concave shapes.
        val b = bounds(ring)
        val y = (b.minLat + b.maxLat) / 2
        val xs = ArrayList<Double>()
        for (i in ring.indices) {
            val p = ring[i]
            val q = ring[(i + 1) % ring.size]
            if ((p.lat > y) != (q.lat > y)) xs += p.lng + (y - p.lat) / (q.lat - p.lat) * (q.lng - p.lng)
        }
        xs.sort()
        var best = ring.first()
        var bestLen = -1.0
        for (k in 0 until xs.size - 1 step 2) {
            val len = xs[k + 1] - xs[k]
            if (len > bestLen) { bestLen = len; best = GeoPoint(y, (xs[k] + xs[k + 1]) / 2) }
        }
        return best
    }

    // ---- containment --------------------------------------------------------

    /** Even-odd ray casting. */
    fun contains(ring: List<GeoPoint>, p: GeoPoint): Boolean {
        if (ring.size < 3) return false
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.lng > p.lng) != (b.lng > p.lng)) {
                val latAtLng = a.lat + (p.lng - a.lng) / (b.lng - a.lng) * (b.lat - a.lat)
                if (p.lat < latAtLng) inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun distanceToRingMeters(ring: List<GeoPoint>, p: GeoPoint): Double {
        val proj = Proj(p.lat)
        var best = Double.MAX_VALUE
        for (i in ring.indices) {
            best = min(best, pointToSegmentMeters(proj, p, ring[i], ring[(i + 1) % ring.size]))
        }
        return best
    }

    /** Inside any of [allowed], or within [toleranceMeters] of one's edge — a finger tracing right along a boundary
     * easily lands a couple of meters over it. */
    fun isAllowed(allowed: List<List<GeoPoint>>, p: GeoPoint, toleranceMeters: Double): Boolean =
        allowed.any { ring -> contains(ring, p) || (toleranceMeters > 0 && distanceToRingMeters(ring, p) <= toleranceMeters) }

    /**
     * The parts of [ring]'s outline that lie outside every [allowed] ring, as
     * polylines (for drawing in red). The outline is sampled every ~[stepMeters]
     * (at most [MAX_SAMPLES_PER_EDGE] samples per edge), so a polygon whose
     * every sample is allowed is, for any practical purpose, inside the union.
     */
    fun outsideRuns(
        ring: List<GeoPoint>,
        allowed: List<List<GeoPoint>>,
        toleranceMeters: Double = 3.0,
        stepMeters: Double = 4.0,
    ): List<List<GeoPoint>> {
        // Sample the whole outline, then group consecutive outside samples into runs; each
        // run is padded with the allowed sample on either side so it touches the boundary.
        val samples = ArrayList<GeoPoint>()
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            val steps = min(MAX_SAMPLES_PER_EDGE, max(1, (distanceMeters(a, b) / stepMeters).toInt()))
            for (s in 0 until steps) {
                val t = s.toDouble() / steps
                samples += GeoPoint(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
            }
        }
        val outside = samples.map { !isAllowed(allowed, it, toleranceMeters) }
        val runs = ArrayList<List<GeoPoint>>()
        var i = 0
        while (i < samples.size) {
            if (!outside[i]) { i++; continue }
            val run = ArrayList<GeoPoint>()
            if (i > 0) run += samples[i - 1]
            while (i < samples.size && outside[i]) { run += samples[i]; i++ }
            if (i < samples.size) run += samples[i]
            if (run.size >= 2) runs += run
        }
        return runs
    }

    fun isWithin(ring: List<GeoPoint>, allowed: List<List<GeoPoint>>, toleranceMeters: Double = 3.0): Boolean =
        outsideRuns(ring, allowed, toleranceMeters).isEmpty()

    private const val MAX_SAMPLES_PER_EDGE = 500

    private fun pointToSegmentMeters(proj: Proj, p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val px = proj.x(p); val py = proj.y(p)
        val ax = proj.x(a); val ay = proj.y(a)
        val bx = proj.x(b); val by = proj.y(b)
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0.0) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    // ---- GeoJSON ------------------------------------------------------------

    /** GeoJSON `Polygon` string for [ring] (closed, counter-clockwise, `[lng, lat]` order). */
    fun polygonJson(ring: List<GeoPoint>): String {
        val ccw = ensureCounterClockwise(ring)
        val coords = JsonArray()
        (ccw + ccw.first()).forEach { coords.add(position(it)) }
        val outer = JsonArray().apply { add(coords) }
        return JsonObject().apply {
            addProperty("type", "Polygon")
            add("coordinates", outer)
        }.toString()
    }

    fun pointJson(p: GeoPoint): String = JsonObject().apply {
        addProperty("type", "Point")
        add("coordinates", position(p))
    }.toString()

    private fun position(p: GeoPoint) = JsonArray().apply { add(p.lng); add(p.lat) }

    /** The outer ring of a GeoJSON `Polygon` string without its repeated closing point, or null if it isn't one. */
    fun parsePolygon(json: String): List<GeoPoint>? = runCatching {
        val obj = JsonParser.parseString(json).asJsonObject
        if (obj.get("type")?.asString != "Polygon") return null
        val outer = obj.getAsJsonArray("coordinates").first().asJsonArray
        val ring = outer.map { pos -> pos.asJsonArray.let { GeoPoint(lat = it[1].asDouble, lng = it[0].asDouble) } }
        (if (ring.size > 1 && ring.first() == ring.last()) ring.dropLast(1) else ring).takeIf { it.size >= 3 }
    }.getOrNull()

    fun parsePoint(json: String): GeoPoint? = runCatching {
        val obj = JsonParser.parseString(json).asJsonObject
        if (obj.get("type")?.asString != "Point") return null
        val c = obj.getAsJsonArray("coordinates")
        GeoPoint(lat = c[1].asDouble, lng = c[0].asDouble)
    }.getOrNull()

    /** Cheap "did the shape change" test, to tell a color-only edit from a reshape. */
    fun sameRing(a: List<GeoPoint>, b: List<GeoPoint>): Boolean =
        a.size == b.size && a.indices.all { abs(a[it].lat - b[it].lat) < 1e-9 && abs(a[it].lng - b[it].lng) < 1e-9 }
}
