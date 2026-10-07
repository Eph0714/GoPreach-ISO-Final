package com.emfitsolutions.gopreach.ui.screens.publishers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.GroupRepository
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.data.repository.MonthlyReportRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.VisitRepository
import com.emfitsolutions.gopreach.domain.duplicateNameKey
import com.emfitsolutions.gopreach.domain.duplicateUsernameKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class PublisherRow(
    val person: Person,
    val assignment: RoleAssignment,
    val category: PublisherCategory,
    val groupName: String,
    /** "Check if there are duplicate names, evaluate it if they are the
     * same person" — the other publisher's full name, when this row's name
     * or auto-generated username base (see [duplicateUsernameKey]) matches
     * another active publisher in the same congregation; null otherwise.
     * Flagging only — this app has no record-merge tool, so an admin still
     * decides and acts (edit/permanently delete) themselves. */
    val possibleDuplicateOf: String? = null,
)

/**
 * Spec §3/§5.1 — "CRUD Publishers (all categories)": Super-Admin sees every
 * congregation, Admin/Coordinator Elder see only their own (via
 * [visibleCongregationId]), Regular Elder has no access to this screen at all.
 *
 * "Delete" here means recategorizing to [PublisherCategory.REMOVED_PUBLISHER]
 * rather than erasing the record — spec §2.2 already models Removed/Inactive as
 * publisher categories, so changing category *is* the CRUD-delete/reactivate
 * operation; the Person and their historical reports stay intact.
 */
class ManagePublishersViewModel(
    private val personRepository: PersonRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val auditLogRepository: AuditLogRepository,
    private val monthlyReportRepository: MonthlyReportRepository,
    private val interestedPersonRepository: InterestedPersonRepository,
    private val visitRepository: VisitRepository,
    private val recycleBinRepository: com.emfitsolutions.gopreach.data.repository.RecycleBinRepository,
    private val publisherCongregationContext: com.emfitsolutions.gopreach.data.repository.PublisherCongregationContext,
    private val locationTracker: com.emfitsolutions.gopreach.data.location.LocationTracker,
    private val philippineLocationRepository: com.emfitsolutions.gopreach.data.repository.PhilippineLocationRepository,
    groupRepository: GroupRepository,
    congregationRepository: CongregationRepository,
) : ViewModel() {

    private val groups: Flow<List<Group>> = groupRepository.observeAll()

    /** The congregation a Super-Admin is working in inside this module (shared with Add Publisher). */
    val selectedCongregationId = publisherCongregationContext.selectedCongregationId

    fun selectCongregation(congregationId: String?) = publisherCongregationContext.select(congregationId)

    /** "In enrolling publisher record for superadmin, show a dropdown to
     * select a congregation as filter" — every active congregation, for the
     * Super-Admin-only filter dropdown in [ManagePublishersScreen] (Admin/
     * Coordinator Elder are already scoped to their own single congregation
     * upstream via [rowsFor]'s own `visibleCongregationId`, so this is never
     * shown to them at all). */
    val congregations: Flow<List<Congregation>> =
        congregationRepository.observeAll().map { list -> list.filter { it.status == RecordStatus.ACTIVE }.sortedBy { it.name } }

    /** For the Edit Publisher dialog's Group dropdown — every Group in this
     * publisher's own congregation (a Publisher can only ever belong to a
     * Group within their own congregation). */
    fun groupsFor(congregationId: String?): Flow<List<Group>> =
        groups.map { list -> list.filter { it.congregationId == congregationId }.sortedWith(com.emfitsolutions.gopreach.domain.GroupNameOrder) }

    fun rowsFor(visibleCongregationId: String?): Flow<List<PublisherRow>> =
        combine(personRepository.observeAll(), roleAssignmentRepository.observeAll(), groups) { people, assignments, groups ->
            // resolvedRoleTypeOrNull, never the throwing resolvedRoleType — see
            // DashboardStats.computeStatMembers' doc comment; a row that fails
            // to parse is simply excluded (mapNotNull below) rather than
            // crashing this whole screen.
            val rows = assignments
                .filter { it.resolvedRoleTypeOrNull() is RoleType.Publisher }
                .filter { visibleCongregationId == null || it.congregationId == visibleCongregationId }
                .mapNotNull { assignment ->
                    val person = people.firstOrNull { it.id == assignment.personId } ?: return@mapNotNull null
                    val category = (assignment.resolvedRoleTypeOrNull() as? RoleType.Publisher)?.category ?: return@mapNotNull null
                    val groupName = groups.firstOrNull { it.id == assignment.groupId }?.name ?: "Unassigned"
                    PublisherRow(person, assignment, category, groupName)
                }
            // "Check if there are duplicate names... use their username and
            // password as reference" — matched within the same congregation
            // only, on either the normalized full name or the auto-generated
            // username's base (see duplicateUsernameKey's doc comment for
            // why that's the practical stand-in for "password").
            rows
                .map { row ->
                    val match = rows.firstOrNull { other ->
                        other.person.id != row.person.id &&
                            other.assignment.congregationId == row.assignment.congregationId &&
                            (other.person.duplicateNameKey() == row.person.duplicateNameKey() ||
                                other.person.duplicateUsernameKey() == row.person.duplicateUsernameKey())
                    }
                    if (match != null) row.copy(possibleDuplicateOf = match.person.fullName) else row
                }
                .sortedBy { it.person.fullName }
        }

    /** "Publishers — Manual Status Category Management" — the ONLY place a
     * Publisher's Status Category is ever written. Always a direct result of
     * an authorized user's own dropdown selection (never derived from
     * reports/attendance/inactivity/etc. — see the removed PublisherAutoStatus
     * for what used to run automatically here and why it was deleted).
     * [previousCategory] is recorded purely for the audit trail (spec §7) —
     * it never affects what gets saved. */
    fun changeCategory(row: PublisherRow, newCategory: PublisherCategory, changedByPersonId: String) {
        val previousCategory = row.category
        viewModelScope.launch {
            val updated = row.assignment.copy(
                roleType = RoleType.serialize(RoleType.Publisher(newCategory)),
                lastEditedByPersonId = changedByPersonId,
                lastEditedAt = System.currentTimeMillis(),
            )
            roleAssignmentRepository.save(updated)
            auditLogRepository.log(
                actorPersonId = changedByPersonId,
                action = "CHANGE_PUBLISHER_CATEGORY",
                targetType = "Person",
                targetId = row.person.id,
                congregationId = row.assignment.congregationId,
                details = "Previous: $previousCategory, New: $newCategory",
            )
        }
    }

    fun hasLocationPermission(): Boolean = locationTracker.hasLocationPermission()

    /** The device's current position, with the province/municipality/barangay it falls in when those can be
     * worked out (best-effort). Null when no GPS fix could be had. */
    suspend fun captureLocation(): CapturedLocation? {
        val location = locationTracker.getCurrentLocation() ?: return null
        val geocoded = runCatching { locationTracker.reverseGeocodeAddress(location.lat, location.lng) }.getOrNull()
        val resolved = geocoded?.let { philippineLocationRepository.resolveFromGeocode(it) }
        return CapturedLocation(location.lat, location.lng, resolved?.provinceName, resolved?.muncityName, resolved?.barangayName)
    }

    /** Moves this publisher to another congregation (Super-Admin only, after a confirmation in the dialog). The group
     * belongs to the old congregation, so it is replaced by [newGroupId] (a group of the new congregation) or cleared.
     * Records they already made — reports, interested people — keep the congregation they were made in. */
    fun changeCongregation(row: PublisherRow, newCongregationId: String, newGroupId: String?, changedByPersonId: String) {
        viewModelScope.launch {
            roleAssignmentRepository.save(
                row.assignment.copy(
                    congregationId = newCongregationId,
                    groupId = newGroupId,
                    lastEditedByPersonId = changedByPersonId,
                    lastEditedAt = System.currentTimeMillis(),
                ),
            )
            auditLogRepository.log(
                actorPersonId = changedByPersonId,
                action = "CHANGE_PUBLISHER_CONGREGATION",
                targetType = "Person",
                targetId = row.person.id,
                congregationId = newCongregationId,
                details = "from ${row.assignment.congregationId} to $newCongregationId",
            )
        }
    }

    fun updatePerson(person: Person) {
        viewModelScope.launch { personRepository.save(person) }
    }

    /** "Enable the user to edit all entities like the Groups" — moves this
     * Publisher's RoleAssignment to a different Group within their own
     * congregation (or clears it back to Unassigned with `null`), the same
     * kind of write [changeCategory] already makes to the same document. */
    fun changeGroup(row: PublisherRow, newGroupId: String?, changedByPersonId: String) {
        viewModelScope.launch {
            val updated = row.assignment.copy(
                groupId = newGroupId,
                lastEditedByPersonId = changedByPersonId,
                lastEditedAt = System.currentTimeMillis(),
            )
            roleAssignmentRepository.save(updated)
            auditLogRepository.log(
                actorPersonId = changedByPersonId,
                action = "CHANGE_PUBLISHER_GROUP",
                targetType = "Person",
                targetId = row.person.id,
                congregationId = row.assignment.congregationId,
            )
        }
    }

    /** What a Publisher deletion touches, counted per record type for the warning dialog. */
    data class DeleteImpact(val searching: Int, val returnVisit: Int, val bibleStudy: Int, val monthlyReports: Int) {
        val relatedRecords: Int get() = searching + returnVisit + bibleStudy
        val isEmpty: Boolean get() = relatedRecords == 0 && monthlyReports == 0

        fun message(): String = buildString {
            if (relatedRecords > 0) {
                append("This Publisher currently has:\n\n")
                append("Searching Records: $searching\n")
                append("Return Visit Records: $returnVisit\n")
                append("Bible Study Records: $bibleStudy\n\n")
                append("Deleting this Publisher will NOT delete these records.\n\n")
                append("The related records will remain in the system and will become Unassigned, allowing an Admin to assign them to another Publisher later.")
            }
            if (monthlyReports > 0) {
                if (isNotEmpty()) append("\n\n")
                append("The Publisher's own $monthlyReports monthly report(s) will be moved to Deleted Records.")
            }
            append("\n\nAre you sure you want to delete this Publisher?")
        }
    }

    /** Counts the records the confirmation dialog must warn about; null when there is nothing related at all. */
    suspend fun permanentDeleteImpact(publisherPersonId: String): DeleteImpact? {
        val reportCount = monthlyReportRepository.observeAll().first().count { it.publisherPersonId == publisherPersonId }
        // Searching / Return Visit / Bible Study are all InterestedPerson records (see PipelineStage).
        val owned = interestedPersonRepository.observeAll().first().filter { it.publisherPersonId == publisherPersonId }
        val impact = DeleteImpact(
            searching = owned.count { it.pipelineStage == PipelineStage.SEARCHING },
            returnVisit = owned.count { it.pipelineStage == PipelineStage.RETURN_VISIT },
            bibleStudy = owned.count { it.pipelineStage == PipelineStage.BIBLE_STUDY },
            monthlyReports = reportCount,
        )
        return impact.takeUnless { it.isEmpty }
    }

    /** Super-Admin-only (see [canPermanentlyDelete]) delete of the Publisher: their own Monthly Reports go to Deleted
     * Records with them, then the RoleAssignment, and the Person doc too only if they have no other RoleAssignment
     * left. Their Searching / Return Visit / Bible Study records are NEVER deleted — they stay, with every visit and
     * all history, and simply become Unassigned so an Admin can reassign them. Nothing here is reachable through
     * "Move to Inactive," which stays the non-destructive default. */
    fun permanentlyDelete(row: PublisherRow, actorPersonId: String) {
        viewModelScope.launch {
            val reports = monthlyReportRepository.observeAll().first().filter { it.publisherPersonId == row.person.id }
            val owned = interestedPersonRepository.observeAll().first().filter { it.publisherPersonId == row.person.id }
            val remaining = roleAssignmentRepository.observeAll().first().count { it.personId == row.person.id && it.id != row.assignment.id }

            // The publisher and their own reports are kept whole in Deleted Records (same ids, so a restore puts them
            // back), then removed from the active lists. Interested-person records are not part of this.
            val items = buildList {
                add(recycleBinRepository.item("roleAssignments", row.assignment.id, row.assignment))
                if (remaining == 0) add(recycleBinRepository.item("people", row.person.id, row.person))
                reports.forEach { add(recycleBinRepository.item("monthlyReports", it.id, it)) }
            }
            val groupName = row.assignment.groupId?.let { gid -> groups.first().firstOrNull { it.id == gid }?.name }
            recycleBinRepository.moveToTrash(
                recordType = "Publisher",
                module = "Publishers",
                label = row.person.fullName,
                congregationId = row.assignment.congregationId,
                groupId = row.assignment.groupId,
                groupName = groupName,
                originalCreatedAt = row.person.createdAt,
                originalModifiedAt = row.assignment.lastEditedAt,
                deletedByPersonId = actorPersonId,
                items = items,
            )

            // Never cascade: release the assignment, keep the record.
            owned.forEach { interestedPersonRepository.save(it.copy(publisherPersonId = "")) }
            if (owned.isNotEmpty()) {
                auditLogRepository.log(
                    actorPersonId = actorPersonId,
                    action = "UNASSIGN_RECORDS_ON_PUBLISHER_DELETE",
                    targetType = "Person",
                    targetId = row.person.id,
                    congregationId = row.assignment.congregationId,
                    details = "${owned.size} record(s) of ${row.person.fullName} are now unassigned",
                )
            }
            reports.forEach { monthlyReportRepository.deleteIgnoringLock(it.id) }
            roleAssignmentRepository.delete(row.assignment.id)
            if (remaining == 0) personRepository.delete(row.person.id)
        }
    }
}

/** A GPS fix plus whichever address levels it could be matched to. */
data class CapturedLocation(val lat: Double, val lng: Double, val province: String?, val city: String?, val barangay: String?)
