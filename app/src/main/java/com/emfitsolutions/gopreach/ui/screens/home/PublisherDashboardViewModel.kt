package com.emfitsolutions.gopreach.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.MonthlyReport
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.data.repository.MonthlyReportRepository
import com.emfitsolutions.gopreach.data.repository.PreachingTimeRecordRepository
import com.emfitsolutions.gopreach.data.repository.PublisherDashboardVisibilityRepository
import com.emfitsolutions.gopreach.data.repository.VisitRepository
import com.emfitsolutions.gopreach.domain.DateRangeStore
import com.emfitsolutions.gopreach.domain.MinistryStatisticsService
import com.emfitsolutions.gopreach.ui.components.DateRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** "Add Special Pioneer publisher status category" — Special Pioneer gets
 * exactly Regular Pioneer's treatment everywhere this shared helper is used
 * (PublisherHomeScreen, ConsolidatedReportViewModel, ...), without touching
 * Auxiliary Pioneer's own existing behavior (already grouped in here too). */
fun isPioneerCategory(category: PublisherCategory?): Boolean =
    category == PublisherCategory.REGULAR_PIONEER ||
        category == PublisherCategory.SPECIAL_PIONEER ||
        category == PublisherCategory.AUXILIARY_PIONEER

/** "Complete Publisher Dashboard" spec §1/§21 — everything the square stat
 * cards need, computed the same way (unique-person counts, summed hours,
 * date-range-scoped) regardless of which cards a given [PublisherCategory]
 * actually shows. */
data class PublisherDashboardStats(
    /** Spec §7/§18 — COUNT DISTINCT Bible Study person, not visit rows. A
     * "Bible Study" is an [com.emfitsolutions.gopreach.data.model
     * .InterestedPerson] whose [PipelineStage] is [PipelineStage.BIBLE_STUDY]
     * with at least one qualifying [com.emfitsolutions.gopreach.data.model
     * .Visit] in the selected range — see
     * [com.emfitsolutions.gopreach.domain.MinistryStatisticsService], the
     * single shared definition every Bible Study count in this app now uses. */
    val bibleStudiesCount: Int = 0,
    /** Spec §8/§10 — COUNT DISTINCT Return Visit person: every
     * [com.emfitsolutions.gopreach.data.model.Visit] in range, grouped by
     * its owning Interested Person, counted once each no matter how many
     * separate visits that person had. */
    val returnVisitsCount: Int = 0,
    /** Spec §11/§13 — SUM(Hour Consumed) from
     * [com.emfitsolutions.gopreach.data.model.PreachingTimeRecord] in range. */
    val preachingHours: Double = 0.0,
    /** Spec §19 — true if at least one SUBMITTED MonthlyReport overlapping
     * the range has `participatedInPreaching == true`; never hard-coded. */
    val attendedPreaching: Boolean = false,
)

/**
 * Backs the role-based square stat cards on [PublisherHomeScreen] (spec
 * §1-§32). Shares [DateRangeStore] with the rest of the app (spec §9: "the
 * existing GoPreach date-range system") so picking a range here and on the
 * Admin/Coordinator Dashboard don't silently disagree for a dual-role
 * account.
 */
@HiltViewModel
class PublisherDashboardViewModel @Inject constructor(
    private val interestedPersonRepository: InterestedPersonRepository,
    private val visitRepository: VisitRepository,
    private val preachingTimeRecordRepository: PreachingTimeRecordRepository,
    private val monthlyReportRepository: MonthlyReportRepository,
    private val dateRangeStore: DateRangeStore,
    private val dashboardVisibilityRepository: PublisherDashboardVisibilityRepository,
) : ViewModel() {

    val dateRange: StateFlow<DateRange> = dateRangeStore.range
    fun setDateRange(range: DateRange) = dateRangeStore.set(range)

    /** "Hide the dashboard (add show or hide dashboard)" — a per-device
     * toggle for the Date Range + stat-cards section on the Publisher Main
     * Form. */
    val showDashboard: StateFlow<Boolean> = dashboardVisibilityRepository.showDashboard
    fun setShowDashboard(value: Boolean) = dashboardVisibilityRepository.setShowDashboard(value)

    /** The collection-group listener for this Publisher's own Return Visits
     * (see [VisitRepository.startRemoteSyncForPublisher]) — started once the
     * screen knows its own personId, same on-demand pattern as
     * [com.emfitsolutions.gopreach.ui.screens.interestedpeople
     * .InterestedPeopleViewModel.startVisitSync]. */
    fun startVisitSync(publisherPersonId: String): Flow<Unit> = visitRepository.startRemoteSyncForPublisher(publisherPersonId)

    fun statsFor(publisherPersonId: String): StateFlow<PublisherDashboardStats> = combine(
        interestedPersonRepository.observeAll(),
        visitRepository.observeAllForPublisher(publisherPersonId),
        preachingTimeRecordRepository.observeForPublisher(publisherPersonId),
        monthlyReportRepository.observeAll(),
        dateRangeStore.range,
    ) { allPeople, visits, preachingRecords, allReports, range ->
        // Bug fix: was `status == ReportStatus.SUBMITTED` — a Posted report
        // (see MonthlyReport.isSubmittedOrPosted's doc comment) still
        // counts toward this Publisher's own dashboard totals; it used to
        // silently drop out of their own stats the moment it was Posted.
        val reportsInRange = allReports.filter {
            it.publisherPersonId == publisherPersonId && it.isSubmittedOrPosted && range.overlapsMonth(it.periodMonth)
        }
        PublisherDashboardStats(
            // Centralized in MinistryStatisticsService (spec §1-§15/§51 —
            // "one unique person = one count," never a raw visit-row count,
            // never duplicated per-screen) — both counts key off qualifying
            // Visit dates, not InterestedPerson.stageEnteredAt, matching
            // MonthlyReportCalculator's existing Bible Study definition.
            bibleStudiesCount = MinistryStatisticsService.uniqueVisitedPersons(
                publisherPersonId, allPeople, visits, PipelineStage.BIBLE_STUDY, range,
            ),
            returnVisitsCount = MinistryStatisticsService.uniqueVisitedPersons(
                publisherPersonId, allPeople, visits, PipelineStage.RETURN_VISIT, range,
            ),
            preachingHours = preachingRecords
                .filter { it.status == com.emfitsolutions.gopreach.data.model.RecordStatus.ACTIVE && range.contains(it.date) }
                .sumOf { it.hoursConsumed },
            attendedPreaching = reportsInRange.any { it.participatedInPreaching == true },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PublisherDashboardStats())
}
