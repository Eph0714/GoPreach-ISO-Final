package com.emfitsolutions.gopreach.data.model

import com.emfitsolutions.gopreach.platform.DocumentId

/** The two weekly congregation meetings. */
@kotlinx.serialization.Serializable
enum class MeetingType {
    MIDWEEK, WEEKEND;

    val label: String get() = if (this == MIDWEEK) "Midweek" else "Weekend"

    /** How many attendance counts this meeting is made of (Midweek: 3 parts, Weekend: 2 parts). */
    val partCount: Int get() = if (this == MIDWEEK) 3 else 2
}

/**
 * How a meeting's calculated average becomes its official attendance — a congregation setting.
 * [ROUNDED]: nearest whole number (81.33 → 81, 98.50 → 99). [EXACT]: the average itself, kept to two decimals (98.50).
 */
@kotlinx.serialization.Serializable
enum class AttendanceRounding {
    ROUNDED, EXACT;

    val label: String get() = if (this == ROUNDED) "Round to Nearest Whole Number" else "Keep Exact Average"
}

/**
 * One weekly meeting's attendance, a permanent historical record (`meetingAttendance/{congregationId}_{type}_{meetingDate}` — the
 * id makes a second record for the same congregation, meeting type and date impossible). The counts are the original headcounts;
 * [calculatedAverage] is the mathematical mean of them and [officialAttendance] that mean after the congregation's
 * [roundingMode] was applied — both are stored, plus the mode used, so later setting changes never rewrite history by themselves.
 * A deleted record stays in place with [deleted] = true so its audit trail survives and the date can be re-entered.
 */
@kotlinx.serialization.Serializable
data class MeetingAttendance(
    @field:DocumentId val id: String = "",
    val congregationId: String = "",
    val meetingType: MeetingType = MeetingType.MIDWEEK,
    /** Start of the meeting's day (epoch millis). */
    val meetingDate: Long = 0L,
    /** Start of the month the meeting belongs to. */
    val serviceMonth: Long = 0L,
    // Midweek parts
    val treasuresAttendance: Int? = null,
    val applyYourselfAttendance: Int? = null,
    val livingAsChristiansAttendance: Int? = null,
    // Weekend parts
    val publicMeetingAttendance: Int? = null,
    val watchtowerStudyAttendance: Int? = null,
    val calculatedAverage: Double = 0.0,
    val officialAttendance: Double = 0.0,
    val roundingMode: AttendanceRounding = AttendanceRounding.ROUNDED,
    val remarks: String? = null,
    val deleted: Boolean = false,
    val createdBy: String = "",
    val createdAt: Long = 0L,
    val updatedBy: String = "",
    val updatedAt: Long = 0L,
) {
    /** The counts that apply to this meeting type, in order. */
    val parts: List<Int> get() = when (meetingType) {
        MeetingType.MIDWEEK -> listOfNotNull(treasuresAttendance, applyYourselfAttendance, livingAsChristiansAttendance)
        MeetingType.WEEKEND -> listOfNotNull(publicMeetingAttendance, watchtowerStudyAttendance)
    }
}

/** A congregation's attendance setting (`meetingAttendanceSettings/{congregationId}`); no document = the default, [AttendanceRounding.ROUNDED]. */
@kotlinx.serialization.Serializable
data class MeetingAttendanceSettings(
    @field:DocumentId val id: String = "",
    val congregationId: String = "",
    val roundingMode: AttendanceRounding = AttendanceRounding.ROUNDED,
    val updatedBy: String = "",
    val updatedAt: Long = 0L,
)

/** One permanent audit line for attendance (`meetingAttendanceEvents/{id}`): what changed, from what, to what, by whom — never edited, survives deletion. */
@kotlinx.serialization.Serializable
data class MeetingAttendanceEvent(
    @field:DocumentId val id: String = "",
    val congregationId: String = "",
    /** The attendance record's id, or the congregation id for a setting change. */
    val recordId: String = "",
    val action: String = "",
    val at: Long = 0L,
    val userId: String = "",
    val userName: String = "",
    val userRole: String = "",
    val previousValues: String? = null,
    val newValues: String? = null,
    val reason: String? = null,
)

/**
 * The historical monthly statistics of one congregation (`congregationMonthlyStatistics/{congregationId}_{serviceMonth}`) — the
 * archive the Circuit Overseer's comparative report reads. It is a snapshot of the numbers as they were when the month's Field
 * Service Report was submitted (not recalculated from today's publishers), is refreshed while the report is Submitted or Returned,
 * and is frozen once the Circuit Overseer marks it Received. The congregation's actual report stays the single source of truth;
 * [sourceReportId] points at it.
 */
@kotlinx.serialization.Serializable
data class CongregationMonthlyStatistics(
    @field:DocumentId val id: String = "",
    val congregationId: String = "",
    val serviceMonth: Long = 0L,
    val fieldServiceReportCount: Int = 0,
    val elderCount: Int = 0,
    val ministerialServantCount: Int = 0,
    val publisherCount: Int = 0,
    val auxiliaryPioneerCount: Int = 0,
    val regularPioneerCount: Int = 0,
    val unbaptizedPublisherCount: Int = 0,
    /** Null when no meeting of that type was recorded that month (never 0). */
    val averageMidweekAttendance: Double? = null,
    val averageWeekendAttendance: Double? = null,
    val midweekMeetingsRecorded: Int = 0,
    val weekendMeetingsRecorded: Int = 0,
    val midweekMeetingsMissing: Int = 0,
    val weekendMeetingsMissing: Int = 0,
    val attendanceRoundingMode: AttendanceRounding = AttendanceRounding.ROUNDED,
    val sourceReportId: String = "",
    /** The report's workflow status: SUBMITTED, RETURNED, RECEIVED (frozen) ... */
    val snapshotStatus: String = "SUBMITTED",
    val submittedDate: Long? = null,
    val receivedDate: Long? = null,
    val createdBy: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** Once received the numbers are history and no longer change. */
    val isFrozen: Boolean get() = snapshotStatus == "RECEIVED"
}
