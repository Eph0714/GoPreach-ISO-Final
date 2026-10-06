package com.emfitsolutions.gopreach.ui.screens.controlpanel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.AppSettings
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.repository.AppSettingsRepository
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.ThemePreference
import com.emfitsolutions.gopreach.data.repository.ThemePreferenceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ControlPanelUiState(
    val isUploading: Boolean = false,
    val errorMessage: String? = null,
    val isCleaningUpOrphans: Boolean = false,
    /** Result of the last "Clean Up Orphaned Role Records" run — null means
     * never run this session; the screen clears it once shown. */
    val orphanCleanupResult: Int? = null,
)

/** Spec §1/§5.1 — Control Panel module; logo upload/replace is Super-Admin only.
 * The theme picker here is the same per-device preference as the Settings
 * screen's copy (not Super-Admin-only — every signed-in user has their own),
 * just also surfaced here since some users look for display settings under
 * "Control Panel" rather than a separate "Settings" entry. */
@HiltViewModel
class ControlPanelViewModel @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
    private val themePreferenceRepository: ThemePreferenceRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val personRepository: PersonRepository,
    private val auditLogRepository: AuditLogRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        appSettingsRepository.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val theme: StateFlow<ThemePreference> = themePreferenceRepository.preference

    fun setTheme(preference: ThemePreference) = themePreferenceRepository.setPreference(preference)

    private val _uiState = MutableStateFlow(ControlPanelUiState())
    val uiState: StateFlow<ControlPanelUiState> = _uiState

    fun uploadLogo(imageUri: Uri, updatedByPersonId: String) {
        _uiState.update { it.copy(isUploading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                appSettingsRepository.uploadLogo(imageUri, updatedByPersonId)
                _uiState.update { it.copy(isUploading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isUploading = false, errorMessage = e.localizedMessage ?: "Upload failed.") }
            }
        }
    }

    /** "Delete all unknown member in Total Elders, do not include them in
     * the count" — permanently removes every ACTIVE RoleAssignment whose
     * Person doc no longer exists (an in-flight sync gets a fresh chance to
     * arrive first, since this only runs on demand, not automatically on
     * every dashboard load — auto-deleting on a transient sync gap would
     * risk destroying a real, brand-new elder/publisher whose Person doc
     * simply hasn't synced down yet). This is the one-time data cleanup;
     * [com.emfitsolutions.gopreach.ui.screens.dashboard.computeStatMembers]
     * and [com.emfitsolutions.gopreach.ui.screens.dashboard.CongregationStats]
     * are the standing fix that keeps new orphans like these from ever being
     * counted again, even before this cleanup gets around to deleting them. */
    fun cleanUpOrphanedRoleAssignments(actorPersonId: String) {
        _uiState.update { it.copy(isCleaningUpOrphans = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val people = personRepository.observeAll().first()
                val peopleIds = people.map { it.id }.toSet()
                val orphans = roleAssignmentRepository.observeAll().first()
                    .filter { it.status == RoleAssignmentStatus.ACTIVE && it.personId !in peopleIds }
                orphans.forEach { roleAssignmentRepository.delete(it.id) }
                if (orphans.isNotEmpty()) {
                    auditLogRepository.log(
                        actorPersonId = actorPersonId,
                        action = "CLEAN_UP_ORPHANED_ROLE_ASSIGNMENTS",
                        targetType = "RoleAssignment",
                        details = "removed: ${orphans.size}",
                    )
                }
                _uiState.update { it.copy(isCleaningUpOrphans = false, orphanCleanupResult = orphans.size) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isCleaningUpOrphans = false, errorMessage = e.localizedMessage ?: "Clean-up failed.") }
            }
        }
    }

    fun orphanCleanupResultShown() = _uiState.update { it.copy(orphanCleanupResult = null) }
}
