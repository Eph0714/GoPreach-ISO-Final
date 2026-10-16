package com.emfitsolutions.gopreach.domain.weather

import com.emfitsolutions.gopreach.data.weather.PagasaAlertService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfficialAlertTest {
    private val cap = """<?xml version="1.0"?><alert xmlns="urn:oasis:names:tc:emergency:cap:1.2"><identifier>a1</identifier><sender>PAGASA-DOST</sender><sent>2026-10-16T05:43:04+08:00</sent><status>Actual</status><msgType>Update</msgType><scope>Public</scope><info><category>Met</category><event>General Flood Advisory (Moderate)</event><urgency>Future</urgency><severity>Moderate</severity><certainty>Possible</certainty><expires>2026-10-16T17:43:04+08:00</expires><headline>General Flood Advisory</headline><description>Rain &amp; storms&#xD;
more</description><instruction>Take care.</instruction><web>http://www.pagasa.dost.gov.ph/x</web><area><areaDesc>Camiguin</areaDesc><polygon>9.0,124.0 9.0,125.0 8.0,125.0 8.0,124.0 9.0,124.0</polygon></area></info></alert>"""

    @Test
    fun capDocumentIsReadAndMatchedToAPlaceByArea() {
        val a = assertNotNull(CapParser.parse(cap))
        assertEquals("General Flood Advisory (Moderate)", a.event)
        assertEquals(2, a.severityRank)
        assertEquals("Rain & storms\nmore", a.description)
        assertEquals(listOf("Camiguin"), a.areaNames)
        assertTrue(a.covers(8.5, 124.5))
        assertFalse(a.covers(10.0, 124.5))
        assertTrue(a.isActive(a.sentMillis!!))
        assertFalse(a.isActive(a.expiresMillis!! + 1))
    }

    @Test
    fun cancelledTestAndGarbageAlertsAreIgnored() {
        assertNull(CapParser.parse(cap.replace("<msgType>Update", "<msgType>Cancel")))
        assertNull(CapParser.parse(cap.replace("<status>Actual", "<status>Test")))
        assertNull(CapParser.parse("<html></html>"))
    }

    @Test
    fun feedEntriesYieldCapLinks() {
        val feed = """<feed><entry><id>x</id><updated>2026-10-16T05:43:04+08:00</updated><link type="application/cap+xml" href="https://h/a.cap"/></entry><entry><id>y</id></entry></feed>"""
        val e = PagasaAlertService.parseFeed(feed).single()
        assertEquals("https://h/a.cap", e.url)
        assertNotNull(e.updatedMillis)
    }
}
