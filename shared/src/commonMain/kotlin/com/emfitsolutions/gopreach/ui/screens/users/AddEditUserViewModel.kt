package com.emfitsolutions.gopreach.ui.screens.users

import com.emfitsolutions.gopreach.platform.nowMillis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.AccountStatus
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.Permission
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.model.ScopeType
import com.emfitsolutions.gopreach.data.model.UserAccessGrant
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.AuthRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.GroupRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.TempCredentials
import com.emfitsolutions.gopreach.data.repository.UserAccessGrantRepository
import com.emfitsolutions.gopreach.ui.screens.enrollment.splitName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddEditUserUiState(
    val isEditMode: Boolean = false,
    val fullName: String = "",
    /** Edit-mode-only fields — show the complete stored Person record, not
     * just permissions/scope/status (spec: "Show Complete Record Information
     * When Editing"). [username]/[createdAt] are read-only/system-generated. */
    val firstName: String = "",
    val lastName: String = "",
    val address: String = "",
    val contact: String = "",
    val email: String = "",
    val username: String = "",
    val createdAt: Long = 0L,
    val selectedPermissions: Set<Permission> = emptySet(),
    val scopeType: ScopeType = ScopeType.SELECTED_CONGREGATIONS,
    val selectedCongregationIds: Set<String> = emptySet(),
    val selectedGroupIds: Set<String> = emptySet(),
    val status: AccountStatus = AccountStatus.ACTIVE,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val savedResult: TempCredentials? = null,
    val saveCompleted: Boolean = false,
)

/**
 * Backs both [ManageUsersScreen]'s [ADD] and [Edit] flows (spec §4/§8) — one
 * screen, one form, since editing a Circuit Overseer/custom user's permissions
 * and scope is the exact same shape as creating one, minus name/credentials.
 */
class AddEditUserViewModel(
    private val authRepository: AuthRepository,
    private val personRepository: PersonRepository,
    private val userAccessGrantRepository: UserAccessGrantRepository,
    private val auditLogRepository: AuditLogRepository,
    congregationRepository: CongregationRepository,
    groupRepository: GroupRepository,
) : ViewModel() {

    val congregations: StateFlow<List<Congregation>> =
        congregationRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val groups: StateFlow<List<Group>> =
        groupRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(AddEditUserUiState())
    val uiState: StateFlow<AddEditUserUiState> = _uiState.asStateFlow()

    private var editingPersonId: String? = null

    fun loadForEdit(personId: String) {
        editingPersonId = personId
        _uiState.update { it.copy(isEditMode = true, isLoading = true) }
        viewModelScope.launch {
            val person = personRepository.get(personId)
            val grant = userAccessGrantRepository.get(personId)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    fullName = person?.fullName.orEmpty(),
                    firstName = person?.firstName.orEmpty(),
                    lastName = person?.lastName.orEmpty(),
                    address = person?.address.orEmpty(),
                    contact = person?.contact.orEmpty(),
                    email = person?.email.orEmpty(),
                    username = person?.username.orEmpty(),
                    createdAt = person?.createdAt ?: 0L,
                    status = person?.accountStatus ?: AccountStatus.ACTIVE,
                    selectedPermissions = grant?.resolvedPermissions.orEmpty(),
                    scopeType = grant?.resolvedScopeType ?: ScopeType.SELECTED_CONGREGATIONS,
                    selectedCongregationIds = grant?.scopeCongregationIds?.toSet().orEmpty(),
                    selectedGroupIds = grant?.scopeGroupIds?.toSet().orEmpty(),
                )
            }
        }
    }

    fun onFullNameChange(value: String) = _uiState.update { it.copy(fullName = value.uppercase(), errorMessage = null) }
    fun onFirstNameChange(value: String) = _uiState.update { it.copy(firstName = value.uppercase(), errorMessage = null) }
    fun onLastNameChange(value: String) = _uiState.update { it.copy(lastName = value.uppercase(), errorMessage = null) }
    fun onAddressChange(value: String) = _uiState.update { it.copy(address = value.uppercase(), errorMessage = null) }
    fun onContactChange(value: String) = _uiState.update { it.copy(contact = value.uppercase(), errorMessage = null) }
    fun onEmailChange(value: String) = _uiState.update { it.copy(email = value, errorMessage = null) }

    fun onPermissionToggled(permission: Permission, checked: Boolean) = _uiState.update {
        it.copy(selectedPermissions = if (checked) it.selectedPermissions + permission else it.selectedPermissions - permission)
    }

    fun onScopeTypeChange(type: ScopeType) = _uiState.update { it.copy(scopeType = type) }

    fun onCongregationToggled(congregationId: String, checked: Boolean) = _uiState.update {
        it.copy(selectedCongregationIds = if (checked) it.selectedCongregationIds + congregationId else it.selectedCongregationIds - congregationId)
    }

    fun onGroupToggled(groupId: String, checked: Boolean) = _uiState.update {
        it.copy(selectedGroupIds = if (checked) it.selectedGroupIds + groupId else it.selectedGroupIds - groupId)
    }

    fun onStatusChange(status: AccountStatus) = _uiState.update { it.copy(status = status) }

    private fun buildGrant(personId: String, actingPersonId: String, previous: UserAccessGrant?): UserAccessGrant {
        val state = _uiState.value
        val now = nowMillis()
        return UserAccessGrant(
            personId = personId,
            permissions = state.selectedPermissions.map { it.name },
            scopeType = state.scopeType.name,
            scopeCongregationIds = if (state.scopeType == ScopeType.SELECTED_CONGREGATIONS) state.selectedCongregationIds.toList() else emptyList(),
            scopeGroupIds = if (state.scopeType == ScopeType.SELECTED_GROUPS) state.selectedGroupIds.toList() else emptyList(),
            circuitCode = previous?.circuitCode,
            createdByPersonId = previous?.createdByPersonId ?: actingPersonId,
            createdAt = previous?.createdAt ?: now,
            lastEditedByPersonId = actingPersonId,
            lastEditedAt = now,
        )
    }

    /** Spec §4 — creates a new restricted user with temporary credentials, then
     * attaches their [UserAccessGrant]. Role is fixed to CIRCUIT_OVERSEER: this
     * screen *is* "Custom User" too, since a custom user is just a Circuit
     * Overseer-shaped account with a different permission/scope combination —
     * there's no behavioral difference the app needs a separate enum value for. */
    fun createUser(enrollingPersonId: String) {
        val state = _uiState.value
        if (state.fullName.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Full name is required.") }
            return
        }
        if (state.selectedPermissions.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Select at least one permission.") }
            return
        }
        if (state.scopeType == ScopeType.SELECTED_CONGREGATIONS && state.selectedCongregationIds.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Select at least one congregation, or choose All Congregations.") }
            return
        }
        if (state.scopeType == ScopeType.SELECTED_GROUPS && state.selectedGroupIds.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Select at least one group.") }
            return
        }
        _uiState.update { it.copy(isSaving = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val (firstName, lastName) = splitName(state.fullName)
                val credentials = authRepository.createAccountWithTempCredentials(
                    person = Person(firstName = firstName, lastName = lastName),
                    roleAssignment = { personId ->
                        RoleAssignment(
                            personId = personId,
                            roleType = RoleType.serialize(RoleType.Admin(AdminRole.CIRCUIT_OVERSEER)),
                            status = RoleAssignmentStatus.ACTIVE,
                            dateAssigned = nowMillis(),
                            assignedByPersonId = enrollingPersonId,
                        )
                    },
                    enrollingPersonId = enrollingPersonId,
                )
                userAccessGrantRepository.save(buildGrant(credentials.personId, enrollingPersonId, previous = null))
                auditLogRepository.log(
                    actorPersonId = enrollingPersonId,
                    action = "SET_USER_PERMISSIONS",
                    targetType = "Person",
                    targetId = credentials.personId,
                    details = "permissions: ${state.selectedPermissions} scope: ${state.scopeType}",
                )
                _uiState.update { it.copy(isSaving = false, savedResult = credentials, saveCompleted = true) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSaving = false, errorMessage = e.message ?: "Couldn't create this user. Please try again.")
                }
            }
        }
    }

    /** Spec §6/§8/§11 — Super-Admin editing an existing restricted user's
     * permissions, scope, and account status; every change is audit-logged with
     * its previous and new value. */
    fun saveEdit(actingPersonId: String) {
        val personId = editingPersonId ?: return
        _uiState.update { it.copy(isSaving = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val previousGrant = userAccessGrantRepository.get(personId)
                if (!previousGrant?.circuitCode.isNullOrBlank()) {
                    _uiState.update { it.copy(isSaving = false, errorMessage = "This Circuit Overseer belongs to circuit ${previousGrant?.circuitCode}. Edit it under Circuit Overseer Accounts.") }
                    return@launch
                }
                val newGrant = buildGrant(personId, actingPersonId, previousGrant)
                userAccessGrantRepository.save(newGrant)
                if (previousGrant?.resolvedPermissions != newGrant.resolvedPermissions ||
                    previousGrant.resolvedScopeType != newGrant.resolvedScopeType ||
                    previousGrant.scopeCongregationIds.toSet() != newGrant.scopeCongregationIds.toSet() ||
                    previousGrant.scopeGroupIds.toSet() != newGrant.scopeGroupIds.toSet()
                ) {
                    auditLogRepository.log(
                        actorPersonId = actingPersonId,
                        action = "CHANGE_USER_PERMISSIONS",
                        targetType = "Person",
                        targetId = personId,
                        details = "permissions: ${previousGrant?.resolvedPermissions.orEmpty()} -> ${newGrant.resolvedPermissions}; " +
                            "scope: ${previousGrant?.resolvedScopeType} ${previousGrant?.scopeCongregationIds.orEmpty()} -> " +
                            "${newGrant.resolvedScopeType} ${newGrant.scopeCongregationIds}",
                    )
                }
                val person = personRepository.get(personId)
                if (person != null) {
                    val state = _uiState.value
                    val updated = person.copy(
                        firstName = state.firstName.trim().ifBlank { person.firstName },
                        lastName = state.lastName.trim().ifBlank { person.lastName },
                        address = state.address.trim(),
                        contact = state.contact.trim(),
                        email = state.email.trim().ifBlank { null },
                        accountStatus = state.status,
                    )
                    if (updated != person) personRepository.save(updated)
                    if (person.accountStatus != state.status) {
                        auditLogRepository.log(
                            actorPersonId = actingPersonId,
                            action = "CHANGE_USER_STATUS",
                            targetType = "Person",
                            targetId = personId,
                            details = "status: ${person.accountStatus} -> ${state.status}",
                        )
                    }
                }
                _uiState.update { it.copy(isSaving = false, saveCompleted = true) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSaving = false, errorMessage = e.message ?: "Couldn't save changes. Please try again.")
                }
            }
        }
    }
}
