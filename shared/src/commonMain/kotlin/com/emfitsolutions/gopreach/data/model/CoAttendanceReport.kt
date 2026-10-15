package com.emfitsolutions.gopreach.data.model

import com.emfitsolutions.gopreach.platform.DocumentId

/** One weekly meeting of a sent attendance snapshot, frozen exactly as it was recorded when the congregation sent the month. */
@kotlinx.serialization.Serializable
data class CoAttendanceRow(
    val meetingType: MeetingType = MeetingType.MIDWEEK,
    /** Start of the meeting's day (epoch millis). */
    val meetingDate: Long = 0L,
    /** The original headcounts, in order (Midweek: 3 parts, Weekend: 2 parts). */
    val parts: List<Int> = emptyList(),
    val calculatedAverage: Double = 0.0,
    val officialAttendance: Double = 0.0,
    val roundingMode: AttendanceRounding = AttendanceRounding.ROUNDED,
    val remarks: String = "",
)

/**
 * The Circuit Overseer's own copy of one sent Meeting Attendance month (`coAttendanceReports/{congregationId}_{periodMonth}_{send number}`).
 * It follows exactly the same rules as [CoReceivedReport]: written once with the send, never edited, a corrected re-send is a new
 * send number, and the overseer reads only these copies, never the congregation's working records. The month's workflow status
 * lives in `coAttendanceMonthStatus` (a [CoMonthStatus]) and its audit trail in `coAttendanceReportEvents`.
 */
@kotlinx.serialization.Serializable
data class CoAttendanceSnapshot(
    @field:DocumentId val id: String = "",
    val congregationId: String = "",
    val congregationName: String = "",
    /** First-of-month epoch millis of the service month. */
    val periodMonth: Long = 0L,
    val version: Int = 1,
    val submittedAt: Long = 0L,
    val submittedByPersonId: String = "",
    val submittedByName: String = "",
    val preparedByRole: String = "",
    val rows: List<CoAttendanceRow> = emptyList(),
    val assignedCoPersonId: String? = null,
    val schemaVersion: Int = 1,
) {
    val midweek: List<CoAttendanceRow> get() = rows.filter { it.meetingType == MeetingType.MIDWEEK }.sortedBy { it.meetingDate }
    val weekend: List<CoAttendanceRow> get() = rows.filter { it.meetingType == MeetingType.WEEKEND }.sortedBy { it.meetingDate }

    /** Mean of the official weekly figures of [type], null when that meeting was not recorded (never 0). */
    fun average(type: MeetingType): Double? =
        rows.filter { it.meetingType == type }.map { it.officialAttendance }.let { if (it.isEmpty()) null else it.sum() / it.size }
}

/** The status that applies to a taken-back or not-yet-sent month: the Circuit Overseer sees a month only while it is sent. */
fun coAttendanceId(congregationId: String, periodMonth: Long) = coReportId(congregationId, periodMonth)
