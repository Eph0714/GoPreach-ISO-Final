package com.emfitsolutions.gopreach.ui.screens.attendance

import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.model.CoMonthStatus
import com.emfitsolutions.gopreach.data.model.ComparativeReport
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.CongregationMonthlyStatistics
import com.emfitsolutions.gopreach.data.model.MeetingAttendance
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.repository.CoFieldServiceReportRepository
import com.emfitsolutions.gopreach.data.repository.ComparativeReportRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.MeetingAttendanceRepository
import com.emfitsolutions.gopreach.data.repository.ReportSubmissionPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Everything the Report Submission folder works from, all already on the device (so it also works offline). */
data class SubmissionSources(
    val statuses: List<CoMonthStatus> = emptyList(),
    val attendance: List<MeetingAttendance> = emptyList(),
    val statistics: List<CongregationMonthlyStatistics> = emptyList(),
    val comparative: List<ComparativeReport> = emptyList(),
)

class ReportSubmissionViewModel(
    private val coReports: CoFieldServiceReportRepository,
    private val attendance: MeetingAttendanceRepository,
    private val comparative: ComparativeReportRepository,
    congregationRepository: CongregationRepository,
    val preferences: ReportSubmissionPreferences,
) : ViewModel() {

    val congregations: Flow<List<Congregation>> = congregationRepository.observeAll()
        .map { list -> list.filter { it.status == RecordStatus.ACTIVE }.sortedBy { it.name } }

    fun sources(congregationId: String?): Flow<SubmissionSources> = combine(
        coReports.observeStatuses(), attendance.observeAll(), attendance.observeStatistics(), comparative.observeReports(),
    ) { st, att, stats, cmp ->
        if (congregationId == null) SubmissionSources()
        else SubmissionSources(
            st.filter { it.congregationId == congregationId }, att.filter { it.congregationId == congregationId },
            stats.filter { it.congregationId == congregationId }, cmp.filter { it.congregationId == congregationId },
        )
    }
}
