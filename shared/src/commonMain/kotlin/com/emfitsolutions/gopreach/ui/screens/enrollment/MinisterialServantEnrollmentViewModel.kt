package com.emfitsolutions.gopreach.ui.screens.enrollment

import com.emfitsolutions.gopreach.platform.nowMillis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RegularElderRole
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.repository.AuthRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.TempCredentials
import com.emfitsolutions.gopreach.domain.PermissionChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MinisterialServantEnrollmentUiState(
    val firstName: String = "",
    val lastName: String = "",
    val address: String = "",
    val email: String = "",
    val contact: String = "",
    /** Only used when the enroller is a Super-Admin — Admin/Coordinator
     * Elder are otherwise auto-assigned to their own congregation. */
    val selectedCongregationId: String? = null,
    /** "Select Role" checkboxes — Group Servant/Group Assistant are mutually
     * exclusive with each other (spec: "If Check, 'Group Assistant' is
     * disabled and unchecked" and vice versa), same single-nullable-field
     * pattern as [publisherCategory] below and as Coordinator Elder/Service
     * Overseer's own Group Overseer checkbox — just with two choices instead
     * of one, since a Ministerial Servant (unlike those two Admin roles) can
     * fill either of a Group's two non-Overseer slots. */
    val groupRole: RegularElderRole? = null,
    val publisherCategory: PublisherCategory? = null,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val result: TempCredentials? = null,
)

/**
 * "MINISTERIAL ACCOUNT" spec — reachable by Super-Admin, Admin (own
 * congregation only), and Coordinator Elder (own congregation), same access
 * set as Service Overseer enrollment. Unlike Service Overseer, there is no
 * per-congregation cap: "there can be multiple Ministerial Servant in every
 * congregation."
 */
class MinisterialServantEnrollmentViewModel(
    private val authRepository: AuthRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    congregationRepository: CongregationRepository,
) : ViewModel() {

    val congregations: StateFlow<List<Congregation>> =
        congregationRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(MinisterialServantEnrollmentUiState())
    val uiState: StateFlow<MinisterialServantEnrollmentUiState> = _uiState.asStateFlow()

    fun onFirstNameChange(value: String) = _uiState.update { it.copy(firstName = value.uppercase(), errorMessage = null) }
    fun onLastNameChange(value: String) = _uiState.update { it.copy(lastName = value.uppercase(), errorMessage = null) }
    fun onAddressChange(value: String) = _uiState.update { it.copy(address = value.uppercase(), errorMessage = null) }
    fun onEmailChange(value: String) = _uiState.update { it.copy(email = value, errorMessage = null) }
    fun onContactChange(value: String) = _uiState.update { it.copy(contact = value.uppercase(), errorMessage = null) }
    fun onCongregationSelected(id: String) = _uiState.update { it.copy(selectedCongregationId = id, errorMessage = null) }

    fun onGroupRoleToggled(role: RegularElderRole, checked: Boolean) = _uiState.update {
        it.copy(groupRole = if (checked) role else if (it.groupRole == role) null else it.groupRole)
    }

    fun onPublisherCategoryToggled(category: PublisherCategory, checked: Boolean) = _uiState.update {
        it.copy(publisherCategory = if (checked) category else if (it.publisherCategory == category) null else it.publisherCategory)
    }

    suspend fun isEnrollerSuperAdmin(enrollingPersonId: String): Boolean =
        PermissionChecker.hasAdminRole(roleAssignmentRepository.observeForPerson(enrollingPersonId).first(), AdminRole.SUPER_ADMIN)

    fun save(enrollingPersonId: String) {
        val state = _uiState.value
        if (state.firstName.isBlank() || state.lastName.isBlank() || state.address.isBlank() || state.contact.isBlank()) {
            _uiState.update { it.copy(errorMessage = "First name, last name, address, and contact are required.") }
            return
        }
        _uiState.update { it.copy(isSaving = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val enrollerAssignments = roleAssignmentRepository.observeForPerson(enrollingPersonId).first()
                val enrollerAssignment = PermissionChecker.fullCrudAssignment(enrollerAssignments)
                if (enrollerAssignment == null) {
                    _uiState.update { it.copy(isSaving = false, errorMessage = PermissionChecker.NO_ACCESS_MESSAGE) }
                    return@launch
                }
                val congregationId = if (PermissionChecker.hasAdminRole(enrollerAssignments, AdminRole.SUPER_ADMIN)) {
                    state.selectedCongregationId
                } else {
                    // Admin, Coordinator Elder, Service Overseer or Secretary.
                    enrollerAssignment.congregationId
                }
                if (congregationId == null) {
                    _uiState.update { it.copy(isSaving = false, errorMessage = "Select a congregation.") }
                    return@launch
                }
                val now = nowMillis()
                val credentials = authRepository.createAccountWithTempCredentials(
                    person = Person(
                        firstName = state.firstName.trim(),
                        lastName = state.lastName.trim(),
                        address = state.address.trim(),
                        email = state.email.trim().ifBlank { null },
                        contact = state.contact.trim(),
                    ),
                    roleAssignment = { personId ->
                        RoleAssignment(
                            personId = personId,
                            roleType = RoleType.serialize(RoleType.Admin(AdminRole.MINISTERIAL_SERVANT)),
                            congregationId = congregationId,
                            status = RoleAssignmentStatus.ACTIVE,
                            dateAssigned = now,
                            assignedByPersonId = enrollingPersonId,
                        )
                    },
                    enrollingPersonId = enrollingPersonId,
                )
                // saveNow, not save — same reasoning as the primary role
                // assignment in AuthRepository.createAccountWithTempCredentials:
                // this new account may sign in on a different device before this
                // one's next manual sync, and their role should already be
                // correct the moment they do.
                state.groupRole?.let { role ->
                    roleAssignmentRepository.saveNow(
                        RoleAssignment(
                            personId = credentials.personId,
                            roleType = RoleType.serialize(RoleType.Admin(AdminRole.REGULAR_ELDER)),
                            congregationId = congregationId,
                            regularElderRole = role,
                            status = RoleAssignmentStatus.ACTIVE,
                            dateAssigned = now,
                            assignedByPersonId = enrollingPersonId,
                        )
                    )
                }
                state.publisherCategory?.let { category ->
                    roleAssignmentRepository.saveNow(
                        RoleAssignment(
                            personId = credentials.personId,
                            roleType = RoleType.serialize(RoleType.Publisher(category)),
                            congregationId = congregationId,
                            status = RoleAssignmentStatus.ACTIVE,
                            dateAssigned = now,
                            assignedByPersonId = enrollingPersonId,
                        )
                    )
                }
                _uiState.update { it.copy(isSaving = false, result = credentials) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSaving = false, errorMessage = e.message ?: "Couldn't enroll this ministerial servant. Please try again.")
                }
            }
        }
    }
}
