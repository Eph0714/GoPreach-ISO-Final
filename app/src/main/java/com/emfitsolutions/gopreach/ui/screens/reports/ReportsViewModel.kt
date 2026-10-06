package com.emfitsolutions.gopreach.ui.screens.reports

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.R
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.GroupRepository
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.data.repository.MonthlyReportRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.domain.DateRangeStore
import com.emfitsolutions.gopreach.ui.components.DateRange
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class PublisherReportRow(
    val person: Person,
    /** The publisher's *current* category (their RoleAssignment right now)
     * — what the row's own "Status" column shows, a present-tense "who is
     * this person" label. Deliberately **not** what [hoursByReportCategory]
     * sums by; see that field's own doc comment for why those two need to
     * stay independent. */
    val category: PublisherCategory,
    val totalBibleStudies: Int,
    val totalHours: Double,
    val totalInterestedPeople: Int,
    /** [com.emfitsolutions.gopreach.data.model.MonthlyReport
     * .participatedInPreaching] — null for Pioneer categories, since that
     * field is never used for them (see [MonthlyReport]'s own table); the
     * screen renders that as "N/A", true/false as "Yes"/"No". When more than
     * one report falls in the selected range, true wins over false (any
     * month attended counts as "attended this period"), and false wins over
     * a report that left the field unset. */
    val attendedPreaching: Boolean?,
    /** Bug fix ("total hours of regular pioneer is not correct in pdf
     * report"): hours actually reported, grouped by *that report's own*
     * [com.emfitsolutions.gopreach.data.model.MonthlyReport.category]
     * snapshot — not by [category] above. A publisher who was a Regular
     * Pioneer in July but is a Regular Publisher by the time this report is
     * viewed/exported still had those July hours logged as a Regular
     * Pioneer; attributing them to [category] (their *current* standing)
     * instead silently moved that person's hours into whichever category
     * they happen to hold today, over- or under-counting both categories'
     * "Total Hours for..." summary the moment anyone's category ever
     * changes. [GroupReportSection.categoryHours] sums from this map, the
     * same "snapshot what mattered at the time" [MonthlyReport.category]
     * itself already exists for — matching how
     * [com.emfitsolutions.gopreach.ui.screens.dashboard.CongregationStats
     * .compute]'s own regularPioneerHours/auxiliaryPioneerHours already
     * filter by the report's category, not the assignment's. */
    val hoursByReportCategory: Map<PublisherCategory, Double>,
    /** "Separate the Auxiliary and Regular Pioneer Bible Study" — same
     * "attribute to the report's own category snapshot, not the publisher's
     * current one" fix as [hoursByReportCategory], applied to
     * bibleStudiesCount too. */
    val bibleStudiesByReportCategory: Map<PublisherCategory, Int>,
)

/** The three Pioneer categories a report breaks hours/Bible Studies out by, in
 * display order. */
val PIONEER_CATEGORIES = listOf(
    PublisherCategory.REGULAR_PIONEER,
    PublisherCategory.SPECIAL_PIONEER,
    PublisherCategory.AUXILIARY_PIONEER,
)

/** One Group's worth of the Publisher Report — "Group the record by Group"
 * (example: "Group: 5 / Group Overseer: ... / Group Servant: ... / Group
 * Assistant: ...", one publisher table, then a per-group summary total).
 * [groupId] null is the "Unassigned" bucket for a Publisher with no Group
 * set — everyone still shows up somewhere rather than silently vanishing
 * from the report. */
data class GroupReportSection(
    val groupId: String?,
    val groupName: String,
    val overseerName: String?,
    val servantName: String?,
    val assistantName: String?,
    val rows: List<PublisherReportRow>,
) {
    /** "SUMMARY TOTAL FOR THE MONTH OF ..." — a publisher-count per category
     * (a map, not one field per category, so every [PublisherCategory] this
     * group actually holds is covered without hard-coding the four the
     * example happens to show). */
    val categoryCounts: Map<PublisherCategory, Int> get() = rows.categoryCounts()

    /** "TOTAL HOURS FOR REGULAR PIONEER / AUXILIARY PIONEER" — see
     * [categoryHours] (the shared extension) for how this is derived from
     * each row's *reported* category rather than its current one. */
    val categoryHours: Map<PublisherCategory, Double> get() = rows.categoryHours()

    /** "Total of hours per group, categorized by Regular / Auxiliary / Special
     * Pioneer" — [categoryHours] restricted to the three Pioneer categories
     * and always listing all three (0.0 when this group has no hours for one),
     * so every group's summary reads the same instead of a category silently
     * missing. Still summed by each report's own category snapshot. */
    val pioneerHours: Map<PublisherCategory, Double> get() = categoryHours.let { byCategory ->
        PIONEER_CATEGORIES.associateWith { byCategory[it] ?: 0.0 }
    }

    /** Every hour this group's publishers reported, whatever their category
     * (Pioneers, Regular/Unbaptized Publishers) — the overall group total the
     * three Pioneer lines above are part of. */
    val totalHours: Double get() = rows.sumOf { it.totalHours }

    /** "Separate the Auxiliary and Regular Pioneer... Bible Study" — see
     * [categoryBibleStudies] (the shared extension). */
    val categoryBibleStudies: Map<PublisherCategory, Int> get() = rows.categoryBibleStudies()
}

/** "Edit the Report. Separate the Total report of Regular Pioneers and
 * Auxiliary Pioneer" — shared by [GroupReportSection] (one Group's rows)
 * and the "All Publishers" congregation-wide summary (every row across
 * every Group at once), so both levels of the report break "Regular
 * Pioneer" and "Auxiliary Pioneer" out as their own distinct totals instead
 * of folding them into one combined figure. */
fun List<PublisherReportRow>.categoryCounts(): Map<PublisherCategory, Int> = groupingBy { it.category }.eachCount()

/** See [PublisherReportRow.hoursByReportCategory]'s doc comment for why this
 * sums each row's *reported* category, not its current one — a publisher
 * who changed category mid-period must not have their earlier hours
 * silently move into whichever category they hold today. */
fun List<PublisherReportRow>.categoryHours(): Map<PublisherCategory, Double> = flatMap { it.hoursByReportCategory.entries }
    .filter { it.key == PublisherCategory.REGULAR_PIONEER || it.key == PublisherCategory.SPECIAL_PIONEER || it.key == PublisherCategory.AUXILIARY_PIONEER }
    .groupBy({ it.key }, { it.value })
    .mapValues { (_, hours) -> hours.sum() }

/** See [PublisherReportRow.bibleStudiesByReportCategory]'s doc comment — same
 * "sum by the report's own category, not the publisher's current one" fix as
 * [categoryHours], applied to Bible Studies. */
fun List<PublisherReportRow>.categoryBibleStudies(): Map<PublisherCategory, Int> = flatMap { it.bibleStudiesByReportCategory.entries }
    .filter { it.key == PublisherCategory.REGULAR_PIONEER || it.key == PublisherCategory.SPECIAL_PIONEER || it.key == PublisherCategory.AUXILIARY_PIONEER }
    .groupBy({ it.key }, { it.value })
    .mapValues { (_, counts) -> counts.sum() }

/**
 * Spec §5.1 — "View publisher records/reports": Bible studies, interested
 * people, and preaching hours, per publisher / per Group / per congregation.
 * "Group the record by Group" — [groupedRowsFor] is the shape the screen
 * actually renders; the flat, ungrouped [rowsFor] stays for the "All
 * Publishers" summary-total row at the very top of the screen.
 *
 * "Main Form Date Range Filtering" spec §7 — [dateRange] scopes both figures
 * that actually have a point-in-time dimension: [MonthlyReport.periodMonth]
 * (via [DateRange.overlapsMonth]) and [InterestedPerson.createdAt] (a real
 * timestamp, checked directly). `null` means "no filter" (all-time), used by
 * callers that haven't opted into date scoping.
 */
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val personRepository: PersonRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val monthlyReportRepository: MonthlyReportRepository,
    private val interestedPersonRepository: InterestedPersonRepository,
    private val groupRepository: GroupRepository,
    private val congregationRepository: CongregationRepository,
    private val dateRangeStore: DateRangeStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** Spec §8 — the same selected [DateRange] the Dashboard's Reports screen
     * reads/writes (see [DateRangeStore]), so switching between the two never
     * silently resets back to a default the user didn't choose. */
    val dateRange: StateFlow<DateRange> = dateRangeStore.range
    fun setDateRange(range: DateRange) = dateRangeStore.set(range)

    /** "Select a congregation for Super Admin" — the screen only shows this
     * picker when it's actually reached with no fixed congregation (i.e.
     * Super-Admin; every other role is already scoped upstream in
     * GoPreachNavGraph and has nothing to pick from). */
    val congregations: StateFlow<List<Congregation>> =
        congregationRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** "For Admin, Elders and Service overseer select [All Group, Per Group]
     * filter" — every Group within [congregationId] (`null` → none, so the
     * picker starts empty rather than showing every congregation's groups
     * at once before a congregation is actually chosen/known). */
    fun groupsFor(congregationId: String?): Flow<List<Group>> =
        groupRepository.observeAll().map { groups ->
            if (congregationId == null) emptyList() else groups.filter { it.congregationId == congregationId }.sortedWith(com.emfitsolutions.gopreach.domain.GroupNameOrder)
        }

    /** [visibleGroupId] narrows further for a Regular Elder (own group only, spec
     * §3 permission matrix); leave null for congregation-wide or all-congregations roles. */
    fun rowsFor(
        visibleCongregationId: String?,
        visibleGroupId: String? = null,
        dateRange: DateRange? = null,
    ): Flow<List<PublisherReportRow>> =
        combine(
            personRepository.observeAll(),
            roleAssignmentRepository.observeAll(),
            monthlyReportRepository.observeAll(),
            interestedPersonRepository.observeAll(),
        ) { people, assignments, reports, interestedPeople ->
            buildRows(people, assignments, reports, interestedPeople, visibleCongregationId, visibleGroupId, dateRange)
                .sortedBy { it.person.fullName }
        }

    /** "Group the record by Group" — same rows [rowsFor] produces, bucketed
     * by [com.emfitsolutions.gopreach.data.model.RoleAssignment.groupId] and
     * paired with that Group's own Overseer/Servant/Assistant names for the
     * section header the example shows. Sections are sorted by Group name,
     * with "Unassigned" (no Group set) always last. */
    fun groupedRowsFor(
        visibleCongregationId: String?,
        visibleGroupId: String? = null,
        dateRange: DateRange? = null,
    ): Flow<List<GroupReportSection>> =
        combine(
            personRepository.observeAll(),
            roleAssignmentRepository.observeAll(),
            monthlyReportRepository.observeAll(),
            interestedPersonRepository.observeAll(),
            groupRepository.observeAll(),
        ) { people, assignments, reports, interestedPeople, groups ->
            val peopleById = people.associateBy { it.id }
            // Filtered to the Publisher-role assignment specifically — a
            // person can simultaneously hold a non-Publisher RoleAssignment
            // (e.g. Coordinator Elder), and a plain associateBy over every
            // assignment they hold would risk keying off whichever one
            // happened to iterate last, silently grouping this row under the
            // wrong (or no) Group.
            val assignmentByPerson = assignments
                .filter { it.resolvedRoleTypeOrNull() is RoleType.Publisher }
                .associateBy { it.personId }
            val rows = buildRows(people, assignments, reports, interestedPeople, visibleCongregationId, visibleGroupId, dateRange)
            val groupsById = groups.associateBy { it.id }
            val visibleGroups = groups.filter { visibleCongregationId == null || it.congregationId == visibleCongregationId }
                .filter { visibleGroupId == null || it.id == visibleGroupId }

            val rowsByGroupId = rows.groupBy { assignmentByPerson[it.person.id]?.groupId }
            val assignedSections = visibleGroups
                .filter { rowsByGroupId.containsKey(it.id) }
                .map { group ->
                    GroupReportSection(
                        groupId = group.id,
                        groupName = group.name,
                        overseerName = group.overseerPersonId?.let { peopleById[it]?.fullName },
                        servantName = group.servantPersonId?.let { peopleById[it]?.fullName },
                        assistantName = group.assistantPersonId?.let { peopleById[it]?.fullName },
                        rows = rowsByGroupId[group.id].orEmpty().sortedBy { it.person.fullName },
                    )
                }
                .sortedWith(com.emfitsolutions.gopreach.domain.NaturalOrder.by { it.groupName })

            // A Publisher whose RoleAssignment.groupId points at a Group this
            // caller isn't scoped to see (or that no longer exists) still
            // needs to land somewhere — "Unassigned" is also where a
            // genuinely groupless Publisher (groupId == null) ends up.
            val accountedGroupIds = assignedSections.mapNotNull { it.groupId }.toSet()
            val unassignedRows = rowsByGroupId.entries
                .filter { (groupId, _) -> groupId == null || groupId !in groupsById || groupId !in accountedGroupIds }
                .flatMap { it.value }
                .sortedBy { it.person.fullName }

            if (unassignedRows.isEmpty()) {
                assignedSections
            } else {
                assignedSections + GroupReportSection(
                    groupId = null,
                    groupName = context.getString(R.string.reports_unassigned),
                    overseerName = null,
                    servantName = null,
                    assistantName = null,
                    rows = unassignedRows,
                )
            }
        }

    private fun buildRows(
        people: List<Person>,
        assignments: List<com.emfitsolutions.gopreach.data.model.RoleAssignment>,
        reports: List<com.emfitsolutions.gopreach.data.model.MonthlyReport>,
        interestedPeople: List<com.emfitsolutions.gopreach.data.model.InterestedPerson>,
        visibleCongregationId: String?,
        visibleGroupId: String?,
        dateRange: DateRange?,
    ): List<PublisherReportRow> = assignments
        .filter { it.resolvedRoleTypeOrNull() is RoleType.Publisher }
        .filter { (it.resolvedRoleTypeOrNull() as RoleType.Publisher).category != PublisherCategory.REMOVED_PUBLISHER }
        .filter { visibleCongregationId == null || it.congregationId == visibleCongregationId }
        .filter { visibleGroupId == null || it.groupId == visibleGroupId }
        .mapNotNull { assignment ->
            val person = people.firstOrNull { it.id == assignment.personId } ?: return@mapNotNull null
            val category = (assignment.resolvedRoleTypeOrNull() as RoleType.Publisher).category
            val personReports = reports.filter { it.publisherPersonId == person.id }
                .filter { dateRange == null || dateRange.overlapsMonth(it.periodMonth) }
            val personInterested = interestedPeople.filter { it.publisherPersonId == person.id }
                .filter { dateRange == null || it.createdAt in dateRange }
            val attendedPreaching = personReports.mapNotNull { it.participatedInPreaching }
                .let { flags -> if (flags.isEmpty()) null else flags.any { it } }
            PublisherReportRow(
                person = person,
                category = category,
                totalBibleStudies = personReports.sumOf { it.bibleStudiesCount },
                totalHours = personReports.sumOf { it.hoursRendered ?: 0.0 },
                totalInterestedPeople = personInterested.size,
                attendedPreaching = attendedPreaching,
                // Bug fix ("total hours of regular pioneer is not correct"):
                // grouped by each report's own category snapshot, not the
                // person's current one — see the field's own doc comment.
                hoursByReportCategory = personReports
                    .groupBy { it.category }
                    .mapValues { (_, reportsForCategory) -> reportsForCategory.sumOf { it.hoursRendered ?: 0.0 } },
                bibleStudiesByReportCategory = personReports
                    .groupBy { it.category }
                    .mapValues { (_, reportsForCategory) -> reportsForCategory.sumOf { it.bibleStudiesCount } },
            )
        }
}
