package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.CongregationMonthlyStatistics
import com.emfitsolutions.gopreach.data.model.MeetingType
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.model.coReportId
import com.emfitsolutions.gopreach.domain.AttendanceSummaries
import com.emfitsolutions.gopreach.platform.nowMillis
import kotlinx.coroutines.flow.first

/**
 * Builds the historical monthly statistics snapshot of a congregation as it is NOW — called when the month's Field Service Report is
 * submitted to the Circuit Overseer. The figures come from the congregation's own live records (its publishers' roles and categories,
 * the submitted monthly reports, the month's weekly attendance), and are then stored and kept: later categories changes never alter them.
 */
class CongregationStatisticsBuilder(
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val monthlyReportRepository: MonthlyReportRepository,
    private val attendanceRepository: MeetingAttendanceRepository,
) {
    private val elderRoles = setOf(AdminRole.COORDINATOR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY, AdminRole.REGULAR_ELDER)

    suspend fun build(congregationId: String, serviceMonth: Long, actorPersonId: String, existing: CongregationMonthlyStatistics?): CongregationMonthlyStatistics {
        val active = roleAssignmentRepository.observeAll().first().filter { it.status == RoleAssignmentStatus.ACTIVE && it.congregationId == congregationId }
        val publishers = active
            .mapNotNull { a -> (a.resolvedRoleTypeOrNull() as? RoleType.Publisher)?.category?.takeIf { it != PublisherCategory.REMOVED_PUBLISHER }?.let { a.personId to it } }
            .distinctBy { it.first }
        val leaders = active.mapNotNull { a -> (a.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role?.let { it to a.personId } }
        val reports = monthlyReportRepository.observeAll().first()
            .filter { it.congregationId == congregationId && it.periodMonth == serviceMonth && it.status.countsAsSubmitted }
            .map { it.publisherPersonId }.toSet().size

        val attendance = attendanceRepository.observeAll().first().filter { it.congregationId == congregationId && it.serviceMonth == serviceMonth && !it.deleted }
        val expected = AttendanceSummaries.expectedMeetings(serviceMonth)
        val midweek = AttendanceSummaries.of(attendance.filter { it.meetingType == MeetingType.MIDWEEK }, expected)
        val weekend = AttendanceSummaries.of(attendance.filter { it.meetingType == MeetingType.WEEKEND }, expected)
        val now = nowMillis()
        return CongregationMonthlyStatistics(
            id = attendanceRepository.statisticsIdFor(congregationId, serviceMonth),
            congregationId = congregationId,
            serviceMonth = serviceMonth,
            fieldServiceReportCount = reports,
            elderCount = leaders.filter { it.first in elderRoles }.map { it.second }.toSet().size,
            ministerialServantCount = leaders.filter { it.first == AdminRole.MINISTERIAL_SERVANT }.map { it.second }.toSet().size,
            publisherCount = publishers.size,
            auxiliaryPioneerCount = publishers.count { it.second == PublisherCategory.AUXILIARY_PIONEER },
            regularPioneerCount = publishers.count { it.second == PublisherCategory.REGULAR_PIONEER },
            unbaptizedPublisherCount = publishers.count { it.second == PublisherCategory.UNBAPTIZED_PUBLISHER },
            averageMidweekAttendance = midweek.average,
            averageWeekendAttendance = weekend.average,
            midweekMeetingsRecorded = midweek.recorded,
            weekendMeetingsRecorded = weekend.recorded,
            midweekMeetingsMissing = midweek.missing,
            weekendMeetingsMissing = weekend.missing,
            attendanceRoundingMode = attendanceRepository.roundingFor(congregationId),
            sourceReportId = coReportId(congregationId, serviceMonth),
            snapshotStatus = "SUBMITTED",
            submittedDate = now,
            receivedDate = null,
            createdBy = existing?.createdBy?.ifBlank { actorPersonId } ?: actorPersonId,
            createdAt = existing?.createdAt?.takeIf { it > 0 } ?: now,
            updatedAt = now,
        )
    }
}
