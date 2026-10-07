package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.AttendanceRounding
import com.emfitsolutions.gopreach.data.model.CongregationMonthlyStatistics
import com.emfitsolutions.gopreach.data.model.MeetingAttendance
import com.emfitsolutions.gopreach.data.model.MeetingType
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttendanceCalculatorTest {
    private fun near(expected: Double, actual: Double?, eps: Double = 0.0001) =
        assertTrue(actual != null && kotlin.math.abs(expected - actual) < eps, "expected $expected but was $actual")

    @Test
    fun `midweek average is taken from the original counts and rounded last`() {
        val avg = AttendanceCalculator.average(listOf(79, 80, 85))
        near(81.3333, avg)
        assertEquals(81.0, AttendanceCalculator.official(avg, AttendanceRounding.ROUNDED))
        near(81.33, AttendanceCalculator.official(avg, AttendanceRounding.EXACT))
    }

    @Test
    fun `weekend 98 point 5 rounds up to 99 and halves always go up`() {
        val avg = AttendanceCalculator.average(listOf(92, 105))
        near(98.5, avg)
        assertEquals(99.0, AttendanceCalculator.official(avg, AttendanceRounding.ROUNDED))
        near(98.5, AttendanceCalculator.official(avg, AttendanceRounding.EXACT))
        mapOf(81.33 to 81.0, 81.49 to 81.0, 81.50 to 82.0, 98.20 to 98.0, 98.49 to 98.0, 98.80 to 99.0).forEach { (v, r) ->
            assertEquals(r, AttendanceCalculator.roundHalfUp(v), "round $v")
        }
    }

    @Test
    fun `display keeps two decimals only for exact averages`() {
        assertEquals("99", AttendanceCalculator.display(99.0, AttendanceRounding.ROUNDED))
        assertEquals("98.50", AttendanceCalculator.display(98.5, AttendanceRounding.EXACT))
        assertEquals("83.00", AttendanceCalculator.display(83.0, AttendanceRounding.EXACT))
        assertEquals("81.33", AttendanceCalculator.display(81.3333, AttendanceRounding.EXACT))
    }

    @Test
    fun `only whole non-negative numbers are accepted`() {
        assertEquals(0, AttendanceCalculator.parseCount("0"))
        assertEquals(85, AttendanceCalculator.parseCount(" 85 "))
        for (bad in listOf("", "-3", "4.5", "4,5", "abc", "12a", "+5", " ")) assertNull(AttendanceCalculator.parseCount(bad), "'$bad' is invalid")
    }

    @Test
    fun `building a record stores the counts, the mean, the official value and the mode used`() {
        val base = MeetingAttendance(congregationId = "c", meetingType = MeetingType.MIDWEEK, meetingDate = 1)
        val mid = AttendanceCalculator.build(base, listOf(79, 80, 85), AttendanceRounding.ROUNDED)
        assertEquals(79, mid.treasuresAttendance); assertEquals(80, mid.applyYourselfAttendance); assertEquals(85, mid.livingAsChristiansAttendance)
        assertNull(mid.publicMeetingAttendance)
        assertEquals(81.0, mid.officialAttendance); assertEquals(AttendanceRounding.ROUNDED, mid.roundingMode)
        near(81.3333, mid.calculatedAverage)
        val wk = AttendanceCalculator.build(base.copy(meetingType = MeetingType.WEEKEND), listOf(92, 105), AttendanceRounding.EXACT)
        assertEquals(92, wk.publicMeetingAttendance); assertNull(wk.treasuresAttendance)
        assertEquals(98.5, wk.officialAttendance); assertEquals(AttendanceRounding.EXACT, wk.roundingMode)
        // changing the setting later re-applies it from the ORIGINAL counts
        assertEquals(99.0, AttendanceCalculator.recalculated(wk, AttendanceRounding.ROUNDED).officialAttendance)
    }

    @Test
    fun `a missing meeting is never a zero`() {
        val recs = listOf(81.0, 83.0, 86.0, 82.0).map { MeetingAttendance(officialAttendance = it) }
        val s = AttendanceSummaries.of(recs, expected = 5)
        assertEquals(4, s.recorded); assertEquals(5, s.expected); assertEquals(1, s.missing)
        near(83.0, s.average); assertEquals(86.0, s.highest); assertEquals(81.0, s.lowest)
        val none = AttendanceSummaries.of(emptyList(), expected = 4)
        assertNull(none.average); assertEquals(4, none.missing)
        assertEquals(1, AttendanceSummaries.of(recs + MeetingAttendance(officialAttendance = 1.0, deleted = true), 5).missing, "deleted records do not count")
    }

    @Test
    fun `a month expects four or five meetings by the week's Thursday`() {
        val tz = TimeZone.UTC
        fun expected(y: Int, m: Int) = AttendanceSummaries.expectedMeetings(LocalDate(y, m, 1).atStartOfDayIn(tz).toEpochMilliseconds(), tz)
        assertEquals(5, expected(2026, 10)) // Thursdays: 1, 8, 15, 22, 29
        assertEquals(4, expected(2026, 9)) // 3, 10, 17, 24
        assertEquals(4, expected(2026, 2)) // 5, 12, 19, 26
    }

    private fun snap(month: Long, reports: Int, elders: Int, pubs: Int, mid: Double?, midN: Int, wk: Double?, wkN: Int) =
        CongregationMonthlyStatistics(
            serviceMonth = month, fieldServiceReportCount = reports, elderCount = elders, publisherCount = pubs,
            averageMidweekAttendance = mid, midweekMeetingsRecorded = midN, averageWeekendAttendance = wk, weekendMeetingsRecorded = wkN,
        )

    @Test
    fun `a period totals reports, takes the ending headcount and averages the weekly attendance`() {
        val p = ComparativeStatistics.period(
            listOf(snap(1, 50, 8, 100, 80.0, 4, 100.0, 4), snap(2, 55, 8, 104, 90.0, 1, null, 0), snap(3, 60, 9, 105, null, 0, 120.0, 2)),
            monthCount = 4,
        )
        assertEquals(165.0, p.value[ComparativeStat.FIELD_SERVICE_REPORTS])
        assertEquals(9.0, p.value[ComparativeStat.ELDERS], "headcounts are not summed")
        assertEquals(8.0, p.beginning[ComparativeStat.ELDERS])
        near(8.3333, p.monthlyAverage[ComparativeStat.ELDERS])
        near(82.0, p.value[ComparativeStat.MIDWEEK_ATTENDANCE]) // (80*4 + 90*1) / 5 weekly figures
        near((100.0 * 4 + 120.0 * 2) / 6, p.value[ComparativeStat.WEEKEND_ATTENDANCE])
        assertEquals(3, p.monthsWithData); assertEquals(1, p.monthsMissing)
    }

    @Test
    fun `no data gives no figure rather than zero, and differences are guarded`() {
        val p = ComparativeStatistics.period(emptyList(), monthCount = 3)
        assertNull(p.value[ComparativeStat.PUBLISHERS]); assertNull(p.value[ComparativeStat.MIDWEEK_ATTENDANCE])
        assertEquals(3, p.monthsMissing)
        assertEquals(7.0, ComparativeStatistics.difference(96.0, 103.0))
        near(7.2917, ComparativeStatistics.percentChange(96.0, 103.0))
        assertNull(ComparativeStatistics.percentChange(0.0, 5.0)); assertNull(ComparativeStatistics.difference(null, 5.0))
        near(62.5, ComparativeStatistics.percentChange(520.0, 845.0))
        assertNotNull(ComparativeStatistics.percentChange(8.0, 9.0))
    }
}
