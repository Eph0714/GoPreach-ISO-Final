package com.emfitsolutions.gopreach.domain.map

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoordinateParserTest {
    private fun near(a: Double, b: Double) = abs(a - b) < 1e-4

    @Test fun decimalPair() {
        val c = CoordinateParser.findCandidates("Lat/Long 14.5995, 120.9842 taken today").single()
        assertTrue(near(c.lat, 14.5995) && near(c.lng, 120.9842))
        assertEquals(CoordinateFormat.DECIMAL, c.format)
        assertEquals(CoordinateSource.VISIBLE_TEXT, c.source)
    }

    @Test fun decimalWithHemispheres() {
        val c = CoordinateParser.findCandidates("14.5995° N, 120.9842° E").single()
        assertTrue(near(c.lat, 14.5995) && near(c.lng, 120.9842))
        val s = CoordinateParser.findCandidates("33.8688° S 151.2093° E").single()
        assertTrue(near(s.lat, -33.8688) && near(s.lng, 151.2093))
    }

    @Test fun degreesMinutesSeconds() {
        val c = CoordinateParser.findCandidates("14°35'58.2\"N 120°59'03\"E").single()
        assertEquals(CoordinateFormat.DEGREES_MINUTES_SECONDS, c.format)
        assertTrue(near(c.lat, 14 + 35 / 60.0 + 58.2 / 3600) && near(c.lng, 120 + 59 / 60.0 + 3 / 3600.0))
    }

    @Test fun degreesDecimalMinutes() {
        val c = CoordinateParser.findCandidates("14°35.97'N 120°59.05'E").single()
        assertEquals(CoordinateFormat.DEGREES_DECIMAL_MINUTES, c.format)
        assertTrue(near(c.lat, 14 + 35.97 / 60) && near(c.lng, 120 + 59.05 / 60))
    }

    @Test fun longitudeFirstIsOrientedByItsLetters() {
        val c = CoordinateParser.findCandidates("120.9842° E, 14.5995° N").single()
        assertTrue(near(c.lat, 14.5995) && near(c.lng, 120.9842))
    }

    @Test fun severalCandidatesAreAllReturned() {
        assertEquals(2, CoordinateParser.findCandidates("Site A 14.5995, 120.9842\nSite B 10.3157, 123.8854").size)
    }

    @Test fun nothingIsInvented() {
        assertTrue(CoordinateParser.findCandidates("").isEmpty())
        assertTrue(CoordinateParser.findCandidates("Barangay 12, Purok 3, phone 0917 555 1234").isEmpty())
        assertTrue(CoordinateParser.findCandidates("14.5995").isEmpty(), "a single number is not a coordinate")
        assertTrue(CoordinateParser.findCandidates("95.1234, 120.9842").isEmpty(), "latitude beyond 90")
        assertTrue(CoordinateParser.findCandidates("14°75'10\"N 120°10'10\"E").isEmpty(), "minutes beyond 59")
        assertTrue(CoordinateParser.findCandidates("14.5995° N, 10.1234° N").isEmpty(), "two latitudes")
    }
}
