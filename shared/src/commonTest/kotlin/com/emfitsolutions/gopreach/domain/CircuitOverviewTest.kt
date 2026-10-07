package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.CoMonthStatus
import com.emfitsolutions.gopreach.data.model.CoReportEvent
import com.emfitsolutions.gopreach.data.model.CoReportStatus
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.CongregationMonthlyStatistics
import com.emfitsolutions.gopreach.data.model.MeetingAttendance
import com.emfitsolutions.gopreach.data.model.MeetingType
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.ui.screens.circuit.CongregationPeopleSummary
import com.emfitsolutions.gopreach.ui.screens.circuit.QuickAccessCounts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CircuitOverviewTest {
    // months are 1..: this month = 3
    private fun summary(id: String, pubs: Int) = CongregationPeopleSummary(
        congregation = Congregation(id = id, name = "Cong $id", cityMunicipality = "City"), publishers = pubs,
        counts = QuickAccessCounts(mapOf(PublisherCategory.REGULAR_PIONEER to 2)), elders = 3, servants = 1,
    )

    private fun snap(id: String, month: Long, pubs: Int) = CongregationMonthlyStatistics(
        id = "${id}_$month", congregationId = id, serviceMonth = month, publisherCount = pubs, elderCount = 4, fieldServiceReportCount = pubs - 1,
        averageMidweekAttendance = 80.0,
    )

    private fun build(
        month: Long = 3, statuses: List<CoMonthStatus> = emptyList(), att: List<MeetingAttendance> = emptyList(),
        stats: List<CongregationMonthlyStatistics> = emptyList(), summaries: List<CongregationPeopleSummary> = listOf(summary("a", 10), summary("b", 20)),
    ) = CircuitOverviewBuilder.build(month, thisMonth = 3, previousMonth = month - 1, summaries, statuses, att, stats, emptyList()) { 4 }

    @Test
    fun `the current month uses today's records and a past month only its saved snapshots`() {
        val now = build()
        assertEquals(30, now.totals.publishers); assertEquals(FigureSource.LIVE, now.cards[0].figures.source)
        val past = build(month = 2, stats = listOf(snap("a", 2, 8)))
        assertEquals(8, past.totals.publishers, "only the congregation with a snapshot counts; today's 10 is not used")
        assertEquals(FigureSource.NONE, past.cards[1].figures.source)
        assertNull(past.cards[1].figures.publishers, "no snapshot means unavailable, not zero")
        assertEquals(1, past.totals.covered)
    }

    @Test
    fun `a month with no snapshot at all has no totals instead of zeros`() {
        val t = build(month = 1).totals
        assertNull(t.publishers); assertNull(t.elders); assertNull(t.publishersChange)
    }

    @Test
    fun `publisher change needs the previous month for every congregation`() {
        val full = build(month = 2, stats = listOf(snap("a", 2, 12), snap("b", 2, 20), snap("a", 1, 10), snap("b", 1, 18)))
        assertEquals(4, full.totals.publishersChange)
        val partial = build(month = 2, stats = listOf(snap("a", 2, 12), snap("b", 2, 20), snap("a", 1, 10)))
        assertNull(partial.totals.publishersChange)
    }

    @Test
    fun `report standings and the action list`() {
        val o = build(
            month = 2,
            statuses = listOf(
                CoMonthStatus(congregationId = "a", periodMonth = 2, status = CoReportStatus.RETURNED, returnReason = "Check AP count"),
                CoMonthStatus(congregationId = "b", periodMonth = 2, status = CoReportStatus.SUBMITTED),
            ),
        )
        assertEquals(ReportStanding.RETURNED, o.cards[0].standing)
        assertEquals(1, o.standingCounts[ReportStanding.SUBMITTED])
        val returned = o.actions.first { it.congregationId == "a" && it.area == ActionArea.FIELD_SERVICE }
        assertEquals("Check AP count", returned.reason); assertTrue(returned.review)
        assertTrue(o.actions.any { it.congregationId == "b" && it.status.startsWith("Submitted") })
    }

    @Test
    fun `a past month with nothing submitted needs attention but the current month is just no data`() {
        assertEquals(ReportStanding.NOT_SUBMITTED, build(month = 2).cards[0].standing)
        assertEquals(ReportStanding.NO_DATA, build(month = 3).cards[0].standing)
        assertTrue(build(month = 3).actions.none { it.area == ActionArea.FIELD_SERVICE })
    }

    @Test
    fun `missing meetings are counted only once the month is over`() {
        val rec = { type: MeetingType, n: Int -> MeetingAttendance(id = "$type$n", congregationId = "a", meetingType = type, serviceMonth = 2, officialAttendance = 70.0) }
        val att = listOf(rec(MeetingType.MIDWEEK, 1), rec(MeetingType.MIDWEEK, 2), rec(MeetingType.WEEKEND, 1))
        val past = build(month = 2, att = att).cards[0].attendance
        assertEquals(2 + 3, past.missing) // midweek 2 of 4, weekend 1 of 4
        assertEquals(0, build(month = 3, att = att.map { it.copy(serviceMonth = 3) }).cards[0].attendance.missing)
    }

    @Test
    fun `trend leaves a gap for a month without snapshots`() {
        val stats = listOf(snap("a", 1, 10), snap("b", 1, 20), snap("a", 3, 12))
        val t = CircuitOverviewBuilder.trend(TrendMetric.PUBLISHERS, listOf(1, 2, 3), setOf("a", "b"), stats)
        assertEquals(30.0, t[0]); assertNull(t[1]); assertEquals(12.0, t[2])
        assertEquals(80.0, CircuitOverviewBuilder.trend(TrendMetric.MIDWEEK_ATTENDANCE, listOf(1), setOf("a", "b"), stats)[0])
    }

    @Test
    fun `activity shows the newest events of the circuit's congregations only`() {
        val ev = listOf(
            CoReportEvent(congregationId = "a", at = 5, action = "Report submitted"), CoReportEvent(congregationId = "x", at = 9, action = "Other circuit"),
            CoReportEvent(congregationId = "b", at = 7, action = "Report received", remarks = "Thanks"),
        )
        val a = CircuitOverviewBuilder.activity(ev, mapOf("a" to "Alpha", "b" to "Beta"))
        assertEquals(listOf(7L, 5L), a.map { it.at })
        assertTrue(a[0].text.startsWith("Beta – Report received"))
        assertFalse(a.any { it.text.contains("Other") })
    }
}
