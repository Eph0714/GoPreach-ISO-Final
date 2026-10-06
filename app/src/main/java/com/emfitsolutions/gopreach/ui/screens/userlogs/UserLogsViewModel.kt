package com.emfitsolutions.gopreach.ui.screens.userlogs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.AuditLogEntry
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LogRow(val entry: AuditLogEntry, val actorName: String)

/**
 * Spec §3 — "user logs". Scoping (Super-Admin: all; Admin/Coordinator Elder: own
 * congregation only; Regular Elder: no access) is enforced by the screen only
 * showing this ViewModel to roles that pass [com.emfitsolutions.gopreach.domain.PermissionChecker];
 * [visibleCongregationId] further narrows the data itself for the congregation-
 * scoped roles (null means "show everything", i.e. Super-Admin).
 */
@HiltViewModel
class UserLogsViewModel @Inject constructor(
    private val auditLogRepository: AuditLogRepository,
    private val personRepository: PersonRepository,
    congregationRepository: CongregationRepository,
) : ViewModel() {

    /** "Add a filter for Congregation" (Super-Admin only) — the dropdown's
     * own option list. */
    val congregations: StateFlow<List<Congregation>> = congregationRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun rowsFor(visibleCongregationId: String?): Flow<List<LogRow>> =
        combine(auditLogRepository.observeAll(), personRepository.observeAll()) { entries, people ->
            entries
                .filter { visibleCongregationId == null || it.congregationId == visibleCongregationId }
                .sortedByDescending { it.timestamp }
                .map { entry ->
                    val actorName = people.firstOrNull { it.id == entry.actorPersonId }?.fullName ?: entry.actorPersonId
                    LogRow(entry, actorName)
                }
        }

    fun delete(entryId: String) {
        viewModelScope.launch { auditLogRepository.delete(entryId) }
    }

    /** "Select all user log AND DELETE IT PERMANENTLY" — bulk counterpart to
     * [delete]; same Super-Admin-only visibility gate, enforced by the
     * screen. [entryIds] is exactly the set the screen currently has
     * selected/checked, not re-derived here, so this only ever deletes what
     * the admin actually confirmed. */
    fun deleteAll(entryIds: Collection<String>) {
        viewModelScope.launch { auditLogRepository.deleteAll(entryIds) }
    }
}
