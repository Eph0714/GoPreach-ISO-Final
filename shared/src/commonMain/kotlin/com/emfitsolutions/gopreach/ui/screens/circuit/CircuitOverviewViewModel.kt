package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.model.CoMonthStatus
import com.emfitsolutions.gopreach.data.model.CoReportEvent
import com.emfitsolutions.gopreach.data.model.ComparativeReport
import com.emfitsolutions.gopreach.data.model.CongregationMonthlyStatistics
import com.emfitsolutions.gopreach.data.model.MeetingAttendance
import com.emfitsolutions.gopreach.data.repository.CoFieldServiceReportRepository
import com.emfitsolutions.gopreach.data.repository.ComparativeReportRepository
import com.emfitsolutions.gopreach.data.repository.MeetingAttendanceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Everything on the device the Circuit Overseer dashboard is computed from (the screen narrows it to the congregations in scope). */
data class OverviewSources(
    val statuses: List<CoMonthStatus> = emptyList(),
    val events: List<CoReportEvent> = emptyList(),
    val attendance: List<MeetingAttendance> = emptyList(),
    val statistics: List<CongregationMonthlyStatistics> = emptyList(),
    val comparative: List<ComparativeReport> = emptyList(),
)

class CircuitOverviewViewModel(
    coReports: CoFieldServiceReportRepository,
    attendance: MeetingAttendanceRepository,
    comparative: ComparativeReportRepository,
) : ViewModel() {
    val sources: Flow<OverviewSources> = combine(
        coReports.observeStatuses(), coReports.observeEvents(), attendance.observeAll(), attendance.observeStatistics(), comparative.observeReports(),
    ) { st, ev, att, stats, cmp -> OverviewSources(st, ev, att, stats, cmp) }
}
