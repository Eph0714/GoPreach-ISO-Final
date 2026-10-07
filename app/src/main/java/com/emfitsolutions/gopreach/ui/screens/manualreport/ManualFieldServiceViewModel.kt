package com.emfitsolutions.gopreach.ui.screens.manualreport

import com.emfitsolutions.gopreach.platform.SimpleDateFormat
import com.emfitsolutions.gopreach.platform.Locale
import com.emfitsolutions.gopreach.platform.Date
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.MonthlyReport
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.ReportStatus
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.model.SOURCE_MANUAL
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.MonthlyReportRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.domain.MonthBounds
import com.emfitsolutions.gopreach.ui.screens.home.isPioneerCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A publisher (or pioneer) in the selected congregation who a manual field service record can be entered for. */
data class ManualPublisher(
    val person: Person,
    val category: PublisherCategory,
    val congregationId: String,
    /** The publisher's own RoleAssignment and FS Group — what proves, on the server, that a group role may touch them. */
    val assignmentId: String = "",
    val groupId: String? = null,
) {
    val isPioneer: Boolean get() = isPioneerCategory(category)
}

/** What the form collected. [hours]/[minutes] apply to pioneers, [participated] to everyone else. */
data class ManualRecordInput(
    val publisherPersonId: String,
    val month: Long,
    val hours: Int,
    val minutes: Int,
    val participated: Boolean?,
    val bibleStudies: Int,
    val remarks: String,
    /** Null keeps whatever the record already holds (the Manual form has no such field). */
    val returnVisits: Int? = null,
)

/** Roles that may enter field service records on a publisher's behalf. */
val ManualEntryRoles = setOf(
    AdminRole.SUPER_ADMIN,
    AdminRole.ADMIN_PER_CONGREGATION,
    AdminRole.SERVICE_OVERSEER,
    AdminRole.SECRETARY,
    AdminRole.COORDINATOR_ELDER,
)

class ManualFieldServiceViewModel(
    private val monthlyReportRepository: MonthlyReportRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val personRepository: PersonRepository,
    private val auditLogRepository: AuditLogRepository,
    congregationRepository: CongregationRepository,
    private val coReports: com.emfitsolutions.gopreach.data.repository.CoFieldServiceReportRepository,
) : ViewModel() {

    /** Which of the congregation's service months the Circuit Overseer approved (those are locked for manual entry too). */
    fun lockStatuses(): Flow<List<com.emfitsolutions.gopreach.data.model.CoMonthStatus>> = coReports.observeStatuses()

    val congregations: StateFlow<List<Congregation>> = congregationRepository.observeAll()
        .map { list -> list.filter { it.status == RecordStatus.ACTIVE }.sortedBy { it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Active, non-removed publishers of exactly [congregationId] — the only people the form offers. */
    fun publishersIn(congregationId: String, groupId: String? = null): Flow<List<ManualPublisher>> =
        combine(roleAssignmentRepository.observeAll(), personRepository.observeAll()) { assignments, people ->
            val byId = people.associateBy { it.id }
            assignments
                .filter { it.status == RoleAssignmentStatus.ACTIVE && it.congregationId == congregationId }
                // A group role sees only the publishers of its own FS Group — whatever the screen's filters say.
                .filter { groupId == null || it.groupId == groupId }
                .mapNotNull { a ->
                    val category = (a.resolvedRoleTypeOrNull() as? RoleType.Publisher)?.category ?: return@mapNotNull null
                    if (category == PublisherCategory.REMOVED_PUBLISHER) return@mapNotNull null
                    byId[a.personId]?.let { ManualPublisher(it, category, congregationId, a.id, a.groupId) }
                }
                .distinctBy { it.person.id }
                .sortedBy { it.person.fullName }
        }

    fun reportsIn(congregationId: String): Flow<List<MonthlyReport>> =
        monthlyReportRepository.observeAll().map { list -> list.filter { it.congregationId == congregationId } }

    val names: Flow<Map<String, String>> = personRepository.observeAll().map { people -> people.associate { it.id to it.fullName } }

    /**
     * Saves (creates or updates) one manual record as an ordinary monthly report so every existing report — Field
     * Service Report, Comparative Report and graph, totals, exports — counts it with no special handling. Returns an
     * error message, or null on success. The checks here are the backend side of the rules: the actor's role, and that
     * the publisher really belongs to [scopeCongregationId] (re-read from the data, not trusted from the form).
     */
    suspend fun save(
        input: ManualRecordInput, scopeCongregationId: String, actorRole: AdminRole?, actorPersonId: String,
        /** Set for a Group Coordinator / Servant / Assistant: the only FS Group whose publishers they may manage. */
        scopeGroupId: String? = null,
    ): String? {
        val groupRole = scopeGroupId != null && actorRole == AdminRole.REGULAR_ELDER
        if (actorRole == null || (actorRole !in ManualEntryRoles && !groupRole)) return "You are not allowed to enter field service records."
        val publisher = publishersIn(scopeCongregationId, if (groupRole) scopeGroupId else null).first().firstOrNull { it.person.id == input.publisherPersonId }
            ?: return if (groupRole) "That publisher is not in your FS Group." else "That publisher is not in this congregation."
        if (input.month <= 0L) return "Choose the reporting month."
        val monthStart = MonthBounds.of(input.month).startInclusive
        try { monthlyReportRepository.requireMonthOpen(scopeCongregationId, monthStart) } catch (e: com.emfitsolutions.gopreach.data.model.MonthLockedException) { return e.message }
        if (input.bibleStudies < 0) return "Bible Studies cannot be negative."
        if (publisher.isPioneer) {
            if (input.hours < 0 || input.minutes !in 0..59) return "Enter hours of 0 or more and minutes from 0 to 59."
        } else {
            val participated = input.participated ?: return "Choose whether the publisher participated (Yes or No)."
            if (!participated && input.bibleStudies > 0) return "Participation is No, so Bible Studies must be 0 — set Participation to Yes if there were Bible Studies."
        }

        val existing = monthlyReportRepository.observeAll().first()
            .firstOrNull { it.publisherPersonId == publisher.person.id && MonthBounds.of(it.periodMonth).startInclusive == monthStart }
        // A record belonging to another congregation can never be overwritten from here.
        if (existing != null && existing.congregationId != scopeCongregationId) return "A record for this month belongs to another congregation."

        val now = System.currentTimeMillis()
        val totalHours = input.hours + input.minutes / 60.0
        val remarks = input.remarks.trim().ifBlank { null }
        val base = existing ?: MonthlyReport(
            publisherPersonId = publisher.person.id,
            congregationId = scopeCongregationId,
            category = publisher.category,
            periodMonth = monthStart,
            createdByPersonId = actorPersonId,
            createdAt = now,
        )
        val saved = base.copy(
            source = SOURCE_MANUAL,
            bibleStudiesCount = input.bibleStudies,
            returnVisitsCount = input.returnVisits ?: base.returnVisitsCount,
            hoursRendered = if (publisher.isPioneer) totalHours else null,
            // Same value as hoursRendered: a manual entry is not an adjustment of calculated hours.
            systemCalculatedHours = if (publisher.isPioneer) totalHours else null,
            hoursConfirmed = false,
            hoursAdjustmentRemarks = null,
            participatedInPreaching = if (publisher.isPioneer) (totalHours > 0.0 || input.bibleStudies > 0) else input.participated,
            // Counts as a submitted report everywhere; a returned/draft one becomes submitted, a posted one stays posted.
            status = if (base.status == ReportStatus.POSTED) ReportStatus.POSTED else ReportStatus.SUBMITTED,
            submittedAt = base.submittedAt ?: now,
            remarks = remarks,
            lastEditedByPersonId = actorPersonId,
            lastEditedAt = now,
            // A group role proves the publisher belongs to its FS Group through that publisher's own role assignment.
            groupRoleAssignmentId = if (groupRole) publisher.assignmentId else base.groupRoleAssignmentId,
        )
        val persisted = try { monthlyReportRepository.save(saved) } catch (e: com.emfitsolutions.gopreach.data.model.MonthLockedException) { return e.message }
        auditLogRepository.log(
            actorPersonId = actorPersonId,
            action = if (existing == null) "MANUAL_FIELD_SERVICE_RECORD_ADD" else "MANUAL_FIELD_SERVICE_RECORD_EDIT",
            targetType = "MonthlyReport",
            targetId = persisted.id,
            congregationId = scopeCongregationId,
            details = publisher.person.fullName + " — " + SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(monthStart)),
        )
        return null
    }

    fun delete(report: MonthlyReport, scopeCongregationId: String, actorRole: AdminRole?, actorPersonId: String, scopeGroupId: String? = null) {
        val groupRole = scopeGroupId != null && actorRole == AdminRole.REGULAR_ELDER
        if (actorRole == null || (actorRole !in ManualEntryRoles && !groupRole) || report.congregationId != scopeCongregationId) return
        viewModelScope.launch {
            if (groupRole) {
                // Only a publisher of the group's own FS Group may be touched; the record is stamped with that publisher's role assignment first so the server can verify it.
                val publisher = publishersIn(scopeCongregationId, scopeGroupId).first().firstOrNull { it.person.id == report.publisherPersonId } ?: return@launch
                if (report.groupRoleAssignmentId != publisher.assignmentId) monthlyReportRepository.saveNow(report.copy(groupRoleAssignmentId = publisher.assignmentId))
            }
            monthlyReportRepository.delete(report.id)
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "MANUAL_FIELD_SERVICE_RECORD_DELETE",
                targetType = "MonthlyReport",
                targetId = report.id,
                congregationId = report.congregationId,
            )
        }
    }
}
