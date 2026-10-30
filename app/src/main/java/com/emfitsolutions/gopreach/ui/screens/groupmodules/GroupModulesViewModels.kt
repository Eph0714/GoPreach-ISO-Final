package com.emfitsolutions.gopreach.ui.screens.groupmodules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.FsGroupReportSnapshot
import com.emfitsolutions.gopreach.data.repository.FsGroupReportSnapshotRepository
import com.emfitsolutions.gopreach.data.repository.GroupRepository
import com.emfitsolutions.gopreach.data.repository.SnapshotSaveResult
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.FieldServiceReportViewModel
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.toSnapshotReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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

    /** Replaces the saved snapshot [existing] of the officer's OWN group with the report as it is now; the copy it replaces stays in its history. [onResult] runs on the main thread. */
    fun replace(report: FieldServiceReportViewModel, slot: OfficerSlot, existing: FsGroupReportSnapshot, reason: String, byPersonId: String, byName: String, nowMillis: Long, onResult: (SnapshotSaveResult) -> Unit) {
        viewModelScope.launch {
            val sheet = report.sheetsFor(listOf(slot.group), existing.periodMonth, existing.periodMonth).first().firstOrNull()
            if (sheet == null) {
                onResult(SnapshotSaveResult.Failed("There is no report to save for this group and month."))
                return@launch
            }
            onResult(snapshots.replace(existing, existing.replacedBy(sheet.toSnapshotReport(), reason, byPersonId, byName, slot.label, nowMillis, sheet.rows.count { it.hasReport }, nowMillis)))
        }
    }

    /** Deletes the saved record [existing] (the officer's OWN group, one month). [onResult] gets null when it is gone, else the reason, on the main thread. */
    fun delete(existing: FsGroupReportSnapshot, onResult: (String?) -> Unit) {
        viewModelScope.launch { onResult(snapshots.delete(existing)) }
    }

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
