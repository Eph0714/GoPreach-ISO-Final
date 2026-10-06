package com.emfitsolutions.gopreach.ui.screens.publisherreports

import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.model.displayName
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.MonthlyReport
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.ReportStatus
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.MonthlyReportRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.domain.MonthlyReportCalculator
import com.emfitsolutions.gopreach.ui.components.DateRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** "Show" mode — spec: "Show (All, By Publisher)". Congregation filtering
 * used to be a third mutually-exclusive mode here (`BY_CONGREGATION`); it's
 * now the same always-visible [CongregationFilterDropdown] every other
 * Super-Admin screen uses instead — see [selectCongregation] — so it composes
 * with either mode rather than being its own. */
enum class ReportShowMode { ALL, BY_PUBLISHER }

/** One row of the report table — sourced directly from a submitted (or
 * unlocked-for-edit) [MonthlyReport], which already carries the publisher's
 * category and congregation *as of that period* (spec's example: "Status"
 * column is the publisher's category, not the report's lock state). */
data class PublisherReportRow(
    val report: MonthlyReport,
    val person: Person,
    val congregationName: String,
) {
    val category: PublisherCategory get() = report.category
    val isPioneer: Boolean get() = MonthlyReportCalculator.isPioneerCategory(category)
    /** Whether the *Publisher* can still edit this report themselves —
     * "allow the publisher to edit the record until the service overseer
     * will mark it as 'Posted'": true only once [ReportStatus.POSTED],
     * unlike the old model where SUBMITTED alone already locked them out.
     * DRAFT and SUBMITTED both read as "unlocked" here. */
    val isLocked: Boolean get() = report.status == ReportStatus.POSTED
    val isPosted: Boolean get() = report.status == ReportStatus.POSTED
}

data class ManagePublisherReportsUiState(
    val rows: List<PublisherReportRow> = emptyList(),
    val congregationsInScope: List<Congregation> = emptyList(),
    val publishersInScope: List<Person> = emptyList(),
    val dateRange: DateRange = DateRange.thisMonth(),
    val showMode: ReportShowMode = ReportShowMode.ALL,
    val selectedPublisherId: String? = null,
    val selectedCongregationId: String? = null,
    /** Report Summary spec §36 — Classification/Status filters, alongside
     * the congregation/year-month/publisher filters this screen already had. */
    val selectedClassification: PublisherCategory? = null,
    val selectedStatus: ReportStatus? = null,
    val searchQuery: String = "",
    val isLoading: Boolean = true,
) {
    val totalBibleStudies: Int get() = rows.sumOf { it.report.bibleStudiesCount }
    val totalReturnVisits: Int get() = rows.sumOf { it.report.returnVisitsCount }
    val totalHoursByPioneers: Double get() = rows.filter { it.isPioneer }.sumOf { it.report.hoursRendered ?: 0.0 }
}

/**
 * "Manage Publisher Report" module — Super-Admin (every congregation),
 * Admin/Coordinator Elder/Service Overseer (own congregation only, via
 * [restrictTo]). Direct edits and Unlock are scoped the same way; [markPosted]
 * is narrower — Service Overseer/Admin/Super-Admin only, not Coordinator
 * Elder (see ManagePublisherReportsScreen's `canMarkPosted`) — and permanent
 * delete is Super-Admin only (see [canPermanentlyDelete] usages at the call
 * site, same convention as every other Manage screen).
 */
@HiltViewModel
class ManagePublisherReportsViewModel @Inject constructor(
    private val monthlyReportRepository: MonthlyReportRepository,
    private val personRepository: PersonRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val congregationRepository: CongregationRepository,
    private val auditLogRepository: AuditLogRepository,
    private val recycleBinRepository: com.emfitsolutions.gopreach.data.repository.RecycleBinRepository,
) : ViewModel() {

    /** Set once, from the nav graph, before this screen is ever composed —
     * null means Super-Admin (every congregation); a real id means the
     * caller's own congregation is the only one they may ever see or touch. */
    var fixedCongregationId: String? = null
        private set

    fun restrictTo(congregationId: String?) {
        fixedCongregationId = congregationId
    }

    private val _dateRange = MutableStateFlow(DateRange.thisMonth())
    private val _showMode = MutableStateFlow(ReportShowMode.ALL)
    private val _selectedPublisherId = MutableStateFlow<String?>(null)
    private val _selectedCongregationId = MutableStateFlow<String?>(null)
    private val _selectedClassification = MutableStateFlow<PublisherCategory?>(null)
    private val _selectedStatus = MutableStateFlow<ReportStatus?>(null)
    private val _searchQuery = MutableStateFlow("")

    fun selectClassification(category: PublisherCategory?) = _selectedClassification.update { category }
    fun selectStatus(status: ReportStatus?) = _selectedStatus.update { status }

    /** Report Summary spec §36 — "Search and Reset." Search is just letting
     * the already-live filters keep filtering (no separate action needed);
     * Reset clears every filter back to its default in one action. */
    fun resetFilters() {
        _dateRange.update { DateRange.thisMonth() }
        _showMode.update { ReportShowMode.ALL }
        _selectedPublisherId.update { null }
        _selectedClassification.update { null }
        _selectedStatus.update { null }
        _searchQuery.update { "" }
    }

    fun setDateRange(range: DateRange) = _dateRange.update { range }
    fun setShowMode(mode: ReportShowMode) = _showMode.update {
        // Switching away from a mode clears its own selection, so a stale
        // pick from a previous mode never silently keeps filtering once
        // it's no longer shown as selected in the UI. The Congregation filter
        // is independent of this now (see [selectCongregation]), so it's
        // deliberately untouched here — picking "By Publisher" never resets
        // an already-chosen Congregation, and vice versa.
        if (mode != ReportShowMode.BY_PUBLISHER) _selectedPublisherId.update { null }
        mode
    }
    fun selectPublisher(personId: String?) = _selectedPublisherId.update { personId }

    /** "Add a filter for Congregation" (Super-Admin only) — always-available,
     * independent of [showMode]; `null` means "All Congregations." */
    fun selectCongregation(congregationId: String?) = _selectedCongregationId.update { congregationId }
    fun setSearchQuery(query: String) = _searchQuery.update { query }

    private data class RawData(
        val reports: List<MonthlyReport>,
        val people: List<Person>,
        val congregations: List<Congregation>,
    )

    private data class Filters(
        val dateRange: DateRange,
        val showMode: ReportShowMode,
        val selectedPublisherId: String?,
        val selectedCongregationId: String?,
        val selectedClassification: PublisherCategory?,
        val selectedStatus: ReportStatus?,
        val searchQuery: String,
    )

    private val rawData = combine(
        monthlyReportRepository.observeAll(),
        personRepository.observeAll(),
        congregationRepository.observeAll(),
    ) { reports, people, congregations -> RawData(reports, people, congregations) }

    private val filters = combine(
        _dateRange, _showMode, _selectedPublisherId, _selectedCongregationId, _selectedClassification, _selectedStatus, _searchQuery,
    ) { values ->
        Filters(
            dateRange = values[0] as DateRange,
            showMode = values[1] as ReportShowMode,
            selectedPublisherId = values[2] as String?,
            selectedCongregationId = values[3] as String?,
            selectedClassification = values[4] as PublisherCategory?,
            selectedStatus = values[5] as ReportStatus?,
            searchQuery = values[6] as String,
        )
    }

    /** Every active Publisher in scope — feeds the "By Publisher" dropdown.
     * Recomputed from RoleAssignments (not just who happens to have a report
     * in the current date range), so a publisher with no report yet this
     * period is still pickable. */
    val publishersInScope: StateFlow<List<Person>> = combine(
        roleAssignmentRepository.observeAll(),
        personRepository.observeAll(),
    ) { assignments, people ->
        assignments
            .filter { it.status == RoleAssignmentStatus.ACTIVE && it.resolvedRoleTypeOrNull() is RoleType.Publisher }
            .filter { fixedCongregationId == null || it.congregationId == fixedCongregationId }
            .mapNotNull { assignment -> people.firstOrNull { it.id == assignment.personId } }
            .distinctBy { it.id }
            .sortedBy { it.fullName }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val uiState: StateFlow<ManagePublisherReportsUiState> = combine(rawData, filters, publishersInScope) { raw, f, publishers ->
        val congregationsInScope = if (fixedCongregationId == null) raw.congregations else raw.congregations.filter { it.id == fixedCongregationId }

        val rows = raw.reports
            .filter { fixedCongregationId == null || it.congregationId == fixedCongregationId }
            .filter { fixedCongregationId != null || (f.selectedCongregationId != null && it.congregationId == f.selectedCongregationId) }
            .filter { f.showMode != ReportShowMode.BY_PUBLISHER || f.selectedPublisherId == null || it.publisherPersonId == f.selectedPublisherId }
            .filter { f.dateRange.overlapsMonth(it.periodMonth) }
            .filter { f.selectedClassification == null || it.category == f.selectedClassification }
            .filter { f.selectedStatus == null || it.status == f.selectedStatus }
            .mapNotNull { report ->
                val person = raw.people.firstOrNull { it.id == report.publisherPersonId } ?: return@mapNotNull null
                val congregationName = raw.congregations.firstOrNull { it.id == report.congregationId }?.name ?: "—"
                PublisherReportRow(report, person, congregationName)
            }
            .filter { row ->
                f.searchQuery.isBlank() ||
                    row.person.fullName.contains(f.searchQuery, ignoreCase = true) ||
                    row.category.displayName.contains(f.searchQuery, ignoreCase = true) ||
                    row.congregationName.contains(f.searchQuery, ignoreCase = true)
            }
            .sortedWith(compareBy({ it.congregationName }, { it.person.fullName }))

        ManagePublisherReportsUiState(
            rows = rows,
            congregationsInScope = congregationsInScope.sortedBy { it.name },
            publishersInScope = publishers,
            dateRange = f.dateRange,
            showMode = f.showMode,
            selectedPublisherId = f.selectedPublisherId,
            selectedCongregationId = f.selectedCongregationId,
            selectedClassification = f.selectedClassification,
            selectedStatus = f.selectedStatus,
            searchQuery = f.searchQuery,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ManagePublisherReportsUiState())

    /** "Can edit directly the report if there are changes" — Bible Study/
     * Hours/Participated fields only; category, publisher, and period are
     * never touched by an editor here. Leaves [MonthlyReport.status]
     * untouched (still locked if it already was), unlike [unlock]. */
    fun updateReport(
        report: MonthlyReport,
        bibleStudiesCount: Int,
        hoursRendered: Double?,
        participatedInPreaching: Boolean?,
        actorPersonId: String,
    ) {
        viewModelScope.launch {
            val updated = report.copy(
                bibleStudiesCount = bibleStudiesCount,
                hoursRendered = hoursRendered,
                participatedInPreaching = participatedInPreaching,
                lastEditedByPersonId = actorPersonId,
                lastEditedAt = System.currentTimeMillis(),
            )
            monthlyReportRepository.save(updated)
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "EDIT_PUBLISHER_REPORT",
                targetType = "MonthlyReport",
                targetId = report.id,
                congregationId = report.congregationId,
            )
        }
    }

    /** "Unlock so that the publisher can edit it by himself" — flips a
     * Posted (or Submitted) report back to DRAFT, the same status a fresh
     * report starts as; the publisher's own Monthly Report screen already
     * treats DRAFT (and now SUBMITTED too) as editable (see
     * MonthlyReportUiState.isLocked), and their next Submit puts it back to
     * SUBMITTED — "it will return to status as lock" — with no separate
     * flag needed here. Doubles as "un-post": there's no other way back to
     * an editable-by-the-publisher state from POSTED. */
    fun unlock(report: MonthlyReport, actorPersonId: String) {
        viewModelScope.launch {
            // saveNow, not save — see MonthlyReportRepository.saveNow's doc
            // comment: a lock-state change has to actually reach the
            // publisher's own device promptly, not sit queued for whenever
            // someone next syncs.
            monthlyReportRepository.saveNow(
                report.copy(status = ReportStatus.DRAFT, lastEditedByPersonId = actorPersonId, lastEditedAt = System.currentTimeMillis()),
            )
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "UNLOCK_PUBLISHER_REPORT",
                targetType = "MonthlyReport",
                targetId = report.id,
                congregationId = report.congregationId,
            )
        }
    }

    /** "Allow the publisher to edit the record until the service overseer
     * will mark it as 'Posted', that's the time the publisher can no
     * longer edit the record" — the actual lock trigger, gated at the call
     * site to Service Overseer/Admin (own congregation)/Super-Admin (see
     * ManagePublisherReportsScreen's `canMarkPosted`). Distinct from
     * [unlock] going the other way: this is the one action that actually
     * shuts the Publisher's own edit access off. */
    fun markPosted(report: MonthlyReport, actorPersonId: String) {
        viewModelScope.launch {
            // saveNow — see MonthlyReportRepository.saveNow's doc comment.
            // Bug fix ("the publisher can still edit even though it's
            // posted"): the plain queued `save()` only reached Firestore
            // (and therefore the publisher's own device) whenever someone
            // next happened to sync, which could be indefinitely under this
            // app's manual/periodic-sync design — the publisher's copy kept
            // showing SUBMITTED, so their Submit button stayed correctly
            // (per that stale data) enabled.
            val now = System.currentTimeMillis()
            monthlyReportRepository.saveNow(
                report.copy(
                    status = ReportStatus.POSTED,
                    reviewedByPersonId = actorPersonId,
                    reviewedAt = now,
                    lastEditedByPersonId = actorPersonId,
                    lastEditedAt = now,
                ),
            )
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "MARK_PUBLISHER_REPORT_POSTED",
                targetType = "MonthlyReport",
                targetId = report.id,
                congregationId = report.congregationId,
            )
        }
    }

    /** My Planner / Reporting upgrade spec §35 — "Return for Correction":
     * distinct from [unlock] (which just flips POSTED back to DRAFT with no
     * record of why). This is the real workflow step — a SUBMITTED report
     * that needs fixing before it's ever POSTED, with a required [reason]
     * the audit trail and the report itself both keep. The Publisher's own
     * next Submit from [ReportStatus.RETURNED] becomes
     * [ReportStatus.CORRECTED] (see [com.emfitsolutions.gopreach.ui.screens
     * .monthlyreport.MonthlyReportViewModel.submit]), not a plain
     * resubmission, so this history is visible on the report itself. */
    fun returnForCorrection(report: MonthlyReport, reason: String, actorPersonId: String) {
        if (reason.isBlank()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            monthlyReportRepository.saveNow(
                report.copy(
                    status = ReportStatus.RETURNED,
                    returnedByPersonId = actorPersonId,
                    returnedAt = now,
                    correctionReason = reason.trim(),
                    lastEditedByPersonId = actorPersonId,
                    lastEditedAt = now,
                ),
            )
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "RETURN_PUBLISHER_REPORT_FOR_CORRECTION",
                targetType = "MonthlyReport",
                targetId = report.id,
                congregationId = report.congregationId,
                details = reason.trim(),
            )
        }
    }

    /** "The service overseer will select a month, then he can see all
     * publishers that submitted their record within that month. After that
     * he will click the POST button, all the record will now be locked" —
     * one tap posts every currently-visible SUBMITTED report at once
     * (whichever month/filters [uiState.rows] is already scoped to — see
     * [ManagePublisherReportsScreen]'s date-range filter), instead of
     * tapping the per-row lock icon one publisher at a time. Only
     * SUBMITTED rows are posted; a DRAFT (never actually submitted) or
     * already-POSTED row is silently skipped, same as if it weren't in the
     * list at all. */
    fun markPostedAll(rows: List<PublisherReportRow>, actorPersonId: String) {
        val toPost = rows.filter { it.report.status == ReportStatus.SUBMITTED }
        if (toPost.isEmpty()) return
        viewModelScope.launch {
            toPost.forEach { row ->
                // saveNow — same reasoning as markPosted above, applied to
                // every row in the batch.
                monthlyReportRepository.saveNow(
                    row.report.copy(status = ReportStatus.POSTED, lastEditedByPersonId = actorPersonId, lastEditedAt = System.currentTimeMillis()),
                )
                auditLogRepository.log(
                    actorPersonId = actorPersonId,
                    action = "MARK_PUBLISHER_REPORT_POSTED",
                    targetType = "MonthlyReport",
                    targetId = row.report.id,
                    congregationId = row.report.congregationId,
                )
            }
        }
    }

    /** Super-Admin-only (enforced at the call site, same convention as every
     * other Manage screen's force delete) — permanently removes this one
     * report row. Never touches the Publisher's own RoleAssignment/Person
     * record. */
    fun permanentlyDelete(report: MonthlyReport, actorPersonId: String) {
        viewModelScope.launch {
            val publisherName = personRepository.get(report.publisherPersonId)?.fullName.orEmpty()
            val period = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(report.periodMonth))
            val groupId = roleAssignmentRepository.observeForPerson(report.publisherPersonId).first().firstOrNull { it.groupId != null }?.groupId
            recycleBinRepository.moveToTrash(
                recordType = "Monthly Report",
                module = "Publisher Reports",
                label = if (publisherName.isBlank()) period else "$publisherName — $period",
                congregationId = report.congregationId,
                groupId = groupId,
                originalModifiedAt = report.lastEditedAt ?: report.submittedAt,
                deletedByPersonId = actorPersonId,
                items = listOf(recycleBinRepository.item("monthlyReports", report.id, report)),
            )
            monthlyReportRepository.delete(report.id)
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "PERMANENT_DELETE_PUBLISHER_REPORT",
                targetType = "MonthlyReport",
                targetId = report.id,
                congregationId = report.congregationId,
            )
        }
    }
}
