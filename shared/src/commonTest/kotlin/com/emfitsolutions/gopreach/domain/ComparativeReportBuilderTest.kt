package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.AttendanceRounding
import com.emfitsolutions.gopreach.data.model.ComparativeStatus
import com.emfitsolutions.gopreach.data.model.CongregationMonthlyStatistics
import com.emfitsolutions.gopreach.data.model.comparativeReportId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComparativeReportBuilderTest {
    // months are just 1, 2, 3 ... in these tests
    private val next = { m: Long -> m + 1 }
    private val label = { m: Long -> "M$m" }

    private fun snap(month: Long, reports: Int, elders: Int, pubs: Int, mid: Double?, midN: Int, wk: Double?, wkN: Int) =
        CongregationMonthlyStatistics(
            id = "c_$month", congregationId = "c", serviceMonth = month, fieldServiceReportCount = reports, elderCount = elders, publisherCount = pubs,
            averageMidweekAttendance = mid, midweekMeetingsRecorded = midN, averageWeekendAttendance = wk, weekendMeetingsRecorded = wkN,
        )

    @Test
    fun `periods must be ordered, not in the future and not overlapping`() {
        assertNull(ComparativePeriods.validate(1, 4, 5, 8, monthStartNow = 10))
        assertEquals("Period A: the From month cannot be after the To month.", ComparativePeriods.validate(4, 1, 5, 8, 10))
        assertEquals("Period B: the From month cannot be after the To month.", ComparativePeriods.validate(1, 4, 8, 5, 10))
        assertEquals("Future months cannot be selected.", ComparativePeriods.validate(1, 4, 5, 11, 10))
        assertEquals("Period A and Period B must not overlap.", ComparativePeriods.validate(1, 5, 5, 8, 10))
        assertNull(ComparativePeriods.validate(6, 8, 1, 5, 10), "Period B may come before Period A as long as they do not overlap")
    }

    @Test
    fun `the report is built only from saved snapshots, with missing months shown and not counted`() {
        val stats = listOf(snap(1, 50, 8, 100, 80.0, 4, 100.0, 4), snap(2, 55, 8, 104, 90.0, 4, 110.0, 4), snap(5, 60, 9, 112, 100.0, 4, 120.0, 4))
        val (s, ids) = ComparativeReportBuilder.build("Solano", stats, 1, 3, 4, 6, AttendanceRounding.ROUNDED, "Sec", "now", label, next)
        assertEquals("M1 – M3", s.periodA); assertEquals("M4 – M6", s.periodB)
        val reports = s.comparison.first { it[0].startsWith("Field Service Reports") }
        assertEquals("105", reports[1]); assertEquals("60", reports[2]); assertEquals("-45", reports[3]); assertEquals("-42.9%", reports[4])
        val elders = s.comparison.first { it[0] == "Elders" }
        assertEquals("8", elders[1], "headcounts use the ending count of the period"); assertEquals("9", elders[2])
        val midweek = s.comparison.first { it[0] == "Midweek Attendance" }
        assertEquals("85", midweek[1]); assertEquals("100", midweek[2]) // (80*4+90*4)/8 = 85
        assertEquals(listOf("M3", "M4", "M6"), s.missingMonths)
        assertEquals(listOf("c_1", "c_2", "c_5"), ids)
        assertTrue(s.monthly.first { it[0] == "Elders" }.contains("—"), "a month without a snapshot is a dash, not zero")
        assertEquals(7, s.monthly.first().size, "statistic column + 6 months")
    }

    @Test
    fun `an exact-average congregation keeps two decimals`() {
        val stats = listOf(snap(1, 10, 1, 10, 81.3333, 1, 98.5, 1), snap(4, 10, 1, 10, 83.0, 1, 100.0, 1))
        val (s, _) = ComparativeReportBuilder.build("X", stats, 1, 1, 4, 4, AttendanceRounding.EXACT, "u", "n", label, next)
        val midweek = s.comparison.first { it[0] == "Midweek Attendance" }
        assertEquals("81.33", midweek[1]); assertEquals("83.00", midweek[2]); assertEquals("+1.67", midweek[3])
        assertEquals("98.50", s.comparison.first { it[0] == "Weekend Attendance" }[1])
    }

    @Test
    fun `no data gives dashes and a clear missing count`() {
        val (s, ids) = ComparativeReportBuilder.build("X", emptyList(), 1, 2, 3, 4, AttendanceRounding.ROUNDED, "u", "n", label, next)
        assertTrue(ids.isEmpty())
        assertEquals(listOf("M1", "M2", "M3", "M4"), s.missingMonths)
        assertEquals(listOf("Publishers", "—", "—", "—", "—"), s.comparison.first { it[0] == "Publishers" })
    }

    @Test
    fun `the same congregation and periods always give the same id, a different period another`() {
        assertEquals(comparativeReportId("c", 1, 4, 5, 8), comparativeReportId("c", 1, 4, 5, 8))
        assertFalse(comparativeReportId("c", 1, 4, 5, 8) == comparativeReportId("c", 1, 4, 5, 9))
        assertFalse(comparativeReportId("c", 1, 4, 5, 8) == comparativeReportId("d", 1, 4, 5, 8))
    }

    @Test
    fun `only drafts and returned reports are editable by the congregation`() {
        assertTrue(ComparativeStatus.DRAFT.congregationCanEdit); assertTrue(ComparativeStatus.RETURNED.congregationCanEdit)
        assertFalse(ComparativeStatus.SUBMITTED.congregationCanEdit); assertFalse(ComparativeStatus.RECEIVED.congregationCanEdit)
        assertTrue(ComparativeStatus.SUBMITTED.locked); assertTrue(ComparativeStatus.RECEIVED.locked)
        assertNotNull(ComparativeStatus.RETURNED.notice)
    }
}
