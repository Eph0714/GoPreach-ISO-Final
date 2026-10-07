package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.MeetingAttendance
import com.emfitsolutions.gopreach.data.model.MeetingType

/**
 * The mandatory end-of-report Summary of the Meeting Attendance report, built from exactly the records shown. A meeting that was not
 * recorded is Missing and is never counted as zero in an average; when the number of expected meetings is not known (a search or a
 * partial filter is active) the Missing figure is Not Applicable rather than a guess. First row = headings (Metric | Value).
 */
object AttendanceReportSummary {
    fun rows(
        records: List<MeetingAttendance>,
        types: List<MeetingType>,
        /** Meetings expected per type for the period shown, or null when that cannot be known. */
        expected: Map<MeetingType, Int>?,
        format: (Double?) -> String,
    ): List<List<String>> {
        val out = mutableListOf(listOf("Metric", "Value"))
        types.forEach { type ->
            val mine = records.filter { it.meetingType == type && !it.deleted }
            val s = AttendanceSummaries.of(mine, expected?.get(type) ?: mine.size)
            val label = type.label
            out += listOf("$label Meetings Recorded", s.recorded.toString())
            out += listOf("$label Meetings Missing", if (expected?.get(type) == null) "Not applicable" else s.missing.toString())
            out += listOf("Average $label Attendance", format(s.average))
            out += listOf("Highest $label Attendance", format(s.highest))
            out += listOf("Lowest $label Attendance", format(s.lowest))
        }
        return out
    }
}
