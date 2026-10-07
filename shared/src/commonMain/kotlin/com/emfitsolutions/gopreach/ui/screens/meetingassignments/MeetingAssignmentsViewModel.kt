package com.emfitsolutions.gopreach.ui.screens.meetingassignments

import com.emfitsolutions.gopreach.platform.nowMillis

import com.emfitsolutions.gopreach.platform.SimpleDateFormat
import com.emfitsolutions.gopreach.platform.Locale
import com.emfitsolutions.gopreach.platform.Date
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.CartAssignmentRow
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.MidweekAssignmentItem
import com.emfitsolutions.gopreach.data.model.MidweekMeetingSchedule
import com.emfitsolutions.gopreach.data.model.MidweekSection
import com.emfitsolutions.gopreach.data.model.PublicTalkScheduleRow
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.CartAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.MidweekMeetingScheduleRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.PublicTalkScheduleRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.emfitsolutions.gopreach.platform.Calendar

/** Midnight, this week's Monday — spec's own example, "Week for August
 * 31-September 6," is a Monday-Sunday span, matching
 * [com.emfitsolutions.gopreach.ui.components.DateRangeSelection.DateRange
 * .Companion.thisWeek]'s already-established Monday convention. */
fun mondayOfWeek(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    val currentDow = get(Calendar.DAY_OF_WEEK)
    val daysSinceMonday = ((currentDow - Calendar.MONDAY) + 7) % 7
    add(Calendar.DAY_OF_MONTH, -daysSinceMonday)
}.timeInMillis

/** "My Assignments" — one entry across any of this module's three record
 * types, all normalized to a single [sortKey] so they can share one
 * chronological list ([MyAssignmentsScreen]). */
sealed interface MyAssignmentRow {
    val sortKey: Long

    data class Midweek(
        val weekStart: Long,
        val sectionLabel: String,
        val particular: String,
        val durationMinutes: String,
        val assignedTo: String,
    ) : MyAssignmentRow {
        override val sortKey: Long get() = weekStart
    }

    /** [matchedRoles] is which of [row]'s own role fields actually named this
     * publisher — usually one ("Speaker"), occasionally more than one if the
     * same person is filling two roles on the same date. */
    data class PublicTalk(val row: PublicTalkScheduleRow, val matchedRoles: List<String>) : MyAssignmentRow {
        override val sortKey: Long get() = row.date
    }

    data class Cart(val row: CartAssignmentRow) : MyAssignmentRow {
        override val sortKey: Long get() = row.date
    }
}

/** Whole-word, case-insensitive search for any word of [personName] inside
 * [text] — the closest a *free-text* assignee field (see this module's own
 * doc comments: "Assigned To"/"Speaker"/"Publishers" etc. are never a
 * personId reference) can get to "does this record name this publisher."
 * Word-bounded so "Eva" matches "Eva and Lita" but not "Evangeline"; matches
 * on any single word of the full name (not the whole name at once) so
 * "Eva Reyes" still matches a field that only wrote "Eva". This is
 * inherently best-effort, not a guaranteed exact match — a full name shared
 * by two publishers, or a field spelled differently than the roster, won't
 * resolve perfectly, same limitation the free-text field itself already has. */
private fun mentionsPerson(text: String, personName: String): Boolean {
    if (text.isBlank() || personName.isBlank()) return false
    val words = personName.trim().split(Regex("\\s+")).filter { it.length > 1 }
    return words.any { word -> Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) }
}

/**
 * "Meeting Assignments" module — Coordinator Elder/Regular Elder/Service
 * Overseer/Admin (own congregation)/Super-Admin (every congregation) enroll
 * both the Midweek Meeting Schedule and the Public Talk and Watchtower
 * Study Schedule; every Publisher sees their own congregation's copy,
 * read-only.
 */
class MeetingAssignmentsViewModel(
    private val midweekRepository: MidweekMeetingScheduleRepository,
    private val publicTalkRepository: PublicTalkScheduleRepository,
    private val cartAssignmentRepository: CartAssignmentRepository,
    private val congregationRepository: CongregationRepository,
    private val personRepository: PersonRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val auditLogRepository: AuditLogRepository,
    private val recycleBinRepository: com.emfitsolutions.gopreach.data.repository.RecycleBinRepository,
) : ViewModel() {

    val congregations: StateFlow<List<Congregation>> =
        congregationRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** "In assigning publishers, browse it from the publishers record" —
     * every active person's full name in [congregationId] (Publisher *and*
     * Admin-track roles alike, e.g. a Chairman/Watchtower Conductor
     * assignment is usually a Regular/Coordinator Elder, not a Publisher-
     * category role) — feeds the Assigned To/Speaker/Chairman/Publishers
     * autocomplete field in every one of this module's three dialogs
     * (Midweek, Public Talk, Cart Assignment). A Removed Publisher is
     * excluded (they can no longer sign in and shouldn't be assignable to
     * new work), same convention [com.emfitsolutions.gopreach.ui.screens
     * .pipeline.PipelineViewModel.otherPublishers] already uses; every
     * Admin-track role is unaffected by that check (only [RoleType.Publisher]
     * carries a [PublisherCategory] to filter on). The field stays free-text
     * regardless — this is suggestions only, never an enum — so an assignee
     * who isn't in the roster yet (a visiting speaker) can still be typed in
     * manually. */
    fun rosterNamesFor(congregationId: String?): Flow<List<String>> = combine(
        personRepository.observeAll(),
        roleAssignmentRepository.observeAll(),
    ) { people, assignments ->
        if (congregationId == null) return@combine emptyList()
        val peopleById = people.associateBy { it.id }
        assignments
            .filter { it.status == RoleAssignmentStatus.ACTIVE && it.congregationId == congregationId }
            .filter { (it.resolvedRoleTypeOrNull() as? RoleType.Publisher)?.category != PublisherCategory.REMOVED_PUBLISHER }
            .mapNotNull { peopleById[it.personId] }
            .map { it.fullName }
            .distinct()
            .sorted()
    }

    /** The one schedule doc (if any) for [congregationId]/[weekStartDate] —
     * null means nothing has been enrolled for that week yet, not an error. */
    fun scheduleFor(congregationId: String, weekStartDate: Long): Flow<MidweekMeetingSchedule?> =
        midweekRepository.observeAll().map { list ->
            list.firstOrNull { it.congregationId == congregationId && it.weekStartDate == weekStartDate }
        }

    /** Replaces one sub-category's whole particulars list at once — the
     * caller (an Add/Edit/Delete/Reorder in the section's own dialog) always
     * has the full intended list in hand already, so there's no per-item
     * Firestore write to reconcile. Creates the week's schedule doc on first
     * save for that congregation/week; every later save for the same one
     * edits it in place instead of creating a duplicate. */
    fun saveSectionItems(
        existing: MidweekMeetingSchedule?,
        congregationId: String,
        weekStartDate: Long,
        section: MidweekSection,
        items: List<MidweekAssignmentItem>,
        actorPersonId: String,
    ) {
        viewModelScope.launch {
            val now = nowMillis()
            val base = existing ?: MidweekMeetingSchedule(
                congregationId = congregationId,
                weekStartDate = weekStartDate,
                createdByPersonId = actorPersonId,
                createdAt = now,
            )
            val updated = base.withItems(section, items).copy(lastEditedByPersonId = actorPersonId, lastEditedAt = now)
            midweekRepository.save(updated)
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "SAVE_MIDWEEK_MEETING_SCHEDULE",
                targetType = "MidweekMeetingSchedule",
                targetId = updated.id.ifBlank { "new" },
                congregationId = congregationId,
                details = section.displayLabel,
            )
        }
    }

    fun rowsFor(congregationId: String?): Flow<List<PublicTalkScheduleRow>> =
        publicTalkRepository.observeAll().map { list ->
            list.filter { congregationId == null || it.congregationId == congregationId }
                .sortedBy { it.date }
        }

    /** "Do not duplicate date" — checked here, not just left to the UI,
     * against every other row in the same congregation (excluding the row
     * being edited, if any). Returns an error message to show instead of
     * saving, or null once it's actually saved. */
    suspend fun savePublicTalkRow(
        existingRows: List<PublicTalkScheduleRow>,
        row: PublicTalkScheduleRow,
        actorPersonId: String,
    ): String? {
        val duplicate = existingRows.any { it.id != row.id && it.congregationId == row.congregationId && it.date == row.date }
        if (duplicate) return "A schedule already exists for this date. Edit that one instead, or pick a different date."
        val now = nowMillis()
        val isNew = row.id.isBlank()
        val saved = publicTalkRepository.save(
            row.copy(
                createdByPersonId = row.createdByPersonId.ifBlank { actorPersonId },
                createdAt = row.createdAt.takeIf { it > 0L } ?: now,
                lastEditedByPersonId = actorPersonId,
                lastEditedAt = now,
            ),
        )
        auditLogRepository.log(
            actorPersonId = actorPersonId,
            action = if (isNew) "CREATE_PUBLIC_TALK_SCHEDULE" else "EDIT_PUBLIC_TALK_SCHEDULE",
            targetType = "PublicTalkScheduleRow",
            targetId = saved.id,
            congregationId = saved.congregationId,
            details = saved.theme,
        )
        return null
    }

    fun deletePublicTalkRow(row: PublicTalkScheduleRow, actorPersonId: String) {
        viewModelScope.launch {
            recycleBinRepository.moveToTrash(
                recordType = "Public Talk Schedule",
                module = "Meeting Assignments",
                label = "${row.theme.orEmpty()} ${SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(row.date))}".trim(),
                congregationId = row.congregationId,
                deletedByPersonId = actorPersonId,
                items = listOf(recycleBinRepository.item("publicTalkSchedules", row.id, row)),
            )
            publicTalkRepository.delete(row.id)
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "DELETE_PUBLIC_TALK_SCHEDULE",
                targetType = "PublicTalkScheduleRow",
                targetId = row.id,
                congregationId = row.congregationId,
                details = row.theme,
            )
        }
    }

    /** "Cart Assignment" half of the module. Unlike [rowsFor]'s Public Talk
     * rows, sorted by date only — multiple rows may legitimately share the
     * same date (different locations), so a stable secondary sort by
     * [CartAssignmentRow.location] keeps same-day rows from reordering
     * against each other on every unrelated recomposition. */
    fun cartAssignmentsFor(congregationId: String?): Flow<List<CartAssignmentRow>> =
        cartAssignmentRepository.observeAll().map { list ->
            list.filter { congregationId == null || it.congregationId == congregationId }
                .sortedWith(compareBy({ it.date }, { it.location }))
        }

    /** "Add, edit and delete permanently the record" — no duplicate-date
     * check here (unlike [savePublicTalkRow]): "there can be multiple cart
     * assignment[s]" for the same date, by design. */
    fun saveCartAssignment(row: CartAssignmentRow, actorPersonId: String) {
        viewModelScope.launch {
            val now = nowMillis()
            val isNew = row.id.isBlank()
            val saved = cartAssignmentRepository.save(
                row.copy(
                    createdByPersonId = row.createdByPersonId.ifBlank { actorPersonId },
                    createdAt = row.createdAt.takeIf { it > 0L } ?: now,
                    lastEditedByPersonId = actorPersonId,
                    lastEditedAt = now,
                ),
            )
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = if (isNew) "CREATE_CART_ASSIGNMENT" else "EDIT_CART_ASSIGNMENT",
                targetType = "CartAssignmentRow",
                targetId = saved.id,
                congregationId = saved.congregationId,
                details = saved.location,
            )
        }
    }

    fun deleteCartAssignment(row: CartAssignmentRow, actorPersonId: String) {
        viewModelScope.launch {
            recycleBinRepository.moveToTrash(
                recordType = "Cart Assignment",
                module = "Meeting Assignments",
                label = "${row.location} ${SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(row.date))}".trim(),
                congregationId = row.congregationId,
                deletedByPersonId = actorPersonId,
                items = listOf(recycleBinRepository.item("cartAssignments", row.id, row)),
            )
            cartAssignmentRepository.delete(row.id)
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "PERMANENT_DELETE_CART_ASSIGNMENT",
                targetType = "CartAssignmentRow",
                targetId = row.id,
                congregationId = row.congregationId,
                details = row.location,
            )
        }
    }

    /** "Add a Button under Meeting [and Cart] Assignment[:] 'My
     * Assignments'... the publisher can see all the assignments under his
     * name" — every Midweek/Public Talk/Cart Assignment record in
     * [congregationId] whose own assignee field(s) name [personName] (see
     * [mentionsPerson]), across every week/date on file, not just whichever
     * one the enrollment screen currently happens to have selected. Newest
     * first, same convention every other activity list in this app uses. */
    fun myAssignmentsFor(congregationId: String?, personName: String): Flow<List<MyAssignmentRow>> = combine(
        midweekRepository.observeAll(),
        publicTalkRepository.observeAll(),
        cartAssignmentRepository.observeAll(),
    ) { midweekSchedules, publicTalkRows, cartRows ->
        if (congregationId == null || personName.isBlank()) return@combine emptyList()

        val midweekMatches = midweekSchedules
            .filter { it.congregationId == congregationId }
            .flatMap { schedule ->
                MidweekSection.entries.flatMap { section ->
                    schedule.itemsFor(section)
                        .filter { mentionsPerson(it.assignedTo, personName) }
                        .map { item ->
                            MyAssignmentRow.Midweek(
                                weekStart = schedule.weekStartDate,
                                sectionLabel = section.displayLabel,
                                particular = item.particular,
                                durationMinutes = item.durationMinutes,
                                assignedTo = item.assignedTo,
                            )
                        }
                }
            }

        val publicTalkMatches = publicTalkRows
            .filter { it.congregationId == congregationId }
            .mapNotNull { row ->
                val roles = buildList {
                    if (mentionsPerson(row.speaker, personName)) add("Speaker")
                    if (mentionsPerson(row.chairman, personName)) add("Chairman")
                    if (mentionsPerson(row.watchtowerConductor, personName)) add("Watchtower Conductor")
                    if (mentionsPerson(row.watchtowerReader, personName)) add("Watchtower Reader")
                    if (mentionsPerson(row.micServers, personName)) add("Mic Servers")
                }
                if (roles.isEmpty()) null else MyAssignmentRow.PublicTalk(row, roles)
            }

        val cartMatches = cartRows
            .filter { it.congregationId == congregationId && mentionsPerson(it.publishers, personName) }
            .map { MyAssignmentRow.Cart(it) }

        (midweekMatches + publicTalkMatches + cartMatches).sortedByDescending { it.sortKey }
    }
}
