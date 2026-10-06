package com.emfitsolutions.gopreach.ui.screens.enrollment

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
import com.emfitsolutions.gopreach.ui.screens.elders.ELDER_PRIMARY_ROLES
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EldersEnrollmentUiState(
    val firstName: String = "",
    val lastName: String = "",
    val address: String = "",
    val email: String = "",
    val contact: String = "",
    /** Only used when the enroller is a Super-Admin — Admin/Coordinator
     * Elder are otherwise auto-assigned to their own congregation, same as
     * every enrollment screen in this app already works. */
    val selectedCongregationId: String? = null,
    /** "The Admin may select one or multiple roles for the same person" —
     * independent checkboxes, not a mutually-exclusive picker: nothing in
     * the data model (several simultaneous Admin RoleAssignment docs already
     * possible for one person) requires forcing these apart, and spec §12
     * explicitly says not to invent a restriction the source spec never
     * asked for. */
    val selectedAdminRoles: Set<AdminRole> = emptySet(),
    /** "Group Overseer"/"Assistant Group Overseer" — mutually exclusive with
     * each other (a person fills exactly one Group slot), independent of
     * [selectedAdminRoles]. `null` means neither checked. Attaches to this
     * person's own REGULAR_ELDER assignment if [AdminRole.REGULAR_ELDER] is
     * checked, otherwise becomes its own additional REGULAR_ELDER
     * assignment — the same pattern Coordinator/Service Overseer enrollment
     * already used before this consolidation. */
    val regularElderRole: RegularElderRole? = null,
    /** Mutually exclusive — checking one clears the other two. `null` means
     * none selected; this person gets no Publisher-category RoleAssignment
     * at all. */
    val publisherCategory: PublisherCategory? = null,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val result: TempCredentials? = null,
)

/**
 * "Consolidate Elder, Coordinator Elder, Service Overseer and Secretary
 * Enrollment" — the single Add form for Regular Elder, Coordinator Elder,
 * Service Overseer, and Secretary, replacing the three separate enrollment
 * screens those used to be (spec §1/§5). Reachable by the exact same
 * Super-Admin/Admin (own congregation)/Coordinator Elder (own congregation)
 * set every one of those three already shared.
 */
class EldersEnrollmentViewModel(
    private val authRepository: AuthRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    congregationRepository: CongregationRepository,
) : ViewModel() {

    val congregations: StateFlow<List<Congregation>> =
        congregationRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(EldersEnrollmentUiState())
    val uiState: StateFlow<EldersEnrollmentUiState> = _uiState.asStateFlow()

    fun onFirstNameChange(value: String) = _uiState.update { it.copy(firstName = value.uppercase(), errorMessage = null) }
    fun onLastNameChange(value: String) = _uiState.update { it.copy(lastName = value.uppercase(), errorMessage = null) }
    fun onAddressChange(value: String) = _uiState.update { it.copy(address = value.uppercase(), errorMessage = null) }
    fun onEmailChange(value: String) = _uiState.update { it.copy(email = value, errorMessage = null) }
    fun onContactChange(value: String) = _uiState.update { it.copy(contact = value.uppercase(), errorMessage = null) }
    fun onCongregationSelected(id: String) = _uiState.update { it.copy(selectedCongregationId = id, errorMessage = null) }

    fun onAdminRoleToggled(role: AdminRole, checked: Boolean) = _uiState.update {
        it.copy(selectedAdminRoles = if (checked) it.selectedAdminRoles + role else it.selectedAdminRoles - role, errorMessage = null)
    }

    /** A single nullable field naturally gives "checking one clears the
     * other" behavior for the two Group-slot checkboxes. */
    fun onRegularElderRoleToggled(role: RegularElderRole, checked: Boolean) = _uiState.update {
        it.copy(regularElderRole = if (checked) role else if (it.regularElderRole == role) null else it.regularElderRole)
    }

    /** Same shape for the three mutually-exclusive Publisher categories. */
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
        // "At least one applicable role should be selected unless the
        // existing business rules explicitly permit an unassigned person" —
        // no existing GoPreach enrollment screen ever creates an account
        // with zero roles, so this module doesn't either.
        if (state.selectedAdminRoles.isEmpty() && state.regularElderRole == null && state.publisherCategory == null) {
            _uiState.update { it.copy(errorMessage = "Select at least one role.") }
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
                // Admin, Coordinator Elder, Service Overseer or Secretary —
                // all have full access within their own congregation.
                enrollerAssignment.congregationId
            }
            if (congregationId == null) {
                _uiState.update { it.copy(isSaving = false, errorMessage = "Select a congregation.") }
                return@launch
            }
            // "There must be 1 Service Overseer in every congregation" —
            // unchanged from the old dedicated Service Overseer enrollment
            // screen; Secretary carries no such cap (see that role's own doc
            // comment on AdminRole).
            if (AdminRole.SERVICE_OVERSEER in state.selectedAdminRoles) {
                val allAssignments = roleAssignmentRepository.observeAll().first()
                val alreadyHasServiceOverseer = allAssignments.any {
                    it.status == RoleAssignmentStatus.ACTIVE &&
                        it.congregationId == congregationId &&
                        (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role == AdminRole.SERVICE_OVERSEER
                }
                if (alreadyHasServiceOverseer) {
                    _uiState.update { it.copy(isSaving = false, errorMessage = "This congregation already has an active Service Overseer.") }
                    return@launch
                }
            }

            val now = System.currentTimeMillis()
            // Every checked primary role becomes its own RoleAssignment doc
            // (spec §8: one person, several simultaneous role assignments).
            // Order doesn't matter functionally — whichever comes first is
            // just the one AuthRepository creates the account itself with;
            // every other one is added right after via saveNow, same as
            // this app's existing "primary role + additional roles" pattern.
            val rolesToCreate = ELDER_PRIMARY_ROLES.filter { it in state.selectedAdminRoles }
            val firstRole = rolesToCreate.firstOrNull() ?: AdminRole.REGULAR_ELDER
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
                        roleType = RoleType.serialize(RoleType.Admin(firstRole)),
                        congregationId = congregationId,
                        regularElderRole = if (firstRole == AdminRole.REGULAR_ELDER) state.regularElderRole else null,
                        status = RoleAssignmentStatus.ACTIVE,
                        dateAssigned = now,
                        assignedByPersonId = enrollingPersonId,
                    )
                },
                enrollingPersonId = enrollingPersonId,
            )
            // saveNow for every extra role beyond the first — same reasoning
            // as every other multi-role enrollment screen in this app: this
            // new account may sign in on a different device before this
            // one's next manual sync, and its roles should already be
            // correct the moment it does.
            rolesToCreate.drop(1).forEach { role ->
                roleAssignmentRepository.saveNow(
                    RoleAssignment(
                        personId = credentials.personId,
                        roleType = RoleType.serialize(RoleType.Admin(role)),
                        congregationId = congregationId,
                        status = RoleAssignmentStatus.ACTIVE,
                        dateAssigned = now,
                        assignedByPersonId = enrollingPersonId,
                    )
                )
            }
            // Group Overseer/Assistant checked without Regular Elder itself
            // checked — a separate, additional REGULAR_ELDER assignment
            // (never a second one if Regular Elder was already the primary
            // role created above).
            if (state.regularElderRole != null && firstRole != AdminRole.REGULAR_ELDER) {
                roleAssignmentRepository.saveNow(
                    RoleAssignment(
                        personId = credentials.personId,
                        roleType = RoleType.serialize(RoleType.Admin(AdminRole.REGULAR_ELDER)),
                        congregationId = congregationId,
                        regularElderRole = state.regularElderRole,
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
                it.copy(isSaving = false, errorMessage = e.localizedMessage ?: "Couldn't enroll this person. Please try again.")
            }
          }
        }
    }
}
