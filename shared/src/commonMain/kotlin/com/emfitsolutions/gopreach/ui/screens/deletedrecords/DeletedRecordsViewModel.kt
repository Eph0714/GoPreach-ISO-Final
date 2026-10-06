package com.emfitsolutions.gopreach.ui.screens.deletedrecords

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.AppSettings
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.DeletedRecord
import com.emfitsolutions.gopreach.data.repository.AppSettingsRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.RecycleBinRepository
import com.emfitsolutions.gopreach.data.repository.RestoreResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Who is looking at Deleted Records, resolved once by the nav graph from the session's own role (the app's role
 * system stays the source of truth; firestore.rules enforces the same boundary server-side):
 *  - Super-Admin: every congregation;
 *  - Admin / Coordinator Elder / Service Overseer / Secretary: their own congregation's records, plus their own;
 *  - everyone else: only what they deleted themselves.
 */
data class DeletedRecordsAccess(
    val personId: String,
    val isSuperAdmin: Boolean,
    val manageableCongregationId: String?,
) {
    fun canManage(record: DeletedRecord): Boolean =
        isSuperAdmin ||
            record.deletedByPersonId == personId ||
            (manageableCongregationId != null && record.congregationId == manageableCongregationId)
}

class DeletedRecordsViewModel(
    private val recycleBinRepository: RecycleBinRepository,
    private val appSettingsRepository: AppSettingsRepository,
    congregationRepository: CongregationRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = appSettingsRepository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val congregations: StateFlow<List<Congregation>> = congregationRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Everything this user may see and act on, newest deleted first. */
    fun recordsFor(access: DeletedRecordsAccess): Flow<List<DeletedRecord>> =
        recycleBinRepository.observeAll().map { all ->
            all.filter { it.status == DeletedRecord.STATUS_DELETED && access.canManage(it) }.sortedByDescending { it.deletedAt }
        }

    /** Restores one record; the result says whether it worked or why not (a clash is never overwritten). */
    suspend fun restore(record: DeletedRecord, access: DeletedRecordsAccess): RestoreResult {
        if (!access.canManage(record)) return RestoreResult.Failed("You don't have permission to restore this record.")
        return recycleBinRepository.restore(record, access.personId)
    }

    fun permanentlyDelete(record: DeletedRecord, access: DeletedRecordsAccess) {
        if (!access.canManage(record)) return
        viewModelScope.launch { recycleBinRepository.permanentlyDelete(record, access.personId, automatic = false) }
    }

    /** Removes what has outlived the retention period — only if the setting is on, and only what this user may manage. */
    fun purgeExpired(access: DeletedRecordsAccess) {
        viewModelScope.launch {
            val current = appSettingsRepository.observe().first()
            if (!current.trashAutoDeleteEnabled) return@launch
            val mine = recycleBinRepository.observeAll().first().filter { access.canManage(it) }
            recycleBinRepository.purgeExpired(current, mine, access.personId)
        }
    }

    fun saveRetention(enabled: Boolean, days: Int, byPersonId: String) {
        viewModelScope.launch { appSettingsRepository.saveTrashRetention(enabled, days, byPersonId) }
    }
}
