package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.MeetingAttendance
import com.emfitsolutions.gopreach.data.model.MeetingType
import kotlin.test.Test
import kotlin.test.assertEquals

class AttendanceReportSummaryTest {
    private fun rec(type: MeetingType, official: Double, deleted: Boolean = false) =
        MeetingAttendance(id = "$type$official", congregationId = "c", meetingType = type, officialAttendance = official, deleted = deleted)

    private val fmt = { v: Double? -> v?.toString() ?: "—" }
    private fun value(rows: List<List<String>>, metric: String) = rows.first { it[0] == metric }[1]

    @Test
    fun `missing meetings are counted as missing and never as zero in the average`() {
        val records = listOf(rec(MeetingType.MIDWEEK, 80.0), rec(MeetingType.MIDWEEK, 100.0), rec(MeetingType.MIDWEEK, 5.0, deleted = true))
        val s = AttendanceReportSummary.rows(records, MeetingType.entries, mapOf(MeetingType.MIDWEEK to 4, MeetingType.WEEKEND to 4), fmt)
        assertEquals("2", value(s, "Midweek Meetings Recorded"))
        assertEquals("2", value(s, "Midweek Meetings Missing"))
        assertEquals("90.0", value(s, "Average Midweek Attendance"))
        assertEquals("100.0", value(s, "Highest Midweek Attendance"))
        assertEquals("80.0", value(s, "Lowest Midweek Attendance"))
        assertEquals("0", value(s, "Weekend Meetings Recorded"))
        assertEquals("4", value(s, "Weekend Meetings Missing"))
        assertEquals("—", value(s, "Average Weekend Attendance"))
    }

    @Test
    fun `without a known expectation missing is not applicable`() {
        val s = AttendanceReportSummary.rows(listOf(rec(MeetingType.WEEKEND, 120.0)), listOf(MeetingType.WEEKEND), null, fmt)
        assertEquals("Not applicable", value(s, "Weekend Meetings Missing"))
        assertEquals("1", value(s, "Weekend Meetings Recorded"))
    }

    @Test
    fun `a single type filter summarises only that type`() {
        val s = AttendanceReportSummary.rows(listOf(rec(MeetingType.MIDWEEK, 70.0)), listOf(MeetingType.MIDWEEK), null, fmt)
        assertEquals(6, s.size)
    }
}
