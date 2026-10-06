package com.emfitsolutions.gopreach.ui.screens.monthlyreport

import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.model.MonthlyReport
import com.emfitsolutions.gopreach.data.model.ReportStatus
import com.emfitsolutions.gopreach.data.repository.MonthlyReportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * "Allow the publisher to see all his submitted Report record" — a read-only
 * history of every [MonthlyReport] this Publisher has ever filed, not just
 * whichever one or two months [MonthlyReportScreen]'s own editing form
 * exposes (current + previous month only, by design — see
 * [MonthlyReportViewModel.availableMonths]). Every status is shown here,
 * Draft included, so a Publisher can see something they started but never
 * actually submitted, same as they'd remember it.
 */
@HiltViewModel
class MySubmittedReportsViewModel @Inject constructor(
    private val monthlyReportRepository: MonthlyReportRepository,
) : ViewModel() {

    /** Newest period first — a Publisher looking back at their own history
     * cares most about what they just filed. */
    fun reportsFor(publisherPersonId: String): Flow<List<MonthlyReport>> =
        monthlyReportRepository.observeAll().map { list ->
            list.filter { it.publisherPersonId == publisherPersonId }
                .sortedByDescending { it.periodMonth }
        }
}

/** "Draft"/"Submitted"/"Posted" — same wording
 * [com.emfitsolutions.gopreach.ui.screens.publisherreports
 * .ManagePublisherReportsScreen] already uses for the Admin-side equivalent,
 * kept consistent here so the same report reads the same way on both
 * screens. */
fun ReportStatus.label(): String = when (this) {
    ReportStatus.DRAFT -> "Draft"
    ReportStatus.SUBMITTED -> "Submitted"
    ReportStatus.POSTED -> "Posted"
    ReportStatus.RETURNED -> "Returned for Correction"
    ReportStatus.CORRECTED -> "Corrected"
}
