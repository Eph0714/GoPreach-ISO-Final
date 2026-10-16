package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.CoAttendanceRow
import com.emfitsolutions.gopreach.data.model.CoAttendanceSnapshot
import com.emfitsolutions.gopreach.data.model.MeetingType
import com.emfitsolutions.gopreach.domain.AttendanceComparative.MonthInput
import com.emfitsolutions.gopreach.domain.AcceptedComparative.MonthState
import com.emfitsolutions.gopreach.domain.AcceptedComparative.Period
import com.emfitsolutions.gopreach.domain.CircuitAttendanceComparative.CongregationInput
import com.emfitsolutions.gopreach.domain.CircuitAttendanceComparative.Trend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CircuitAttendanceComparativeTest {
    private val next = { m: Long -> m + 1 }
    private val label = { m: Long -> "M$m" }

    private fun month(m: Long, midweek: List<Double>, weekend: List<Double> = emptyList(), state: MonthState = MonthState.ACCEPTED) = MonthInput(
        m, state,
        if (state == MonthState.ACCEPTED) CoAttendanceSnapshot(
            id = "s$m", periodMonth = m,
            rows = midweek.map { CoAttendanceRow(MeetingType.MIDWEEK, m, officialAttendance = it) } + weekend.map { CoAttendanceRow(MeetingType.WEEKEND, m, officialAttendance = it) },
        ) else null,
    )

    private val a = CongregationInput("a", "Alpha", listOf(month(1, listOf(40.0, 60.0)), month(2, listOf(50.0)), month(3, listOf(80.0), listOf(100.0))))
    private val b = CongregationInput("b", "Beta", listOf(month(1, listOf(20.0)), month(3, listOf(10.0), state = MonthState.AWAITING)))

    private fun build() = CircuitAttendanceComparative.build(listOf(b, a), Period(1, 2), Period(3, 3), next, label)

    @Test
    fun eachCongregationIsComparedOnItsOwnAveragesAndMissingMonthsAreNotZero() {
        val r = build()
        val alphaMid = r.rows.first { it.name == "Alpha" && it.meetingType == MeetingType.MIDWEEK }
        assertEquals(50.0, alphaMid.figure.first)   // (40 + 60 + 50) / 3 recorded meetings
        assertEquals(80.0, alphaMid.figure.second)
        assertEquals(30.0, alphaMid.figure.difference)
        assertEquals(60.0, alphaMid.figure.percentChange)
        assertEquals(Trend.INCREASED, alphaMid.figure.trend)
        val betaMid = r.rows.first { it.name == "Beta" && it.meetingType == MeetingType.MIDWEEK }
        assertEquals(20.0, betaMid.figure.first)
        assertNull(betaMid.figure.second)           // month 3 only awaits receipt: no figure, not zero
        assertNull(betaMid.figure.percentChange)
        assertEquals(Trend.NA, betaMid.figure.trend)
        assertEquals("Alpha", r.rows.first().name, "congregations are listed alphabetically")
    }

    @Test
    fun circuitFiguresPoolEveryMeetingAndTotalsUseOnlyCongregationsWithBothPeriods() {
        val r = build()
        val pooledMid = r.circuitRows.first { it.name.startsWith("Entire circuit") && it.meetingType == MeetingType.MIDWEEK }
        assertEquals((40.0 + 60.0 + 50.0 + 20.0) / 4, pooledMid.figure.first)
        assertEquals(80.0, pooledMid.figure.second)
        val total = r.circuitRows.first { it.name.startsWith("Circuit total") && it.meetingType == MeetingType.MIDWEEK }
        assertEquals(50.0, total.figure.first)      // only Alpha has a figure in both periods
        assertEquals(80.0, total.figure.second)
        assertEquals(1, total.firstAvailable)
    }

    @Test
    fun zeroBaselineGivesNotApplicablePercentAndTheGridUsesRealPeriodNames() {
        assertNull(CircuitAttendanceComparative.Figure(0.0, 5.0).percentChange)
        assertEquals("N/A", CircuitAttendanceComparative.percent(null))
        assertEquals("+60.0%", CircuitAttendanceComparative.percent(60.0))
        val grid = CircuitAttendanceComparative.table(build())
        assertEquals("M1 - M2", grid[0][2])
        assertEquals("M3", grid[0][3])
        assertTrue(grid.any { it[0] == "Alpha" && it[6].contains("Increased") })
    }
}
