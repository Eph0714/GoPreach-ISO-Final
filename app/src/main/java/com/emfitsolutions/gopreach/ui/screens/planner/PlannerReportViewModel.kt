package com.emfitsolutions.gopreach.ui.screens.planner

import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.model.ReportStatus
import com.emfitsolutions.gopreach.domain.PublisherReportService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * The Planner's Preview Report and Send as Text — the same [PublisherReportService]
 * calculation the Monthly Report screen uses, recalculated from the latest records
 * every time, so the three can never show different figures.
 */
@HiltViewModel
class PlannerReportViewModel @Inject constructor(
    private val publisherReportService: PublisherReportService,
    private val monthlyReportRepository: com.emfitsolutions.gopreach.data.repository.MonthlyReportRepository,
) : ViewModel() {
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
