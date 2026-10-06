package com.emfitsolutions.gopreach.ui.screens.backup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.repository.BackupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject

data class BackupRestoreUiState(
    val isBusy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

/** Spec §3/§5.1 — Backup & Restore, Super-Admin only (see [BackupRepository]). */
@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val backupRepository: BackupRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupRestoreUiState())
    val uiState: StateFlow<BackupRestoreUiState> = _uiState.asStateFlow()

    fun exportTo(destinationUri: Uri, exportedByPersonId: String) {
        _uiState.update { it.copy(isBusy = true, message = null, isError = false) }
        viewModelScope.launch {
            try {
                val json = backupRepository.exportAll(exportedByPersonId)
                appContext.contentResolver.openOutputStream(destinationUri)?.use { it.write(json.toByteArray()) }
                    ?: error("Could not open the chosen file for writing.")
                _uiState.update { it.copy(isBusy = false, message = "Backup exported.") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, isError = true, message = e.localizedMessage ?: "Export failed.") }
            }
        }
    }

    fun restoreFrom(sourceUri: Uri, restoredByPersonId: String) {
        _uiState.update { it.copy(isBusy = true, message = null, isError = false) }
        viewModelScope.launch {
            try {
                val json = appContext.contentResolver.openInputStream(sourceUri)?.use { stream ->
                    BufferedReader(InputStreamReader(stream)).readText()
                } ?: error("Could not open the chosen file for reading.")
                val count = backupRepository.restoreFromJson(json, restoredByPersonId)
                _uiState.update { it.copy(isBusy = false, message = "Restored $count records. They'll sync once online.") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, isError = true, message = e.localizedMessage ?: "Restore failed.") }
            }
        }
    }
}
