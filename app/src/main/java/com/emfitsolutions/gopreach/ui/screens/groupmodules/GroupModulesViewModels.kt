package com.emfitsolutions.gopreach.ui.screens.groupmodules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.AssignmentStatus
import com.emfitsolutions.gopreach.data.model.FsGroupReportSnapshot
import com.emfitsolutions.gopreach.data.model.PublisherAssignment
import com.emfitsolutions.gopreach.data.repository.FsGroupReportSnapshotRepository
import com.emfitsolutions.gopreach.data.repository.GroupRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.PublisherAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.SnapshotSaveResult
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.FieldServiceReportViewModel
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.toSnapshotReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What the Publishers Assignment screen shows: the officer's own FS Group, its publishers and its assignments. Null group = the person is not an officer of any group. */
data class AssignmentsState(
    val slot: OfficerSlot? = null,
    /** personId -> "Last, First" for every publisher of the group now. */
    val members: Map<String, String> = emptyMap(),
    val assignments: List<PublisherAssignment> = emptyList(),
    val loaded: Boolean = false,
)

/**
 * Publishers Assignment. The group is derived here from the signed-in person's own slot; nothing the screen passes in can name another group, and the server refuses a save for
 * another group's publisher whatever this class does.
 */
class PublishersAssignmentViewModel(
    private val groups: GroupRepository,
    private val roles: RoleAssignmentRepository,
    private val people: PersonRepository,
    private val assignments: PublisherAssignmentRepository,
) : ViewModel() {
    fun startSync(): Flow<Unit> = assignments.startRemoteSync()

    fun state(personId: String, congregationId: String?): Flow<AssignmentsState> =
        combine(groups.observeAll(), roles.observeAll(), people.observeAll(), assignments.observeAll()) { allGroups, allRoles, allPeople, allAssignments ->
            val slot = officerSlotOf(personId, congregationId, allGroups) ?: return@combine AssignmentsState(loaded = true)
            val byId = allPeople.associateBy { it.id }
            val members = groupMemberIds(slot.group, allRoles).mapNotNull { id -> byId[id]?.let { id to (it.lastName.trim() + ", " + it.firstName.trim()).trim(' ', ',') } }
                .sortedBy { it.second.lowercase() }.toMap()
            AssignmentsState(slot, members, allAssignments.filter { it.groupId == slot.group.id }.sortedByDescending { it.updatedAt }, loaded = true)
        }

    fun mine(personId: String): Flow<List<PublisherAssignment>> = assignments.observeForPublisher(personId)

    fun create(slot: OfficerSlot, draft: PublisherAssignment, memberName: String, byPersonId: String, byName: String, nowMillis: Long) {
        viewModelScope.launch {
            assignments.create(
                draft.copy(congregationId = slot.group.congregationId, groupId = slot.group.id, groupName = slot.group.name, publisherName = memberName),
                byPersonId, byName, nowMillis,
            )
        }
    }

    fun update(old: PublisherAssignment, edited: PublisherAssignment, byPersonId: String, byName: String, nowMillis: Long) {
        viewModelScope.launch { assignments.update(old, edited, byPersonId, byName, nowMillis) }
    }

    fun setStatus(old: PublisherAssignment, status: AssignmentStatus, byPersonId: String, byName: String, nowMillis: Long) {
        viewModelScope.launch { assignments.setStatus(old, status, byPersonId, byName, nowMillis) }
    }

    fun delete(id: String) {
        viewModelScope.launch { assignments.delete(id) }
    }
}

/** What the Monthly Report Snapshots screen shows. */
data class SnapshotsState(
    val slot: OfficerSlot? = null,
    /** Every snapshot this person may see (their group's, or all of their congregation's for a congregation role). */
    val snapshots: List<FsGroupReportSnapshot> = emptyList(),
    val loaded: Boolean = false,
)

class MonthlySnapshotsViewModel(
    private val groups: GroupRepository,
    private val snapshots: FsGroupReportSnapshotRepository,
    private val congregations: com.emfitsolutions.gopreach.data.repository.CongregationRepository,
) : ViewModel() {
    fun startSync(): Flow<Unit> = snapshots.startRemoteSync()

    fun state(personId: String, congregationId: String?, readsAll: Boolean): Flow<SnapshotsState> =
        combine(groups.observeAll(), snapshots.observeAll()) { allGroups, all ->
            val slot = officerSlotOf(personId, congregationId, allGroups)
            val visible = all.filter { s ->
                (congregationId == null || s.congregationId == congregationId) && (readsAll || (slot != null && s.groupId == slot.group.id))
            }
            SnapshotsState(slot, visible, loaded = true)
        }

    /** The officer's group's report for [monthStart], as it is right now: what a snapshot of it would freeze. */
    fun liveSheet(report: FieldServiceReportViewModel, slot: OfficerSlot, monthStart: Long) =
        report.sheetsFor(listOf(slot.group), monthStart, monthStart).map { it.firstOrNull() }

    /** Freezes the live report of the officer's OWN group for [monthStart]. [onResult] runs on the main thread. */
    fun save(report: FieldServiceReportViewModel, slot: OfficerSlot, monthStart: Long, year: Int, month: Int, byPersonId: String, byName: String, nowMillis: Long, onResult: (SnapshotSaveResult) -> Unit) {
        viewModelScope.launch {
            val sheet = report.sheetsFor(listOf(slot.group), monthStart, monthStart).first().firstOrNull()
            if (sheet == null) {
                onResult(SnapshotSaveResult.Failed("There is no report to save for this group and month."))
                return@launch
            }
            val congregationName = congregations.observeAll().first().firstOrNull { it.id == slot.group.congregationId }?.name.orEmpty()
            onResult(
                snapshots.save(
                    FsGroupReportSnapshot(
                        congregationId = slot.group.congregationId, congregationName = congregationName, groupId = slot.group.id, groupName = slot.group.name, year = year, month = month,
                        periodMonth = monthStart, savedByPersonId = byPersonId, savedByName = byName, savedByRole = slot.label, savedAt = nowMillis,
                        sourceReports = sheet.rows.count { it.hasReport }, sourceVersion = nowMillis, report = sheet.toSnapshotReport(),
                    ),
                ),
            )
        }
    }
}
