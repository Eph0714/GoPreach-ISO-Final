package com.emfitsolutions.gopreach.ui.screens.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.MonthlyReport
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.PreachingTimeRecord
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.model.Visit
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.data.repository.MonthlyReportRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.PreachingTimeRecordRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.VisitRepository
import com.emfitsolutions.gopreach.domain.DateRangeStore
import com.emfitsolutions.gopreach.domain.MinistryStatisticsService
import com.emfitsolutions.gopreach.ui.components.DateRange
import com.emfitsolutions.gopreach.ui.screens.home.isPioneerCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** One publisher's row on the Consolidated Monthly Report — every figure
 * uses the exact same logic as the Publisher Dashboard (spec: "the same
 * calculations must be used throughout... Publisher Dashboard, Pioneer
 * Dashboard, Pioneer Reports..."), just computed for someone else's records
 * by an authorized viewer instead of the publisher's own. */
data class PublisherReportEntry(
    val person: Person,
    val category: PublisherCategory,
    val congregationId: String,
    val congregationName: String,
    val bibleStudiesCount: Int,
    val returnVisitsCount: Int,
    /** Pioneers only (spec item 1) — 0.0 for a Regular/Unbaptized Publisher. */
    val preachingHours: Double,
    /** Regular/Unbaptized Publishers only (spec item 1) — null for a
     * Pioneer, and null (not false) if no submitted report exists for the
     * range at all, so "No" is never shown for someone who simply hasn't
     * reported yet. */
    val participatedInMinistry: Boolean?,
)

data class ConsolidatedReportUiState(
    val entries: List<PublisherReportEntry> = emptyList(),
    /** Every congregation in the viewer's own scope — for Super-Admin's
     * "All Congregations" vs. one-at-a-time picker (same convention as the
     * Dashboard). Congregation-scoped roles (Service Overseer, Coordinator
     * Elder, Admin) only ever have exactly one here. */
    val congregationsInScope: List<Congregation> = emptyList(),
    val selectedCongregationId: String? = null,
    val dateRange: DateRange = DateRange.thisMonth(),
    val isLoading: Boolean = true,
) {
    val visibleEntries: List<PublisherReportEntry>
        get() = when {
            selectedCongregationId != null -> entries.filter { it.congregationId == selectedCongregationId }
            needsCongregation -> emptyList()
            else -> entries
        }

    /** Super-Admin (several congregations in scope) must pick one before any record is shown. */
    val needsCongregation: Boolean get() = selectedCongregationId == null && congregationsInScope.size > 1

    val totalBibleStudies: Int get() = visibleEntries.sumOf { it.bibleStudiesCount }
    val totalPreachingHours: Double get() = visibleEntries.filter { isPioneerCategory(it.category) }.sumOf { it.preachingHours }
    val regularPublisherEntries: List<PublisherReportEntry> get() = visibleEntries.filter { !isPioneerCategory(it.category) }
    val participatedYesCount: Int get() = regularPublisherEntries.count { it.participatedInMinistry == true }
}

/**
 * "Service Overseer... can see the consolidated monthly report" spec —
 * backs the Consolidated Monthly Report screen. Scoped exactly like every
 * other report in this app (spec: "Service Overseer sees own congregation;
 * Coordinator Elder/Admin the same; Super-Admin all congregations") via
 * [restrictTo], resolved once by the caller from the session's own role —
 * never a client-tamperable parameter.
 */
@HiltViewModel
class ConsolidatedReportViewModel @Inject constructor(
    private val personRepository: PersonRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val congregationRepository: CongregationRepository,
    private val interestedPersonRepository: InterestedPersonRepository,
    private val preachingTimeRecordRepository: PreachingTimeRecordRepository,
    private val visitRepository: VisitRepository,
    private val monthlyReportRepository: MonthlyReportRepository,
    private val dateRangeStore: DateRangeStore,
) : ViewModel() {

    private var scopeFilter: Set<String>? = null
    private val _selectedCongregationId = MutableStateFlow<String?>(null)

    /** Called once, from the nav graph, with the session's own authorized
     * congregation id(s) — same pattern as DashboardStatsViewModel. */
    fun restrictTo(visibleCongregationIds: Set<String>?) {
        scopeFilter = visibleCongregationIds
    }

    fun selectCongregation(congregationId: String?) {
        _selectedCongregationId.value = congregationId
    }

    fun setDateRange(range: DateRange) = dateRangeStore.set(range)

    /** Only needed for the drill-down (spec items 3-5's "record per
     * publisher" screens) — every publisher's Visits across every
     * congregation, not started app-wide (see VisitRepository's doc
     * comment) since only this report actually needs it. */
    fun startVisitSync(): Flow<Unit> = visitRepository.startRemoteSyncAllForCongregationView()

    private data class RawData(
        val people: List<Person>,
        val assignments: List<RoleAssignment>,
        val congregations: List<Congregation>,
    )

    private data class RecordsData(
        val pipelinePeople: List<InterestedPerson>,
        val preachingRecords: List<PreachingTimeRecord>,
        val visits: List<Visit>,
        val reports: List<MonthlyReport>,
    )

    private val rawData = combine(
        personRepository.observeAll(),
        roleAssignmentRepository.observeAll(),
        congregationRepository.observeAll(),
    ) { people, assignments, congregations -> RawData(people, assignments, congregations) }

    private val recordsData = combine(
        interestedPersonRepository.observeAll(),
        preachingTimeRecordRepository.observeAll(),
        visitRepository.observeAllVisits(),
        monthlyReportRepository.observeAll(),
    ) { pipelinePeople, preachingRecords, visits, reports -> RecordsData(pipelinePeople, preachingRecords, visits, reports) }

    val uiState: StateFlow<ConsolidatedReportUiState> = combine(rawData, recordsData, dateRangeStore.range, _selectedCongregationId) { raw, records, range, selectedCongregationId ->
        val congregationsInScope = scopeFilter?.let { allowed -> raw.congregations.filter { it.id in allowed } } ?: raw.congregations
        val congregationIds = congregationsInScope.map { it.id }.toSet()

        val entries = raw.assignments
            .filter { it.status == RoleAssignmentStatus.ACTIVE && it.congregationId in congregationIds }
            .mapNotNull { assignment -> (assignment.resolvedRoleTypeOrNull() as? RoleType.Publisher)?.let { assignment to it } }
            .filter { (_, publisher) -> publisher.category != PublisherCategory.REMOVED_PUBLISHER }
            .mapNotNull { (assignment, publisher) ->
                val person = raw.people.firstOrNull { it.id == assignment.personId } ?: return@mapNotNull null
                val congregationName = congregationsInScope.firstOrNull { it.id == assignment.congregationId }?.name ?: "—"
                // Centralized in MinistryStatisticsService (spec §1-§15/§51)
                // — was two slightly-different ad hoc de-duplications here,
                // duplicating (correctly, but separately) the exact same
                // logic PublisherDashboardViewModel already had.
                val bibleStudiesCount = MinistryStatisticsService.uniqueVisitedPersons(
                    person.id, records.pipelinePeople, records.visits, PipelineStage.BIBLE_STUDY, range,
                )
                val returnVisitsCount = MinistryStatisticsService.uniqueVisitedPersons(
                    person.id, records.pipelinePeople, records.visits, PipelineStage.RETURN_VISIT, range,
                )
                val isPioneer = isPioneerCategory(publisher.category)
                val preachingHours = if (isPioneer) {
                    records.preachingRecords
                        .filter { it.publisherPersonId == person.id && it.status == RecordStatus.ACTIVE && range.contains(it.date) }
                        .sumOf { it.hoursConsumed }
                } else 0.0
                val participatedInMinistry = if (isPioneer) {
                    null
                } else {
                    // Bug fix: was `status == ReportStatus.SUBMITTED` — a
                    // Posted report (see MonthlyReport.isSubmittedOrPosted's
                    // doc comment) still counts toward this total; it used
                    // to silently drop out the moment it was Posted.
                    val reportsInRange = records.reports.filter {
                        it.publisherPersonId == person.id && it.isSubmittedOrPosted && range.overlapsMonth(it.periodMonth)
                    }
                    if (reportsInRange.isEmpty()) null else reportsInRange.any { it.participatedInPreaching == true }
                }
                PublisherReportEntry(
                    person = person,
                    category = publisher.category,
                    // Non-null: the `it.congregationId in congregationIds` filter
                    // above already excludes a null congregationId (a Set<String>
                    // never contains null as an element to match against).
                    congregationId = assignment.congregationId!!,
                    congregationName = congregationName,
                    bibleStudiesCount = bibleStudiesCount,
                    returnVisitsCount = returnVisitsCount,
                    preachingHours = preachingHours,
                    participatedInMinistry = participatedInMinistry,
                )
            }
            .sortedBy { it.person.fullName }

        ConsolidatedReportUiState(
            entries = entries,
            congregationsInScope = congregationsInScope.sortedBy { it.name },
            selectedCongregationId = selectedCongregationId,
            dateRange = range,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConsolidatedReportUiState())

    fun bibleStudiesFor(publisherPersonId: String): Flow<List<InterestedPerson>> = interestedPersonRepository.observeAll()
        .map { list -> list.filter { it.publisherPersonId == publisherPersonId && it.pipelineStage == PipelineStage.BIBLE_STUDY } }
    fun visitsFor(publisherPersonId: String): Flow<List<Visit>> = visitRepository.observeAllForPublisher(publisherPersonId)
    fun preachingRecordsFor(publisherPersonId: String): Flow<List<PreachingTimeRecord>> = preachingTimeRecordRepository.observeForPublisher(publisherPersonId)
}
