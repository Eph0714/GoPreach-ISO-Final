package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.CoMonthStatus
import com.emfitsolutions.gopreach.data.model.CoReportStatus
import com.emfitsolutions.gopreach.data.model.ComparativeReport
import com.emfitsolutions.gopreach.data.model.ComparativeStatus
import com.emfitsolutions.gopreach.data.model.MeetingAttendance
import com.emfitsolutions.gopreach.data.model.MeetingType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportSubmissionPlannerTest {
    private val label = { m: Long -> "M$m" }
    private fun plan(
        statuses: List<CoMonthStatus> = emptyList(), att: List<MeetingAttendance> = emptyList(), cmp: List<ComparativeReport> = emptyList(),
        months: List<Long> = listOf(1, 2, 3, 4),
    ) = ReportSubmissionPlanner.plan("c", months, monthStartNow = 3, monthLabel = label, statuses = statuses, attendance = att, statistics = emptyList(), comparative = cmp)

    @Test
    fun `future months never appear and the month in progress is open, not due`() {
        val items = plan().filter { it.kind == SubmissionKind.FIELD_SERVICE }
        assertEquals(listOf(3L, 2L, 1L), items.map { it.periodStart })
        assertEquals(SubmissionStatus.OPEN, items.first { it.periodStart == 3L }.status)
        assertEquals(SubmissionStatus.READY, items.first { it.periodStart == 2L }.status)
    }

    @Test
    fun `server statuses map onto the folder statuses and a returned month carries its reason`() {
        val items = plan(
            statuses = listOf(
                CoMonthStatus(congregationId = "c", periodMonth = 1, status = CoReportStatus.RETURNED, returnReason = "Check hours"),
                CoMonthStatus(congregationId = "c", periodMonth = 2, status = CoReportStatus.RECEIVED),
            ),
        ).filter { it.kind == SubmissionKind.FIELD_SERVICE }
        assertEquals(SubmissionStatus.RETURNED, items.first { it.periodStart == 1L }.status)
        assertEquals("Check hours", items.first { it.periodStart == 1L }.remark)
        assertTrue(items.first { it.periodStart == 2L }.status.locked)
    }

    @Test
    fun `attendance is complete only with both meeting types and missing is not zero`() {
        val rec = { type: MeetingType, m: Long -> MeetingAttendance(id = "$type$m", congregationId = "c", meetingType = type, serviceMonth = m) }
        val items = plan(att = listOf(rec(MeetingType.MIDWEEK, 1), rec(MeetingType.WEEKEND, 1), rec(MeetingType.MIDWEEK, 2))).filter { it.kind == SubmissionKind.ATTENDANCE }
        assertEquals(SubmissionStatus.READY, items.first { it.periodStart == 1L }.status)
        assertEquals(SubmissionStatus.DRAFT, items.first { it.periodStart == 2L }.status)
        assertTrue(items.first { it.periodStart == 2L }.detail.contains("Missing"))
        assertEquals(SubmissionStatus.OPEN, items.first { it.periodStart == 3L }.status)
    }

    @Test
    fun `comparative reports inside the range show with their own status`() {
        val r = ComparativeReport(
            id = "x", congregationId = "c", reportNumber = "CR-1", periodAStart = 1, periodAEnd = 1, periodBStart = 2, periodBEnd = 2,
            status = ComparativeStatus.RETURNED, returnReason = "Fix",
        )
        val items = plan(cmp = listOf(r, r.copy(id = "y", congregationId = "other"))).filter { it.kind == SubmissionKind.COMPARATIVE }
        assertEquals(1, items.size)
        assertEquals(SubmissionStatus.RETURNED, items[0].status)
        assertEquals("Fix", items[0].remark)
    }

    @Test
    fun `summary counts and what needs action`() {
        val s = ReportSubmissionPlanner.summarize(plan())
        assertEquals(s.total, s.counts.values.sum())
        assertEquals(s.count(SubmissionStatus.READY) + s.count(SubmissionStatus.NOT_STARTED), s.needsAction)
    }

    @Test
    fun `presets`() {
        val shift = { m: Long, n: Int -> m + n }
        assertEquals(10L to 12L, ReportSubmissionPlanner.preset("Last 3 Months", 12, 1, shift))
        assertEquals(1L to 12L, ReportSubmissionPlanner.preset("Current Year", 12, 1, shift))
        assertNull(ReportSubmissionPlanner.preset("Custom Range", 12, 1, shift))
        assertNotNull(ReportSubmissionPlanner.preset("Previous Month", 12, 1, shift))
    }
}
