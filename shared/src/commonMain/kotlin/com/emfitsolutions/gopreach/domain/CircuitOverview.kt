package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.CoMonthStatus
import com.emfitsolutions.gopreach.data.model.CoReportEvent
import com.emfitsolutions.gopreach.data.model.CoReportStatus
import com.emfitsolutions.gopreach.data.model.ComparativeReport
import com.emfitsolutions.gopreach.data.model.ComparativeStatus
import com.emfitsolutions.gopreach.data.model.CongregationMonthlyStatistics
import com.emfitsolutions.gopreach.data.model.MeetingAttendance
import com.emfitsolutions.gopreach.data.model.MeetingType
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.ui.screens.circuit.CongregationPeopleSummary

/** Where a congregation's figures for the chosen month come from: today's records, a saved monthly snapshot, or nowhere (shown as "—"). */
enum class FigureSource { LIVE, SNAPSHOT, NONE }

/** The headline numbers of a congregation for one month. A null is "not available" — never zero. */
data class CongregationFigures(
    val publishers: Int?, val elders: Int?, val ministerialServants: Int?,
    val regularPioneers: Int?, val auxiliaryPioneers: Int?, val unbaptized: Int?,
    val fieldServiceReports: Int?,
    val source: FigureSource,
)

/** The standing of a congregation's Field Service Report for the chosen month. */
enum class ReportStanding(val label: String) {
    RECEIVED("Received"), SUBMITTED("Submitted"), RETURNED("Returned"), NOT_SUBMITTED("Not Submitted"), NO_DATA("No Data")
}

data class AttendanceStanding(
    val midweekRecorded: Int, val midweekExpected: Int, val weekendRecorded: Int, val weekendExpected: Int,
    val midweekAverage: Double?, val weekendAverage: Double?,
    /** True once the month is over, so missing meetings are a real gap rather than "not yet". */
    val monthOver: Boolean,
) {
    val midweekMissing: Int get() = if (monthOver) (midweekExpected - midweekRecorded).coerceAtLeast(0) else 0
    val weekendMissing: Int get() = if (monthOver) (weekendExpected - weekendRecorded).coerceAtLeast(0) else 0
    val missing: Int get() = midweekMissing + weekendMissing
}

data class CongregationCard(
    val congregationId: String,
    val name: String,
    val municipality: String,
    val province: String,
    val figures: CongregationFigures,
    val standing: ReportStanding,
    val returnReason: String?,
    val submittedAt: Long?,
    val receivedAt: Long?,
    val lastUpdated: Long,
    val attendance: AttendanceStanding,
    val attention: List<String>,
) {
    val needsAttention: Boolean get() = attention.isNotEmpty()
}

enum class ActionArea(val label: String) { FIELD_SERVICE("Field Service Report"), ATTENDANCE("Meeting Attendance"), COMPARATIVE("Comparative Report") }

data class ActionItem(
    val congregationId: String,
    val congregationName: String,
    val area: ActionArea,
    /** "Returned by CO", "Not Submitted", "Submitted – awaiting your review", "2 meetings missing". */
    val status: String,
    val reason: String? = null,
    /** True when the primary button should review a report rather than open the congregation. */
    val review: Boolean = false,
)

data class CircuitTotals(
    val congregations: Int,
    val publishers: Int?, val elders: Int?, val regularPioneers: Int?, val auxiliaryPioneers: Int?, val unbaptized: Int?,
    /** Change of the publisher total from the previous month's saved snapshots; null unless every congregation has both. */
    val publishersChange: Int?,
    /** How many congregations the figures above cover (a snapshot month may be partial). */
    val covered: Int,
)

data class CircuitOverview(
    val totals: CircuitTotals,
    val standingCounts: Map<ReportStanding, Int>,
    val cards: List<CongregationCard>,
    val actions: List<ActionItem>,
)

data class ActivityItem(val at: Long, val text: String, val congregationId: String)

enum class TrendMetric(val label: String) {
    PUBLISHERS("Publishers"), ELDERS("Elders"), REGULAR_PIONEERS("Regular Pioneers"), AUXILIARY_PIONEERS("Auxiliary Pioneers"),
    UNBAPTIZED("Unbaptized Publishers"), FIELD_SERVICE_REPORTS("Field Service Reports"),
    MIDWEEK_ATTENDANCE("Midweek Attendance (avg per congregation)"), WEEKEND_ATTENDANCE("Weekend Attendance (avg per congregation)");
}

/**
 * Builds the Circuit Overseer dashboard from what is already on the device. Figures of a past month come ONLY from the saved monthly
 * snapshots (never recalculated from today's records); the current month uses today's records; a month with neither is "—", never 0.
 */
object CircuitOverviewBuilder {

    fun build(
        month: Long,
        thisMonth: Long,
        previousMonth: Long,
        summaries: List<CongregationPeopleSummary>,
        statuses: List<CoMonthStatus>,
        attendance: List<MeetingAttendance>,
        statistics: List<CongregationMonthlyStatistics>,
        comparative: List<ComparativeReport>,
        expectedMeetings: (Long) -> Int,
    ): CircuitOverview {
        val monthOver = month < thisMonth
        val cards = summaries.map { s ->
            val id = s.congregation.id
            val snap = statistics.firstOrNull { it.congregationId == id && it.serviceMonth == month }
            val figures = when {
                month == thisMonth -> CongregationFigures(
                    s.publishers, s.elders, s.servants, s.counts[PublisherCategory.REGULAR_PIONEER], s.counts[PublisherCategory.AUXILIARY_PIONEER],
                    s.counts[PublisherCategory.UNBAPTIZED_PUBLISHER], snap?.fieldServiceReportCount, FigureSource.LIVE,
                )
                snap != null -> CongregationFigures(
                    snap.publisherCount, snap.elderCount, snap.ministerialServantCount, snap.regularPioneerCount, snap.auxiliaryPioneerCount,
                    snap.unbaptizedPublisherCount, snap.fieldServiceReportCount, FigureSource.SNAPSHOT,
                )
                else -> CongregationFigures(null, null, null, null, null, null, null, FigureSource.NONE)
            }
            val st = statuses.firstOrNull { it.congregationId == id && it.periodMonth == month }
            val standing = when (st?.status ?: CoReportStatus.NOT_SUBMITTED) {
                CoReportStatus.RECEIVED -> ReportStanding.RECEIVED
                CoReportStatus.SUBMITTED -> ReportStanding.SUBMITTED
                CoReportStatus.RETURNED -> ReportStanding.RETURNED
                CoReportStatus.NOT_SUBMITTED -> if (monthOver) ReportStanding.NOT_SUBMITTED else ReportStanding.NO_DATA
            }
            val recs = attendance.filter { it.congregationId == id && it.serviceMonth == month && !it.deleted }
            val mid = recs.filter { it.meetingType == MeetingType.MIDWEEK }
            val wk = recs.filter { it.meetingType == MeetingType.WEEKEND }
            val expected = expectedMeetings(month)
            val att = AttendanceStanding(
                mid.size, expected, wk.size, expected,
                snap?.averageMidweekAttendance ?: mid.takeIf { it.isNotEmpty() }?.let { r -> r.sumOf { it.officialAttendance } / r.size },
                snap?.averageWeekendAttendance ?: wk.takeIf { it.isNotEmpty() }?.let { r -> r.sumOf { it.officialAttendance } / r.size },
                monthOver,
            )
            val cmpReturned = comparative.any { it.congregationId == id && it.status == ComparativeStatus.RETURNED }
            val attention = buildList {
                if (standing == ReportStanding.RETURNED) add("Field Service Report returned")
                if (standing == ReportStanding.NOT_SUBMITTED) add("Field Service Report not submitted")
                if (standing == ReportStanding.SUBMITTED) add("Field Service Report awaiting your review")
                if (att.missing > 0) add("${att.missing} meeting${if (att.missing > 1) "s" else ""} missing")
                if (cmpReturned) add("Comparative Report returned")
            }
            CongregationCard(
                congregationId = id, name = s.congregation.name, municipality = s.congregation.cityMunicipality.orEmpty(), province = s.congregation.province.orEmpty(),
                figures = figures, standing = standing, returnReason = st?.returnReason ?: st?.coRemarks.takeIf { standing == ReportStanding.RETURNED },
                submittedAt = st?.submittedAt?.takeIf { it > 0L }, receivedAt = st?.receivedAt, lastUpdated = st?.updatedAt ?: 0L, attendance = att, attention = attention,
            )
        }

        val actions = cards.flatMap { c ->
            buildList {
                when (c.standing) {
                    ReportStanding.RETURNED -> add(ActionItem(c.congregationId, c.name, ActionArea.FIELD_SERVICE, "Returned by CO", c.returnReason, review = true))
                    ReportStanding.NOT_SUBMITTED -> add(ActionItem(c.congregationId, c.name, ActionArea.FIELD_SERVICE, "Not Submitted"))
                    ReportStanding.SUBMITTED -> add(ActionItem(c.congregationId, c.name, ActionArea.FIELD_SERVICE, "Submitted – awaiting your review", review = true))
                    else -> Unit
                }
                if (c.attendance.missing > 0) add(ActionItem(c.congregationId, c.name, ActionArea.ATTENDANCE, "${c.attendance.missing} meeting${if (c.attendance.missing > 1) "s" else ""} missing"))
                comparative.filter { it.congregationId == c.congregationId && it.status == ComparativeStatus.RETURNED }.forEach {
                    add(ActionItem(c.congregationId, c.name, ActionArea.COMPARATIVE, "Returned for correction", it.returnReason, review = true))
                }
            }
        }

        fun total(pick: (CongregationFigures) -> Int?): Int? =
            cards.mapNotNull { pick(it.figures) }.takeIf { it.isNotEmpty() }?.sum()
        val covered = cards.count { it.figures.source != FigureSource.NONE }
        val prev = summaries.map { s -> statistics.firstOrNull { it.congregationId == s.congregation.id && it.serviceMonth == previousMonth } }
        val change = total { it.publishers }?.let { now ->
            if (cards.isNotEmpty() && prev.all { it != null } && covered == cards.size) now - prev.sumOf { it!!.publisherCount } else null
        }
        val totals = CircuitTotals(
            congregations = cards.size,
            publishers = total { it.publishers }, elders = total { it.elders }, regularPioneers = total { it.regularPioneers },
            auxiliaryPioneers = total { it.auxiliaryPioneers }, unbaptized = total { it.unbaptized },
            publishersChange = change, covered = covered,
        )
        return CircuitOverview(totals, ReportStanding.entries.associateWith { s -> cards.count { it.standing == s } }, cards, actions)
    }

    /** The circuit's value of [metric] for each of [months] from the saved snapshots (null = no snapshot, a gap in the chart, never zero). */
    fun trend(metric: TrendMetric, months: List<Long>, congregationIds: Set<String>, statistics: List<CongregationMonthlyStatistics>): List<Double?> =
        months.map { m ->
            val snaps = statistics.filter { it.serviceMonth == m && it.congregationId in congregationIds }
            if (snaps.isEmpty()) null
            else when (metric) {
                TrendMetric.PUBLISHERS -> snaps.sumOf { it.publisherCount }.toDouble()
                TrendMetric.ELDERS -> snaps.sumOf { it.elderCount }.toDouble()
                TrendMetric.REGULAR_PIONEERS -> snaps.sumOf { it.regularPioneerCount }.toDouble()
                TrendMetric.AUXILIARY_PIONEERS -> snaps.sumOf { it.auxiliaryPioneerCount }.toDouble()
                TrendMetric.UNBAPTIZED -> snaps.sumOf { it.unbaptizedPublisherCount }.toDouble()
                TrendMetric.FIELD_SERVICE_REPORTS -> snaps.sumOf { it.fieldServiceReportCount }.toDouble()
                TrendMetric.MIDWEEK_ATTENDANCE -> snaps.mapNotNull { it.averageMidweekAttendance }.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size }
                TrendMetric.WEEKEND_ATTENDANCE -> snaps.mapNotNull { it.averageWeekendAttendance }.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size }
            }
        }

    /** The newest [limit] report events as plain sentences, e.g. "Solano – Report submitted to the Circuit Overseer". */
    fun activity(events: List<CoReportEvent>, names: Map<String, String>, limit: Int = 8): List<ActivityItem> =
        events.filter { it.congregationId in names }.sortedByDescending { it.at }.take(limit).map {
            ActivityItem(it.at, "${names[it.congregationId]} – ${it.action}" + (it.remarks?.takeIf { r -> r.isNotBlank() }?.let { r -> " (\"$r\")" } ?: ""), it.congregationId)
        }
}
