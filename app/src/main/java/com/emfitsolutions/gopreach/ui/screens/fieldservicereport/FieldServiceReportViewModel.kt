package com.emfitsolutions.gopreach.ui.screens.fieldservicereport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.MonthlyReport
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.ReportStatus
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.GroupRepository
import com.emfitsolutions.gopreach.data.repository.MonthlyReportRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.messageOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import com.emfitsolutions.gopreach.platform.Calendar
import java.util.Date
import java.util.Locale

/**
 * One publisher status as it appears in the Field Service Report's columns:
 * UP / Pub / AP / RP always, then any other status (Special Pioneer, Irregular,
 * Inactive, Reproof) as its own column the moment a publisher in the group has
 * it — in each block (No. of Reports, Bible Studies, and Hours for the pioneer
 * kinds). Removed publishers never appear.
 */
enum class StatusColumn(val label: String, val category: PublisherCategory, val isPioneer: Boolean, val alwaysShown: Boolean) {
    UP("UP", PublisherCategory.UNBAPTIZED_PUBLISHER, isPioneer = false, alwaysShown = true),
    PUB("Pub", PublisherCategory.REGULAR_PUBLISHER, isPioneer = false, alwaysShown = true),
    AP("AP", PublisherCategory.AUXILIARY_PIONEER, isPioneer = true, alwaysShown = true),
    RP("RP", PublisherCategory.REGULAR_PIONEER, isPioneer = true, alwaysShown = true),
    SP("SP", PublisherCategory.SPECIAL_PIONEER, isPioneer = true, alwaysShown = false),
    IRREGULAR("Irr", PublisherCategory.IRREGULAR_PUBLISHER, isPioneer = false, alwaysShown = false),
    INACTIVE("Inactive", PublisherCategory.INACTIVE_PUBLISHER, isPioneer = false, alwaysShown = false),
    REPROOF("Reproof", PublisherCategory.REPROOF_PUBLISHER, isPioneer = false, alwaysShown = false),
    ;

    companion object {
        fun of(category: PublisherCategory): StatusColumn? = entries.firstOrNull { it.category == category }
    }
}

/** One publisher's line. [reportsCount] is the "No. of Reports" figure: 1 for every publisher in the group, under
 * their status — it counts the publisher, whether or not they have hours or Bible Studies (or any report at
 * all that period; [remarks] stays blank in that case — only entered text is ever shown). */
/** Level 1 vs level 2: how many of a congregation's publishers have submitted their own month to the congregation. */
data class PublisherOverview(val total: Int, val submitted: Int) {
    val notSubmitted: Int get() = (total - submitted).coerceAtLeast(0)
}

data class FieldServiceReportRow(
    val number: Int,
    val status: StatusColumn,
    val name: String,
    val reportsCount: Int,
    val hours: Double?,
    val bibleStudies: Int?,
    val remarks: String,
    /** The publisher this line belongs to (for the Table View's Edit / Delete). */
    val personId: String = "",
    /** False for a publisher who has no report in the period (their line is only a placeholder): the "No Report" filter shows these. */
    val hasReport: Boolean = true,
) {
    val reported: Boolean get() = reportsCount > 0
}

/** Everything the on-screen table, the Excel file and the PDF print are built from, so they can't disagree. */
data class FieldServiceReportSheet(
    val groupName: String,
    /** "October 2026", or "September 2026 to March 2027" for a range. */
    val monthLabel: String,
    /** True when the report covers more than one month. */
    val isRange: Boolean,
    val overseer: String,
    val servant: String,
    val assistant: String,
    /** Columns of the "No. of Reports" and "Bible Studies" blocks. */
    val reportColumns: List<StatusColumn>,
    /** Columns of the "Hours" block — the pioneer kinds only. */
    val hourColumns: List<StatusColumn>,
    val rows: List<FieldServiceReportRow>,
    /** The lines under the title — a group's Overseer / Servant / Assistant, or, for a whole-congregation sheet, the congregation. */
    val officerRows: List<Pair<String, String>> = listOf(
        "Group Overseer:" to overseer,
        "Group Servant:" to servant,
        "Group Assistant:" to assistant,
    ),
    /** The month range this sheet represents when it is one side of a two-range comparison; null for a normal report. */
    val periodTag: String? = null,
) {
    /** Publishers on this sheet — every row. */
    val publisherCount: Int get() = rows.size
    /** Publishers who submitted a report in the period (a row with no report has no Bible Study figure). */
    val participatedCount: Int get() = rows.count { it.bibleStudies != null }
    /** "Total Publishers: 28   Participated: 24" — shown on screen and in the PDF. */
    val countLine: String get() = "Total Publishers: $publisherCount   Participated: $participatedCount"

    /** The heading line: "Report for the Month of …" or "Report for the Period of …". */
    val titleLine: String get() =
        if (isRange) "Report for the Period of $monthLabel" else "Report for the Month of $monthLabel"
    fun reportCount(column: StatusColumn): Int = rows.filter { it.status == column }.sumOf { it.reportsCount }
    fun totalHours(column: StatusColumn): Double = rows.filter { it.status == column }.sumOf { it.hours ?: 0.0 }
    fun totalBibleStudies(column: StatusColumn): Int = rows.filter { it.status == column }.sumOf { it.bibleStudies ?: 0 }
}

/** One line of the List View: a publisher's report for one month, or "No report" when [report] is null. */
data class FieldServiceReportListItem(
    val groupName: String,
    val publisherName: String,
    val status: StatusColumn?,
    val monthLabel: String?,
    val report: MonthlyReport?,
) {
    val key: String get() = groupName + "|" + publisherName + "|" + (report?.id ?: "none")
}

/** "24.5" or "62" — whole numbers without a trailing ".0". */
fun formatHours(hours: Double): String =
    if (hours % 1.0 == 0.0) hours.toLong().toString() else "%.1f".format(Locale.US, hours)


/** Midnight on the 1st of the month [monthsAgo] months before this one. */
fun fieldServiceMonthStart(monthsAgo: Int = 0): Long = Calendar.getInstance().apply {
    add(Calendar.MONTH, -monthsAgo)
    set(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

class FieldServiceReportViewModel(
    private val groupRepository: GroupRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val personRepository: PersonRepository,
    private val monthlyReportRepository: MonthlyReportRepository,
    private val auditLogRepository: AuditLogRepository,
    private val creditHourRecordRepository: com.emfitsolutions.gopreach.data.repository.CreditHourRecordRepository,
    congregationRepository: CongregationRepository,
    private val coReportRepository: com.emfitsolutions.gopreach.data.repository.CoFieldServiceReportRepository,
    private val coReportService: com.emfitsolutions.gopreach.data.repository.CoFieldServiceReportService,
    private val appSettingsRepository: com.emfitsolutions.gopreach.data.repository.AppSettingsRepository,
    private val statisticsBuilder: com.emfitsolutions.gopreach.data.repository.CongregationStatisticsBuilder,
    private val attendanceRepository: com.emfitsolutions.gopreach.data.repository.MeetingAttendanceRepository,
    private val categoryHistoryRepository: com.emfitsolutions.gopreach.data.repository.PublisherCategoryHistoryRepository,
    private val coReceivedRepository: com.emfitsolutions.gopreach.data.repository.CoReceivedReportRepository,
    private val circuitCodeRepository: com.emfitsolutions.gopreach.data.repository.CircuitCodeRepository,
    private val serverClock: com.emfitsolutions.gopreach.data.remote.ServerClock,
) : ViewModel() {

    val congregations: Flow<List<Congregation>> = congregationRepository.observeAll()
        .map { list -> list.filter { it.status == RecordStatus.ACTIVE }.sortedBy { it.name } }

    fun groupsFor(congregationId: String?): Flow<List<Group>> = groupRepository.observeAll().map { list ->
        if (congregationId == null) emptyList()
        else list.filter { it.status == RecordStatus.ACTIVE && it.congregationId == congregationId }.sortedWith(com.emfitsolutions.gopreach.domain.GroupNameOrder)
    }

    // ---------------------------------------------------------------------------------------------
    // Submitting the month's report to the Circuit Overseer. The report stays the congregation's own records — nothing is
    // copied; only the month's status moves (Not Submitted → Submitted → Received, or Returned → Submitted).

    /** The workflow status of [congregationId]'s [month]; null = never submitted (Not Submitted). */
    fun submissionFor(congregationId: String?, month: Long): Flow<com.emfitsolutions.gopreach.data.model.CoMonthStatus?> =
        coReportRepository.observeStatuses().map { list -> if (congregationId == null) null else list.firstOrNull { it.id == com.emfitsolutions.gopreach.data.model.coReportId(congregationId, month) } }

    /** Every month's workflow status of [congregationId] (a month with no entry has not been sent). */
    fun submissionsFor(congregationId: String?): Flow<List<com.emfitsolutions.gopreach.data.model.CoMonthStatus>> =
        coReportRepository.observeStatuses().map { list -> if (congregationId == null) emptyList() else list.filter { it.congregationId == congregationId } }

    /** [publisherOverview] for several months at once (month start -> overview). */
    fun publisherOverviews(congregationId: String?, months: List<Long>): Flow<Map<Long, PublisherOverview>> =
        if (months.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyMap())
        else combine(months.map { publisherOverview(congregationId, it) }) { list -> months.zip(list).toMap() }

    /** Every month of [congregationId] the Circuit Overseer may see (Submitted / Received / Returned), newest first. */
    fun visibleMonthsFor(congregationId: String?): Flow<List<com.emfitsolutions.gopreach.data.model.CoMonthStatus>> =
        coReportRepository.observeStatuses().map { list ->
            list.filter { it.congregationId == congregationId && it.status.visibleToCircuitOverseer }.sortedByDescending { it.periodMonth }
        }

    /** The audit history of one month (Super-Admin / Circuit Overseer / senders may read it). */
    fun historyFor(reportId: String): Flow<List<com.emfitsolutions.gopreach.data.model.CoReportEvent>> =
        coReportRepository.observeEvents().map { list -> list.filter { it.reportId == reportId } }

    /** How many publishers of [congregationId] have handed in [month]'s report to the congregation — shown before the consolidated report goes to the Circuit Overseer. */
    fun publisherOverview(congregationId: String?, month: Long): Flow<PublisherOverview> =
        combine(roleAssignmentRepository.observeAll(), monthlyReportRepository.observeAll()) { assignments, reports ->
            if (congregationId == null) return@combine PublisherOverview(0, 0)
            val publishers = assignments
                .filter { it.status == RoleAssignmentStatus.ACTIVE && it.congregationId == congregationId }
                .filter { (it.resolvedRoleTypeOrNull() as? RoleType.Publisher)?.category.let { c -> c != null && c != PublisherCategory.REMOVED_PUBLISHER } }
                .map { it.personId }.toSet()
            val submitted = reports.filter { it.congregationId == congregationId && it.periodMonth == month && it.status.countsAsSubmitted }.map { it.publisherPersonId }.toSet()
            PublisherOverview(total = publishers.size, submitted = publishers.count { it in submitted })
        }

    /** Configurable: whether the consolidated report is blocked until every publisher has submitted (off = only a warning). */
    val requireAllPublishersSubmitted: Flow<Boolean> = appSettingsRepository.observe().map { it.requireAllPublishersSubmitted }

    suspend fun setRequireAllPublishersSubmitted(required: Boolean, actorPersonId: String) =
        appSettingsRepository.saveRequireAllPublishersSubmitted(required, actorPersonId)

    private suspend fun actorFor(personId: String): com.emfitsolutions.gopreach.data.repository.SubmissionActor {
        val person = personRepository.get(personId)
        return com.emfitsolutions.gopreach.data.repository.SubmissionActor(
            personId, person?.fullName ?: personId,
            if (person?.isSuperAdmin == true) "SUPER_ADMIN" else person?.activeAdminRole.orEmpty(),
        )
    }

    private fun previousMonth(month: Long): Long = Calendar.getInstance().apply { timeInMillis = month; add(Calendar.MONTH, -1) }.timeInMillis

    private fun statusLabel(category: PublisherCategory): String = StatusColumn.of(category)?.label ?: category.name

    /**
     * What sending [month] involves, worked out before anything is sent. Each publisher's category is the one that applied IN that
     * month (their own report for the month, an Admin confirmation, or the recorded category history) - never the current profile;
     * a publisher with nothing on record is listed in [MonthSnapshotPlan.needs] for the Admin to confirm.
     */
    suspend fun planMonth(congregationId: String, month: Long): MonthSnapshotPlan {
        val assignments = roleAssignmentRepository.observeAll().first()
        val people = personRepository.observeAll().first().associateBy { it.id }
        val reports = monthlyReportRepository.observeAll().first()
        val history = categoryHistoryRepository.observeAll().first()
        val currentCategory = mutableMapOf<String, PublisherCategory>()
        val members = assignments
            .filter { it.status == RoleAssignmentStatus.ACTIVE && it.congregationId == congregationId }
            .mapNotNull { a ->
                val category = (a.resolvedRoleTypeOrNull() as? RoleType.Publisher)?.category ?: return@mapNotNull null
                if (category == PublisherCategory.REMOVED_PUBLISHER) null else people[a.personId]?.also { currentCategory[it.id] = category }
            }
            .distinctBy { it.id }
        val previous = previousMonth(month)
        val resolved = members.associate { it.id to com.emfitsolutions.gopreach.domain.PublisherCategoryResolver.resolve(it.id, month, reports, history) }
        val needs = members.filter { resolved[it.id]?.category == null }
            .sortedWith(compareBy({ it.lastName.lowercase() }, { it.firstName.lowercase() }))
            .map { CategoryNeed(it.id, it.lastName.trim() + ", " + it.firstName.trim(), month, currentCategory[it.id]) }
        val changes = members.mapNotNull { p ->
            val now = resolved[p.id]?.category ?: return@mapNotNull null
            val before = com.emfitsolutions.gopreach.domain.PublisherCategoryResolver.resolve(p.id, previous, reports, history).category ?: return@mapNotNull null
            if (before == now) null
            else com.emfitsolutions.gopreach.data.model.CoCategoryChange(p.id, p.lastName.trim() + ", " + p.firstName.trim(), statusLabel(before), statusLabel(now))
        }.sortedBy { it.name.lowercase() }
        return MonthSnapshotPlan(month, members.size, resolved, needs, changes)
    }

    /**
     * The month's whole-congregation table exactly as the sheet shows it right now, with each publisher under the category they
     * had in THAT month: the Circuit Overseer's frozen snapshot. Null if it cannot be built (the send is then refused, never made without it).
     */
    private suspend fun snapshotOf(
        congregationId: String, month: Long, plan: MonthSnapshotPlan,
        categories: Map<String, PublisherCategory>, confirmedBy: Map<String, String>, preparedByRole: String,
    ): com.emfitsolutions.gopreach.data.model.CoReceivedReport? = runCatching {
        val congregation = congregations.first().firstOrNull { it.id == congregationId } ?: return@runCatching null
        val sheet = sheetsFor(groupsFor(congregationId).first(), month, month, congregation, categories).first().firstOrNull() ?: return@runCatching null
        val assignedCo = circuitCodeRepository.observeLinks().first()[congregationId]?.circuitOverseerPersonId?.takeIf { it.isNotBlank() }
        com.emfitsolutions.gopreach.data.model.CoReceivedReport(
            congregationId = congregationId, congregationName = congregation.name, periodMonth = month,
            rows = sheet.rows.map { r ->
                val category = categories[r.personId]
                val source = if (r.personId in confirmedBy) com.emfitsolutions.gopreach.data.model.CategorySource.ADMIN_CONFIRMED
                else plan.resolved[r.personId]?.source ?: com.emfitsolutions.gopreach.data.model.CategorySource.UNCONFIRMED
                com.emfitsolutions.gopreach.data.model.CoReceivedRow(
                    number = r.number, status = r.status.label, name = r.name, reportsCount = r.reportsCount, hours = r.hours,
                    bibleStudies = r.bibleStudies, remarks = r.remarks, personId = r.personId, category = category?.name.orEmpty(),
                    categorySource = source.name, categoryConfirmedByPersonId = confirmedBy[r.personId],
                )
            },
            categoryChanges = plan.changes,
            preparedByRole = preparedByRole,
            assignedCoPersonId = assignedCo,
            schemaVersion = SNAPSHOT_SCHEMA_VERSION,
        )
    }.getOrNull()

    /** The Circuit Overseer this congregation is assigned to (their name), or null when none is assigned. */
    fun assignedOverseerName(congregationId: String?): Flow<String?> =
        combine(circuitCodeRepository.observeLinks(), personRepository.observeAll()) { links, people ->
            val id = congregationId?.let { links[it]?.circuitOverseerPersonId }?.takeIf { it.isNotBlank() }
            id?.let { people.firstOrNull { p -> p.id == it }?.fullName }
        }

    /** The service months a user may report: every month up to and including the current one, by the SERVER's date (never just the device clock). */
    suspend fun serverNow(): com.emfitsolutions.gopreach.data.remote.ServerClock.Reading = serverClock.read()

    /**
     * The live month as it would be sent right now (no confirmations asked, no history written), to see whether it differs from a sent
     * snapshot. A publisher whose category is unknown keeps the category the sent snapshot used for them.
     */
    private suspend fun liveSnapshotOf(congregationId: String, month: Long, plan: MonthSnapshotPlan, sent: com.emfitsolutions.gopreach.data.model.CoReceivedReport?) =
        snapshotOf(
            congregationId, month, plan,
            plan.resolved.mapNotNull { (id, r) -> r.category?.let { id to it } }.toMap() +
                plan.needs.mapNotNull { n ->
                    val kept = sent?.rows?.firstOrNull { it.personId == n.personId }?.category?.takeIf { it.isNotBlank() }?.let { runCatching { PublisherCategory.valueOf(it) }.getOrNull() }
                    (kept ?: n.current)?.let { n.personId to it }
                },
            emptyMap(), "",
        )

    /** True when the live report for [month] differs from the snapshot already sent ([sent]) - the "Changes Since Submission" indicator. */
    suspend fun hasChangesSince(congregationId: String, month: Long, sent: com.emfitsolutions.gopreach.data.model.CoReceivedReport): Boolean {
        val live = liveSnapshotOf(congregationId, month, planMonth(congregationId, month), sent) ?: return false
        return com.emfitsolutions.gopreach.domain.SnapshotComparison.compare(sent, live)?.hasChanges == true
    }

    /** The months among [months] that are Sent to CO (awaiting receipt) whose live report has changed since that snapshot was sent. Recomputed whenever the live data changes. */
    fun changesSince(congregationId: String?, months: List<Long>): Flow<Set<Long>> =
        if (congregationId == null || months.isEmpty()) kotlinx.coroutines.flow.flowOf(emptySet())
        else combine(
            monthlyReportRepository.observeAll(), roleAssignmentRepository.observeAll(), categoryHistoryRepository.observeAll(),
            coReceivedRepository.observeReports(), coReportRepository.observeStatuses(),
        ) { _, _, _, copies, statuses -> copies to statuses }.map { (copies, statuses) ->
            months.filter { m ->
                val st = statuses.firstOrNull { it.id == com.emfitsolutions.gopreach.data.model.coReportId(congregationId, m) }
                if (st == null || st.status != com.emfitsolutions.gopreach.data.model.CoReportStatus.SUBMITTED) return@filter false
                val sent = copies.firstOrNull { it.congregationId == congregationId && it.periodMonth == m && it.version == st.version } ?: return@filter false
                runCatching { hasChangesSince(congregationId, m, sent) }.getOrDefault(false)
            }.toSet()
        }

    /**
     * Checks every selected month on its own BEFORE anything is sent: eligibility (not a future month by the server's date), whether it
     * is already received, awaiting receipt (and whether it changed), returned or cancelled, whether it has any report data, and whether
     * the "every publisher must submit" rule blocks it. One month failing never affects the others.
     */
    suspend fun validateMonths(congregationId: String, months: List<Long>): List<MonthValidation> {
        val now = serverNow().now
        val statuses = coReportRepository.observeStatuses().first()
        val copies = coReceivedRepository.observeReports().first()
        val requireAll = requireAllPublishersSubmitted.first()
        return months.distinct().sorted().map { month ->
            val st = statuses.firstOrNull { it.id == com.emfitsolutions.gopreach.data.model.coReportId(congregationId, month) }
            val status = st?.status ?: com.emfitsolutions.gopreach.data.model.CoReportStatus.NOT_SUBMITTED
            val version = st?.version ?: 0
            fun result(check: MonthCheck, message: String, needs: Int = 0) = MonthValidation(month, check, status, version, message, needs)
            if (com.emfitsolutions.gopreach.data.model.isFutureServiceMonth(month, now)) return@map result(MonthCheck.FUTURE, "A future month cannot be reported.")
            if (status == com.emfitsolutions.gopreach.data.model.CoReportStatus.RECEIVED) {
                return@map result(MonthCheck.ALREADY_RECEIVED, "Already received by the Circuit Overseer (version " + version + "). Only the Circuit Overseer can return it for editing.")
            }
            val plan = planMonth(congregationId, month)
            if (plan.publisherCount == 0) return@map result(MonthCheck.NO_DATA, "No active publishers, so there is no report to send for this month.")
            val overview = publisherOverview(congregationId, month).first()
            if (requireAll && overview.notSubmitted > 0) {
                return@map result(MonthCheck.PUBLISHERS_PENDING, overview.notSubmitted.toString() + " publisher(s) have not submitted their report, and sending is set to wait for everyone.")
            }
            val needs = plan.needs.size
            val needsNote = if (needs > 0) " $needs publisher categor" + (if (needs == 1) "y needs" else "ies need") + " your confirmation." else ""
            when (status) {
                com.emfitsolutions.gopreach.data.model.CoReportStatus.SUBMITTED -> {
                    val sent = copies.firstOrNull { it.congregationId == congregationId && it.periodMonth == month && it.version == version }
                    val changed = sent != null && runCatching { hasChangesSince(congregationId, month, sent) }.getOrDefault(true)
                    if (sent != null && !changed) result(MonthCheck.NO_CHANGES, "Version $version is awaiting receipt and the live report has not changed since - nothing new to send.")
                    else result(MonthCheck.SEND_UPDATE, "Version $version is awaiting receipt; the live report has changed since. An updated snapshot (version " + (version + 1) + ") will be sent." + needsNote, needs)
                }
                com.emfitsolutions.gopreach.data.model.CoReportStatus.RETURNED ->
                    result(MonthCheck.RESEND_AFTER_RETURN, "Returned for editing" + (st?.returnReason?.let { ": $it" } ?: "") + ". The corrected report will be sent as version " + (version + 1) + "." + needsNote, needs)
                com.emfitsolutions.gopreach.data.model.CoReportStatus.CANCELLED ->
                    result(MonthCheck.RESEND_AFTER_CANCEL, "The previous submission was cancelled" + (st?.cancelReason?.let { ": $it" } ?: "") + ". It will be sent again as version " + (version + 1) + "." + needsNote, needs)
                else -> result(MonthCheck.READY, "Ready to send as version 1." + needsNote, needs)
            }
        }
    }

    /**
     * Sends ONE month as an independent snapshot. [confirmations] are the categories the Admin confirmed for publishers with nothing
     * on record for this month; they are saved to the category history and the audit log, and recorded in the snapshot itself.
     * [expectedVersion] is the version of the month that was validated: if the month has moved on nothing is sent, so a repeated tap or
     * retry can never create a duplicate version. Refused (nothing sent) while any publisher's category is still unknown.
     */
    suspend fun submitMonth(
        congregationId: String, month: Long, actorPersonId: String,
        confirmations: Map<String, PublisherCategory> = emptyMap(),
        expectedVersion: Int? = null,
    ): com.emfitsolutions.gopreach.data.repository.CircuitResult {
        val plan = planMonth(congregationId, month)
        val stillUnknown = plan.needs.filter { it.personId !in confirmations }
        if (stillUnknown.isNotEmpty()) {
            return com.emfitsolutions.gopreach.data.repository.CircuitResult.Conflict(
                "Confirm the " + monthLabel(month) + " service category of " + stillUnknown.size + " publisher" + (if (stillUnknown.size == 1) "" else "s") + " before sending.",
            )
        }
        val actor = actorFor(actorPersonId)
        val confirmedNow = plan.needs.associate { it.personId to confirmations.getValue(it.personId) }
        confirmedNow.forEach { (personId, category) ->
            categoryHistoryRepository.confirm(personId, congregationId, month, category, actorPersonId, note = "Confirmed while sending the " + monthLabel(month) + " report")
            auditLogRepository.log(
                actorPersonId = actorPersonId, action = "CONFIRM_PUBLISHER_CATEGORY_FOR_SNAPSHOT", targetType = "Person", targetId = personId,
                congregationId = congregationId, details = "Month " + monthLabel(month) + ": confirmed " + category,
            )
        }
        val categories = plan.resolved.mapNotNull { (id, r) -> r.category?.let { id to it } }.toMap() + confirmedNow
        val snapshot = snapshotOf(congregationId, month, plan, categories, confirmedNow.keys.associateWith { actorPersonId }, actor.role)
            ?: return com.emfitsolutions.gopreach.data.repository.CircuitResult.Error("The " + monthLabel(month) + " snapshot could not be prepared, so nothing was sent.")
        // The month's historical statistics (publishers, pioneers, elders, reports, meeting attendance) are saved with the submission.
        val existing = attendanceRepository.observeStatistics().first().firstOrNull { it.id == attendanceRepository.statisticsIdFor(congregationId, month) }
        val stats = runCatching { statisticsBuilder.build(congregationId, month, actorPersonId, existing) }.getOrNull()
        return coReportService.submit(congregationId, month, actor, stats, snapshot, expectedVersion)
    }

    /**
     * Sends several months, each as its own independent snapshot, one after another, and reports every month on its own. [expectedVersions]
     * is the version each month had when it was validated; a month that has moved on since is skipped (never sent twice), so a retry after a
     * partial failure only re-sends what failed and a double tap cannot duplicate a snapshot.
     */
    suspend fun submitMonths(
        congregationId: String, months: List<Long>, actorPersonId: String,
        confirmations: Map<Long, Map<String, PublisherCategory>> = emptyMap(),
        expectedVersions: Map<Long, Int> = emptyMap(),
    ): List<MonthSendResult> = months.map { month ->
        val id = com.emfitsolutions.gopreach.data.model.coReportId(congregationId, month)
        val before = coReportRepository.observeStatuses().first().firstOrNull { it.id == id }
        val expected = expectedVersions[month]
        if (expected != null && (before?.version ?: 0) != expected) {
            MonthSendResult(month, MonthSendState.ALREADY_SENT, "Already sent as version " + (before?.version ?: 0) + " - not sent again.", before?.version, before?.submittedAt)
        } else {
            val problem = runCatching { submitMonth(congregationId, month, actorPersonId, confirmations[month].orEmpty(), expected) }
                .fold(
                    onSuccess = { it.messageOrNull() },
                    onFailure = { it.message ?: "Couldn't send this month." },
                )
            if (problem == null) {
                val after = coReportRepository.observeStatuses().first().firstOrNull { it.id == id }
                MonthSendResult(month, MonthSendState.SENT, null, after?.version, after?.submittedAt)
            } else MonthSendResult(month, MonthSendState.FAILED, problem, before?.version, null)
        }
    }

    suspend fun deleteSubmission(congregationId: String, month: Long, actorPersonId: String) =
        coReportService.deleteSubmission(congregationId, month, actorFor(actorPersonId))

    suspend fun undoSubmission(congregationId: String, month: Long, actorPersonId: String) =
        coReportService.undoSubmission(congregationId, month, actorFor(actorPersonId))

    suspend fun receiveMonth(reportId: String, remarks: String?, actorPersonId: String) =
        coReportService.receive(reportId, remarks, actorFor(actorPersonId))

    suspend fun returnMonth(reportId: String, reason: String, actorPersonId: String) =
        coReportService.returnForCorrection(reportId, reason, actorFor(actorPersonId))

    suspend fun saveCoRemarks(reportId: String, remarks: String, actorPersonId: String) =
        coReportService.saveRemarks(reportId, remarks, actorFor(actorPersonId))

    /** The FS Group [personId] belongs to, if any (to pre-select it). */
    fun myGroupId(personId: String): Flow<String?> = roleAssignmentRepository.observeForPerson(personId).map { list ->
        list.firstOrNull { it.status == RoleAssignmentStatus.ACTIVE && it.groupId != null }?.groupId
    }


    // ---------------------------------------------------------------------------------------------
    // List View: every publisher report in the period, with Lock / Unlock.

    /**
     * The report lines for the List View: each publisher's reports in [fromMonth]..[toMonth]
     * (every status — draft, submitted, returned, corrected, posted), one line per report; a
     * publisher with no report in the period gets one "No report" line. [monthStart] is the
     * month a line belongs to (null for a "No report" line).
     */
    fun listItemsFor(groups: List<Group>, fromMonth: Long, toMonth: Long): Flow<List<FieldServiceReportListItem>> =
        combine(
            roleAssignmentRepository.observeAll(),
            personRepository.observeAll(),
            monthlyReportRepository.observeAll(),
        ) { assignments, people, reports ->
            val peopleById = people.associateBy { it.id }
            val start = minOf(fromMonth, toMonth)
            val end = maxOf(fromMonth, toMonth)
            val rangeEnd = Calendar.getInstance().apply { timeInMillis = end; add(Calendar.MONTH, 1) }.timeInMillis
            val inRange = reports.filter { it.periodMonth in start until rangeEnd }
            groups.flatMap { group ->
                assignments
                    .filter { it.status == RoleAssignmentStatus.ACTIVE && it.groupId == group.id }
                    .mapNotNull { a ->
                        val category = (a.resolvedRoleTypeOrNull() as? RoleType.Publisher)?.category ?: return@mapNotNull null
                        if (category == PublisherCategory.REMOVED_PUBLISHER) return@mapNotNull null
                        peopleById[a.personId]?.let { it to category }
                    }
                    .distinctBy { it.first.id }
                    .flatMap { (person, assignedCategory) ->
                        val name = person.lastName.trim() + ", " + person.firstName.trim()
                        val own = inRange.filter { it.publisherPersonId == person.id }.sortedBy { it.periodMonth }
                        if (own.isEmpty()) {
                            listOf(FieldServiceReportListItem(group.name, name, StatusColumn.of(assignedCategory), null, null))
                        } else {
                            own.map { r -> FieldServiceReportListItem(group.name, name, StatusColumn.of(r.category) ?: StatusColumn.of(assignedCategory), monthLabel(r.periodMonth), r) }
                        }
                    }
            }.sortedWith(compareBy<FieldServiceReportListItem, String>(com.emfitsolutions.gopreach.domain.NaturalOrder.comparator) { it.groupName }.thenBy { it.publisherName.lowercase() }.thenBy { it.report?.periodMonth ?: 0L })
        }

    /** "Lock" — marks a submitted report Posted: the publisher can no longer edit it. */
    /**
     * A group role (Group Coordinator / Servant / Assistant, [scopeGroupId]) may only touch publishers of its own FS Group, and the
     * server verifies that through the publisher's own role assignment — so the record is stamped with it, and nothing is written
     * for a publisher outside the group.
     */
    private suspend fun stampedForGroup(report: MonthlyReport, scopeGroupId: String?): MonthlyReport? {
        if (scopeGroupId == null) return report
        val assignment = roleAssignmentRepository.observeAll().first().firstOrNull {
            it.personId == report.publisherPersonId && it.groupId == scopeGroupId && it.status == RoleAssignmentStatus.ACTIVE &&
                it.resolvedRoleTypeOrNull() is RoleType.Publisher
        } ?: return null
        return report.copy(groupRoleAssignmentId = assignment.id)
    }

    fun lock(report: MonthlyReport, actorPersonId: String, scopeGroupId: String? = null) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val target = stampedForGroup(report, scopeGroupId) ?: return@launch
            // saveNow, not save: a lock change has to reach the publisher's device promptly.
            monthlyReportRepository.saveNow(
                target.copy(status = ReportStatus.POSTED, reviewedByPersonId = actorPersonId, reviewedAt = now, lastEditedByPersonId = actorPersonId, lastEditedAt = now),
            )
            auditLogRepository.log(actorPersonId = actorPersonId, action = "MARK_PUBLISHER_REPORT_POSTED", targetType = "MonthlyReport", targetId = report.id, congregationId = report.congregationId)
        }
    }

    /** "Unlock" — puts the report back to Draft so the publisher can edit it and submit it again. */
    fun unlock(report: MonthlyReport, actorPersonId: String, scopeGroupId: String? = null) {
        viewModelScope.launch {
            val target = stampedForGroup(report, scopeGroupId) ?: return@launch
            monthlyReportRepository.saveNow(
                target.copy(status = ReportStatus.DRAFT, lastEditedByPersonId = actorPersonId, lastEditedAt = System.currentTimeMillis()),
            )
            auditLogRepository.log(actorPersonId = actorPersonId, action = "UNLOCK_PUBLISHER_REPORT", targetType = "MonthlyReport", targetId = report.id, congregationId = report.congregationId)
        }
    }

    /**
     * Publisher-level access decisions, by the person in charge (congregation-wide roles, or the group role of that publisher's FS Group):
     *  Access Requested → Access Granted (the publisher may correct, then must submit again), or back to Submitted (rejected);
     *  Submitted → Reversed (reopened outright, no request needed). Each one is audited.
     */
    fun approveAccess(report: MonthlyReport, actorPersonId: String, scopeGroupId: String? = null) {
        viewModelScope.launch {
            val target = stampedForGroup(report, scopeGroupId) ?: return@launch
            val now = System.currentTimeMillis()
            monthlyReportRepository.saveNow(
                target.copy(
                    status = ReportStatus.ACCESS_GRANTED, accessGrantedByPersonId = actorPersonId, accessGrantedAt = now,
                    lastEditedByPersonId = actorPersonId, lastEditedAt = now,
                ),
            )
            auditLogRepository.log(
                actorPersonId = actorPersonId, action = "PUBLISHER_REPORT_ACCESS_GRANTED", targetType = "MonthlyReport", targetId = report.id,
                congregationId = report.congregationId, details = report.accessRequestReason,
            )
        }
    }

    fun rejectAccess(report: MonthlyReport, reason: String, actorPersonId: String, scopeGroupId: String? = null) {
        viewModelScope.launch {
            val target = stampedForGroup(report, scopeGroupId) ?: return@launch
            val now = System.currentTimeMillis()
            monthlyReportRepository.saveNow(
                target.copy(
                    status = ReportStatus.SUBMITTED, accessDecisionNote = reason.trim().ifBlank { null },
                    lastEditedByPersonId = actorPersonId, lastEditedAt = now,
                ),
            )
            auditLogRepository.log(
                actorPersonId = actorPersonId, action = "PUBLISHER_REPORT_ACCESS_REJECTED", targetType = "MonthlyReport", targetId = report.id,
                congregationId = report.congregationId, details = reason.trim(),
            )
        }
    }

    fun reverseSubmission(report: MonthlyReport, reason: String, actorPersonId: String, scopeGroupId: String? = null) {
        if (reason.isBlank()) return
        viewModelScope.launch {
            val target = stampedForGroup(report, scopeGroupId) ?: return@launch
            val now = System.currentTimeMillis()
            monthlyReportRepository.saveNow(
                target.copy(
                    status = ReportStatus.RETURNED, returnedByPersonId = actorPersonId, returnedAt = now, correctionReason = reason.trim(),
                    lastEditedByPersonId = actorPersonId, lastEditedAt = now,
                ),
            )
            auditLogRepository.log(
                actorPersonId = actorPersonId, action = "PUBLISHER_REPORT_REVERSED", targetType = "MonthlyReport", targetId = report.id,
                congregationId = report.congregationId, details = reason.trim(),
            )
        }
    }

    /** Locks every submitted/corrected report in [items] at once. */
    fun lockAll(items: List<FieldServiceReportListItem>, actorPersonId: String, scopeGroupId: String? = null) {
        items.mapNotNull { it.report }.filter { it.status == ReportStatus.SUBMITTED || it.status == ReportStatus.CORRECTED }.forEach { lock(it, actorPersonId, scopeGroupId) }
    }

    /**
     * Month-by-month figures for the Comparative Graph Report over [range], for exactly the publishers the report
     * sheets cover: the same scope rule as [sheetsFor] (the selected FS Group(s), or the whole congregation), active
     * non-removed publishers only, so the graph can never include another congregation or group. Reports are the
     * submitted/posted ones, latest per publisher per month; credit hours come from the same publishers' credit records.
     */
    fun monthlyMetrics(groups: List<Group>, range: com.emfitsolutions.gopreach.ui.components.MonthRange, wholeCongregation: Congregation? = null): Flow<List<MonthMetrics>> =
        combine(
            roleAssignmentRepository.observeAll(),
            monthlyReportRepository.observeAll(),
            creditHourRecordRepository.observeAll(),
        ) { assignments, reports, credits ->
            val groupIds = groups.map { it.id }.toSet()
            val memberIds = assignments
                .filter {
                    it.status == RoleAssignmentStatus.ACTIVE &&
                        if (wholeCongregation != null) it.congregationId == wholeCongregation.id else it.groupId in groupIds
                }
                .filter { a -> (a.resolvedRoleTypeOrNull() as? RoleType.Publisher)?.let { it.category != PublisherCategory.REMOVED_PUBLISHER } == true }
                .map { it.personId }
                .toSet()
            range.months().map { monthStart ->
                val next = Calendar.getInstance().apply { timeInMillis = monthStart; add(Calendar.MONTH, 1) }.timeInMillis
                val monthReports = reports
                    .filter { it.publisherPersonId in memberIds && it.periodMonth in monthStart until next && it.isSubmittedOrPosted }
                    .groupBy { it.publisherPersonId }
                    .map { (_, list) -> list.maxByOrNull { it.submittedAt ?: it.lastEditedAt ?: 0L }!! }
                MonthMetrics(
                    monthStart = monthStart,
                    hours = monthReports.sumOf { it.hoursRendered ?: 0.0 },
                    creditMinutes = credits.filter { it.publisherPersonId in memberIds && it.dayStart in monthStart until next }.sumOf { it.totalMinutes },
                    returnVisits = monthReports.sumOf { it.returnVisitsCount },
                    bibleStudies = monthReports.sumOf { it.bibleStudiesCount },
                    reports = monthReports.size,
                )
            }
        }

    /**
     * One report sheet per group in [groups], for the months from [fromMonth] to [toMonth] (both
     * month-start millis, inclusive — pass the same month twice for a single month). A range is
     * totalled per publisher: No. of Reports is how many months they reported, Hours and Bible
     * Studies are summed, and the status column is the one of their latest report in the range
     * (their current status if they never reported).
     */
    fun sheetsFor(
        groups: List<Group>,
        fromMonth: Long,
        toMonth: Long,
        /** "Group by Congregation": instead of one sheet per FS Group, ONE sheet for this whole congregation
         * (every FS Group's publishers together, plus any publisher without a group). [groups] are then only used to
         * name each publisher's FS Group in the Remarks. */
        wholeCongregation: Congregation? = null,
        /** For a CO snapshot: the category each publisher had IN the month (publisher id -> category). A publisher missing from it falls back to the
         * live behaviour; the snapshot builder only ever passes a complete map, so a current profile is never used as a guess. */
        categoryOverride: Map<String, PublisherCategory>? = null,
    ): Flow<List<FieldServiceReportSheet>> =
        combine(
            roleAssignmentRepository.observeAll(),
            personRepository.observeAll(),
            monthlyReportRepository.observeAll(),
        ) { assignments, people, reports ->
            val peopleById = people.associateBy { it.id }
            val start = minOf(fromMonth, toMonth)
            val end = maxOf(fromMonth, toMonth)
            val rangeEnd = Calendar.getInstance().apply { timeInMillis = end; add(Calendar.MONTH, 1) }.timeInMillis
            val range = start until rangeEnd
            val monthCount = monthsBetween(start, end)
            val isRange = monthCount > 1
            val periodLabel = monthLabel(start) + if (isRange) " to " + monthLabel(end) else ""
            val inRange = reports.filter { it.periodMonth in range && it.isSubmittedOrPosted }

            val groupNameById = groups.associate { it.id to it.name }
            // One scope per sheet: each FS Group, or the whole congregation as a single scope.
            val scopes: List<Group> =
                if (wholeCongregation != null) listOf(Group(id = "__congregation__", congregationId = wholeCongregation.id, name = wholeCongregation.name))
                else groups
            scopes.map { group ->
                // The scope's publishers (one line per person), excluding Removed.
                val members = assignments
                    .filter {
                        it.status == RoleAssignmentStatus.ACTIVE &&
                            if (wholeCongregation != null) it.congregationId == wholeCongregation.id else it.groupId == group.id
                    }
                    .mapNotNull { a ->
                        val category = (a.resolvedRoleTypeOrNull() as? RoleType.Publisher)?.category ?: return@mapNotNull null
                        if (category == PublisherCategory.REMOVED_PUBLISHER) return@mapNotNull null
                        val person = peopleById[a.personId] ?: return@mapNotNull null
                        person to category
                    }
                    .distinctBy { it.first.id }
                val groupIdOf = assignments
                    .filter { it.status == RoleAssignmentStatus.ACTIVE && it.groupId != null && it.resolvedRoleTypeOrNull() is RoleType.Publisher }
                    .associate { it.personId to it.groupId }

                val rows = members
                    .map { (person, assignedCategory) ->
                        // One report per month at most; latest-submitted wins within a month.
                        val own = inRange.filter { it.publisherPersonId == person.id }
                            .groupBy { it.periodMonth }
                            .map { (_, list) -> list.maxByOrNull { it.submittedAt ?: it.lastEditedAt ?: 0L }!! }
                            .sortedBy { it.periodMonth }
                        // The status of the latest report in the period; otherwise their current one.
                        val status = categoryOverride?.get(person.id)?.let { StatusColumn.of(it) }
                            ?: if (categoryOverride?.containsKey(person.id) == true) null
                            else StatusColumn.of(own.lastOrNull()?.category ?: assignedCategory) ?: StatusColumn.of(assignedCategory)
                        Triple(person, own, status)
                    }
                    .filter { it.third != null }
                    .sortedWith(compareBy({ it.first.lastName.lowercase() }, { it.first.firstName.lowercase() }))
                    .mapIndexed { index, (person, own, status) ->
                        status!!
                        // Remarks are only what was actually entered — never generated text (no "No report submitted", group names or counts).
                        val remarks = buildList {
                            own.mapNotNull { it.remarks?.trim()?.takeIf { r -> r.isNotEmpty() } }.distinct().forEach { add(it) }
                        }.joinToString("; ")
                        FieldServiceReportRow(
                            personId = person.id,
                            number = index + 1,
                            status = status,
                            name = person.lastName.trim() + ", " + person.firstName.trim(),
                            reportsCount = 1,
                            hasReport = own.isNotEmpty(),
                            // Hours only exist for pioneer statuses; Bible Studies for every status.
                            hours = if (status.isPioneer && own.isNotEmpty()) own.sumOf { it.hoursRendered ?: 0.0 } else null,
                            bibleStudies = if (own.isNotEmpty()) own.sumOf { it.bibleStudiesCount } else null,
                            remarks = remarks,
                        )
                    }

                val present = rows.map { it.status }.toSet()
                val reportColumns = StatusColumn.entries.filter { it.alwaysShown || it in present }
                fun nameOf(id: String?) = id?.let { peopleById[it]?.fullName } ?: "None"
                FieldServiceReportSheet(
                    groupName = group.name,
                    monthLabel = periodLabel,
                    isRange = isRange,
                    overseer = nameOf(group.overseerPersonId),
                    servant = nameOf(group.servantPersonId),
                    assistant = nameOf(group.assistantPersonId),
                    reportColumns = reportColumns,
                    hourColumns = reportColumns.filter { it.isPioneer },
                    rows = rows,
                    officerRows = if (wholeCongregation != null) listOf(
                        "Congregation:" to wholeCongregation.name,
                        "FS Groups:" to (if (groups.isEmpty()) "None" else "All FS Groups (${groups.size})"),
                        "" to "",
                    ) else listOf(
                        "Group Overseer:" to nameOf(group.overseerPersonId),
                        "Group Servant:" to nameOf(group.servantPersonId),
                        "Group Assistant:" to nameOf(group.assistantPersonId),
                    ),
                )
            }
        }
}

private fun monthLabel(monthStart: Long): String = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(monthStart))

/** Number of calendar months from [start] to [end], inclusive (same month = 1). */
private fun monthsBetween(start: Long, end: Long): Int {
    val a = Calendar.getInstance().apply { timeInMillis = start }
    val b = Calendar.getInstance().apply { timeInMillis = end }
    return (b.get(Calendar.YEAR) - a.get(Calendar.YEAR)) * 12 + (b.get(Calendar.MONTH) - a.get(Calendar.MONTH)) + 1
}
