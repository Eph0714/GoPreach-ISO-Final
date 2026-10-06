package com.emfitsolutions.gopreach.ui.screens.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Schedule
import com.emfitsolutions.gopreach.data.model.ScheduleKind
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.ScheduleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Spec §6.2 — Calendar. [CalendarScope] captures the view/edit rules from the
 * spec's role table so the screen doesn't need to re-derive them.
 */
sealed class CalendarScope {
    /** Super-Admin (all congregations) or Admin/Coordinator Elder (their own,
     * via [congregationId]) — sees every CALENDAR_EVENT in scope, may add/edit/
     * delete all of them ([canEditAll] true) or just their own ([canEditAll]
     * false narrows to Regular Elder's own-group events, via [editableGroupId]). */
    data class AdminTrack(
        val congregationId: String?, // null = Super-Admin, all congregations
        val canEditAll: Boolean,
        val editableGroupId: String? = null,
    ) : CalendarScope()

    /** Publisher: sees Admin-track events in [congregationId] plus [groupId]'s
     * Regular Elder events, plus their own private notes; may only add/edit/
     * delete their own notes. */
    data class Publisher(val congregationId: String?, val groupId: String?) : CalendarScope()
}

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val scheduleRepository: ScheduleRepository,
    private val recycleBinRepository: com.emfitsolutions.gopreach.data.repository.RecycleBinRepository,
    congregationRepository: CongregationRepository,
) : ViewModel() {

    /** "Add a filter for Congregation" (Super-Admin only) — the dropdown's
     * own option list. */
    val congregations: StateFlow<List<Congregation>> = congregationRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun eventsFor(scope: CalendarScope, viewerPersonId: String): Flow<List<Schedule>> =
        scheduleRepository.observeAll().map { all ->
            when (scope) {
                is CalendarScope.AdminTrack -> all.filter {
                    it.kind == ScheduleKind.CALENDAR_EVENT && (scope.congregationId == null || it.congregationId == scope.congregationId)
                }
                is CalendarScope.Publisher -> all.filter { event ->
                    (event.kind == ScheduleKind.CALENDAR_EVENT &&
                        event.congregationId == scope.congregationId &&
                        (event.groupId == null || event.groupId == scope.groupId)) ||
                        (event.kind == ScheduleKind.PERSONAL_NOTE && event.ownerPersonId == viewerPersonId)
                }
            }.sortedBy { it.startTime }
        }

    fun canEdit(scope: CalendarScope, event: Schedule, viewerPersonId: String): Boolean = when (scope) {
        is CalendarScope.AdminTrack -> scope.canEditAll || event.groupId == scope.editableGroupId
        is CalendarScope.Publisher -> event.kind == ScheduleKind.PERSONAL_NOTE && event.ownerPersonId == viewerPersonId
    }

    fun save(schedule: Schedule) {
        viewModelScope.launch { scheduleRepository.save(schedule) }
    }

    /** Moves the entry to Deleted Records (kept whole, restorable) and removes it from the calendar. */
    fun delete(schedule: Schedule, actorPersonId: String) {
        viewModelScope.launch {
            recycleBinRepository.moveToTrash(
                recordType = if (schedule.kind == ScheduleKind.PERSONAL_NOTE) "Personal Note" else "Calendar Entry",
                module = "Calendar",
                label = schedule.title,
                congregationId = schedule.congregationId,
                groupId = schedule.groupId,
                originalCreatedAt = schedule.createdAt,
                deletedByPersonId = actorPersonId,
                items = listOf(recycleBinRepository.item("schedules", schedule.id, schedule)),
            )
            scheduleRepository.delete(schedule.id)
        }
    }
}
