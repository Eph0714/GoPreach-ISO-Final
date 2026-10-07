package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.CoMonthStatus
import com.emfitsolutions.gopreach.data.model.CoReportStatus
import com.emfitsolutions.gopreach.data.model.ComparativeReport
import com.emfitsolutions.gopreach.data.model.ComparativeStatus
import com.emfitsolutions.gopreach.data.model.CongregationMonthlyStatistics
import com.emfitsolutions.gopreach.data.model.MeetingAttendance
import com.emfitsolutions.gopreach.data.model.MeetingType

/** The one status vocabulary the Report Submission folder shows for every kind of report. */
enum class SubmissionStatus(val label: String, val needsAction: Boolean) {
    NOT_STARTED("Not Started", true),
    DRAFT("Draft", true),
    READY("Ready for Submission", true),
    SUBMITTED("Submitted", false),
    RETURNED("Correction Required", true),
    RECEIVED("Received", false),

    /** A month still in progress: nothing is due yet. */
    OPEN("Open", false);

    val locked: Boolean get() = this == RECEIVED
}

enum class SubmissionKind(val title: String) {
    FIELD_SERVICE("Field Service Reports"), ATTENDANCE("Meeting Attendance"), COMPARATIVE("Comparative Reports")
}

/** One row of the folder. [periodStart]/[periodEnd] are first-of-month millis; [reportId] is set for kinds that have a record of their own. */
data class SubmissionItem(
    val kind: SubmissionKind,
    val congregationId: String,
    val title: String,
    val periodLabel: String,
    val periodStart: Long,
    val periodEnd: Long,
    val status: SubmissionStatus,
    val detail: String = "",
    /** The Circuit Overseer's reason / remark, when the report was returned. */
    val remark: String? = null,
    val updatedAt: Long = 0L,
    val reportId: String? = null,
)

data class SubmissionSummary(val total: Int, val needsAction: Int, val counts: Map<SubmissionStatus, Int>) {
    fun count(s: SubmissionStatus) = counts[s] ?: 0
}

/**
 * Works out, from what is already on the device, every report the active user has to prepare or has sent for a congregation inside
 * the chosen month range. Nothing is added by hand. Months after [monthStartNow] never appear (a future month cannot be reported).
 */
object ReportSubmissionPlanner {
    fun plan(
        congregationId: String,
        months: List<Long>,
        monthStartNow: Long,
        monthLabel: (Long) -> String,
        statuses: List<CoMonthStatus>,
        attendance: List<MeetingAttendance>,
        statistics: List<CongregationMonthlyStatistics>,
        comparative: List<ComparativeReport>,
        includeFieldService: Boolean = true,
        includeAttendance: Boolean = true,
        includeComparative: Boolean = true,
    ): List<SubmissionItem> {
        val usable = months.filter { it <= monthStartNow }
        val out = mutableListOf<SubmissionItem>()
        if (includeFieldService) usable.forEach { m ->
            val st = statuses.firstOrNull { it.congregationId == congregationId && it.periodMonth == m }
            val status = when (st?.status ?: CoReportStatus.NOT_SUBMITTED) {
                CoReportStatus.SUBMITTED -> SubmissionStatus.SUBMITTED
                CoReportStatus.RECEIVED -> SubmissionStatus.RECEIVED
                CoReportStatus.RETURNED -> SubmissionStatus.RETURNED
                CoReportStatus.NOT_SUBMITTED -> if (m < monthStartNow) SubmissionStatus.READY else SubmissionStatus.OPEN
            }
            out += SubmissionItem(
                SubmissionKind.FIELD_SERVICE, congregationId, "${monthLabel(m)} – Field Service Report", monthLabel(m), m, m, status,
                remark = st?.returnReason ?: st?.coRemarks, updatedAt = st?.updatedAt ?: 0L, reportId = "${congregationId}_$m",
            )
        }
        if (includeAttendance) usable.forEach { m ->
            val recs = attendance.filter { it.congregationId == congregationId && it.serviceMonth == m && !it.deleted }
            val mid = recs.count { it.meetingType == MeetingType.MIDWEEK }
            val wk = recs.count { it.meetingType == MeetingType.WEEKEND }
            val frozen = statistics.any { it.congregationId == congregationId && it.serviceMonth == m && it.isFrozen }
            val status = when {
                frozen -> SubmissionStatus.RECEIVED
                mid > 0 && wk > 0 -> SubmissionStatus.READY
                mid > 0 || wk > 0 -> SubmissionStatus.DRAFT
                m < monthStartNow -> SubmissionStatus.NOT_STARTED
                else -> SubmissionStatus.OPEN
            }
            out += SubmissionItem(
                SubmissionKind.ATTENDANCE, congregationId, "${monthLabel(m)} – Meeting Attendance", monthLabel(m), m, m, status,
                detail = "Midweek $mid recorded · Weekend $wk recorded" + if (mid == 0 || wk == 0) " · Missing" else "",
                updatedAt = recs.maxOfOrNull { it.updatedAt } ?: 0L,
            )
        }
        if (includeComparative && usable.isNotEmpty()) {
            val lo = usable.first()
            val hi = usable.last()
            comparative.filter { it.congregationId == congregationId && minOf(it.periodAStart, it.periodBStart) <= hi && maxOf(it.periodAEnd, it.periodBEnd) >= lo }
                .forEach { r ->
                    val status = when (r.status) {
                        ComparativeStatus.DRAFT -> SubmissionStatus.DRAFT
                        ComparativeStatus.SUBMITTED -> SubmissionStatus.SUBMITTED
                        ComparativeStatus.RETURNED -> SubmissionStatus.RETURNED
                        ComparativeStatus.RECEIVED -> SubmissionStatus.RECEIVED
                    }
                    out += SubmissionItem(
                        SubmissionKind.COMPARATIVE, congregationId, "${r.reportNumber.ifBlank { "Comparative Report" }} – Comparative Report",
                        "${monthLabel(minOf(r.periodAStart, r.periodBStart))} – ${monthLabel(maxOf(r.periodAEnd, r.periodBEnd))}",
                        minOf(r.periodAStart, r.periodBStart), maxOf(r.periodAEnd, r.periodBEnd), status,
                        remark = r.returnReason ?: r.currentCoRemarks, updatedAt = r.updatedAt, reportId = r.id,
                    )
                }
        }
        return out.sortedWith(compareBy<SubmissionItem> { it.kind.ordinal }.thenByDescending { it.periodStart })
    }

    fun summarize(items: List<SubmissionItem>) = SubmissionSummary(
        total = items.size, needsAction = items.count { it.status.needsAction },
        counts = items.groupingBy { it.status }.eachCount(),
    )

    /** Preset ranges as (first, last) first-of-month millis; [shift] moves a month by n using the platform's calendar. */
    fun preset(name: String, thisMonth: Long, januaryOfThisYear: Long, shift: (Long, Int) -> Long): Pair<Long, Long>? = when (name) {
        "Current Month" -> thisMonth to thisMonth
        "Previous Month" -> shift(thisMonth, -1).let { it to it }
        "Last 3 Months" -> shift(thisMonth, -2) to thisMonth
        "Last 6 Months" -> shift(thisMonth, -5) to thisMonth
        "Last 12 Months" -> shift(thisMonth, -11) to thisMonth
        "Current Year" -> januaryOfThisYear to thisMonth
        "Previous Year" -> shift(januaryOfThisYear, -12) to shift(januaryOfThisYear, -1)
        else -> null
    }

    val presetNames = listOf("Current Month", "Previous Month", "Last 3 Months", "Last 6 Months", "Last 12 Months", "Current Year", "Previous Year", "Custom Range")
}
