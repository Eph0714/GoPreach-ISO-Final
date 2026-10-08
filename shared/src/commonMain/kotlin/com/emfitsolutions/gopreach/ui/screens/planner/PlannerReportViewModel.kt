package com.emfitsolutions.gopreach.ui.screens.planner

import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.model.ReportStatus
import com.emfitsolutions.gopreach.domain.PublisherReportService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The Planner's Preview Report and Send as Text — the same [PublisherReportService]
 * calculation the Monthly Report screen uses, recalculated from the latest records
 * every time, so the three can never show different figures.
 */
class PlannerReportViewModel(
    private val publisherReportService: PublisherReportService,
    private val monthlyReportRepository: com.emfitsolutions.gopreach.data.repository.MonthlyReportRepository,
    private val coReports: com.emfitsolutions.gopreach.data.repository.CoFieldServiceReportRepository,
) : ViewModel() {
    /** The congregation's months already sent to the Circuit Overseer (Submitted or Received) — closed in My Planner for everyone in it. */
    fun congregationLockedMonths(congregationId: String?): Flow<Set<Long>> =
        coReports.observeStatuses().map { list ->
            if (congregationId == null) emptySet() else list.filter { it.congregationId == congregationId && it.isLocked }.map { it.periodMonth }.toSet()
        }

    fun reportText(publisherPersonId: String, periodMonth: Long): Flow<String> =
        publisherReportService.observe(publisherPersonId, flowOf(periodMonth)).map { it.toReport().toText() }

    /** Every month of this Publisher's that has a submitted report — drives [PlannerLock]. */
    fun submittedMonths(publisherPersonId: String): Flow<Map<Long, Double>> =
        monthlyReportRepository.observeSubmittedMonths(publisherPersonId)

    /** True once the month's report has been submitted (or corrected/posted) — Send Report is then closed. */
    fun isSubmitted(publisherPersonId: String, periodMonth: Long): Flow<Boolean> =
        publisherReportService.observe(publisherPersonId, flowOf(periodMonth)).map {
            when (it.existingReport?.status) {
                ReportStatus.SUBMITTED, ReportStatus.CORRECTED, ReportStatus.POSTED -> true
                else -> false
            }
        }
}
