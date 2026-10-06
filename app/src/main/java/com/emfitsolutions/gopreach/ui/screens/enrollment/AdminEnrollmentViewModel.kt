package com.emfitsolutions.gopreach.ui.screens.enrollment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.repository.AuthRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.TempCredentials
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AdminEnrollmentUiState(
    val name: String = "",
    val address: String = "",
    val email: String = "",
    val contact: String = "",
    val selectedCongregationId: String? = null,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val result: TempCredentials? = null,
)

/** Spec §4.2 — Admin Per Congregation enrollment, Super-Admin only. */
@HiltViewModel
class AdminEnrollmentViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    congregationRepository: CongregationRepository,
) : ViewModel() {

    val congregations: StateFlow<List<Congregation>> =
        congregationRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(AdminEnrollmentUiState())
    val uiState: StateFlow<AdminEnrollmentUiState> = _uiState.asStateFlow()

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value.uppercase(), errorMessage = null) }
    fun onAddressChange(value: String) = _uiState.update { it.copy(address = value.uppercase(), errorMessage = null) }
    fun onEmailChange(value: String) = _uiState.update { it.copy(email = value, errorMessage = null) }
    fun onContactChange(value: String) = _uiState.update { it.copy(contact = value.uppercase(), errorMessage = null) }
    fun onCongregationSelected(id: String) = _uiState.update { it.copy(selectedCongregationId = id, errorMessage = null) }

    fun save(enrollingPersonId: String) {
        val state = _uiState.value
        if (state.name.isBlank() || state.address.isBlank() || state.contact.isBlank() || state.selectedCongregationId == null) {
            _uiState.update { it.copy(errorMessage = "Name, address, contact, and congregation are required.") }
            return
        }
        _uiState.update { it.copy(isSaving = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val (firstName, lastName) = splitName(state.name)
                val credentials = authRepository.createAccountWithTempCredentials(
                    person = Person(
                        firstName = firstName,
                        lastName = lastName,
                        address = state.address.trim(),
                        email = state.email.trim().ifBlank { null },
                        contact = state.contact.trim(),
                    ),
                    roleAssignment = { personId ->
                        RoleAssignment(
                            personId = personId,
                            roleType = RoleType.serialize(RoleType.Admin(AdminRole.ADMIN_PER_CONGREGATION)),
                            congregationId = state.selectedCongregationId,
                            status = RoleAssignmentStatus.ACTIVE,
                            dateAssigned = System.currentTimeMillis(),
                            assignedByPersonId = enrollingPersonId,
                        )
                    },
                    enrollingPersonId = enrollingPersonId,
                )
                _uiState.update { it.copy(isSaving = false, result = credentials) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSaving = false, errorMessage = e.localizedMessage ?: "Couldn't enroll this admin. Please try again.")
                }
            }
        }
    }
}

/** Enrollment forms take one "Name" field per spec wording; Person stores it
 * split. A single space is the split point — good enough for the common case,
 * with everything after the first space folded into the last name. */
internal fun splitName(fullName: String): Pair<String, String> {
    val trimmed = fullName.trim()
    val spaceIndex = trimmed.indexOf(' ')
    return if (spaceIndex == -1) trimmed to "" else trimmed.substring(0, spaceIndex) to trimmed.substring(spaceIndex + 1).trim()
}
