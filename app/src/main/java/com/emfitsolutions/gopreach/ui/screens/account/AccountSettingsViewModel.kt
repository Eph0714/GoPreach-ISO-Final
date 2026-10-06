package com.emfitsolutions.gopreach.ui.screens.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.PreachingDay
import com.emfitsolutions.gopreach.data.repository.AuthRepository
import com.emfitsolutions.gopreach.data.repository.AuthResult
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.ui.components.PublisherFormState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountSettingsUiState(
    /** Personal Information — shown/editable for whoever is signed in,
     * including a Super-Admin (spec request: "add a name of the Super-Admin"
     * in Account Settings). No current-password check here, unlike
     * username/password below — this is profile info, not a login credential. */
    /** The signed-in person's own basic details — name, contacts, address, location, email. */
    val profile: PublisherFormState = PublisherFormState(),
    val isSavingProfile: Boolean = false,
    val profileMessage: String? = null,
    val profileError: String? = null,
    val capturingLocation: Boolean = false,
    val locationError: String? = null,

    /** "Preaching Availability" module — only ever shown/editable for a
     * Publisher (see [AccountSettingsScreen]'s own `isPublisher` gate), but
     * kept here rather than a separate ViewModel since it's still the same
     * one Person document. */
    val availableDays: Set<PreachingDay> = emptySet(),
    val availabilityRemarks: String = "",
    val isSavingAvailability: Boolean = false,
    val availabilityMessage: String? = null,
    val availabilityError: String? = null,

    val currentPasswordForUsername: String = "",
    val newUsername: String = "",
    val isSavingUsername: Boolean = false,
    val usernameMessage: String? = null,
    val usernameError: String? = null,

    val currentPassword: String = "",
    val newPassword: String = "",
    val confirmNewPassword: String = "",
    val isSavingPassword: Boolean = false,
    val passwordChanged: Boolean = false,
    val passwordError: String? = null,
)

/** Spec §1 — Super-Admin (or any signed-in user) editing their own account.
 * Username/password changes require the current password (spec's "require
 * the current password before changing X"); a password change signs the user
 * out afterward so they log back in with the new one. Name is plain profile
 * info and doesn't need re-authentication to change. */
@HiltViewModel
class AccountSettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val personRepository: PersonRepository,
    private val credentialStore: com.emfitsolutions.gopreach.data.repository.CredentialStore,
    private val quickLoginStore: com.emfitsolutions.gopreach.data.repository.QuickLoginStore,
    private val locationTracker: com.emfitsolutions.gopreach.data.location.LocationTracker,
    private val philippineLocationRepository: com.emfitsolutions.gopreach.data.repository.PhilippineLocationRepository,
) : ViewModel() {

    /** Changing the username or password makes every saved sign-in (Remember me, Biometrics, PIN, Pattern)
     * stale — each holds the old one — so all of them are cleared and must be set up again. */
    private fun forgetSavedSignIns() {
        runCatching { credentialStore.clearRemembered(); credentialStore.clearBiometric(); quickLoginStore.disableAll() }
    }

    private val _uiState = MutableStateFlow(AccountSettingsUiState())
    val uiState: StateFlow<AccountSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val person = authRepository.currentPersonId?.let { personRepository.get(it) }
            if (person != null) {
                _uiState.update {
                    it.copy(
                        profile = PublisherFormState.from(person, null, null),
                        availableDays = person.preachingAvailableDays.mapNotNull { day ->
                            runCatching { PreachingDay.valueOf(day) }.getOrNull()
                        }.toSet(),
                        availabilityRemarks = person.preachingAvailabilityRemarks.orEmpty(),
                    )
                }
            }
        }
    }

    fun onProfileChange(profile: PublisherFormState) = _uiState.update { it.copy(profile = profile, profileError = null, profileMessage = null) }

    fun hasLocationPermission(): Boolean = locationTracker.hasLocationPermission()

    /** Fills the coordinates (and, when they can be matched, province / municipality / barangay) from GPS. */
    fun captureLocation() {
        _uiState.update { it.copy(capturingLocation = true, locationError = null) }
        viewModelScope.launch {
            val location = locationTracker.getCurrentLocation()
            if (location == null) {
                _uiState.update { it.copy(capturingLocation = false, locationError = "Could not get a GPS fix. Make sure location is turned on and try again.") }
                return@launch
            }
            val geocoded = runCatching { locationTracker.reverseGeocodeAddress(location.lat, location.lng) }.getOrNull()
            val resolved = geocoded?.let { philippineLocationRepository.resolveFromGeocode(it) }
            _uiState.update {
                it.copy(
                    capturingLocation = false,
                    profile = it.profile.copy(
                        latitudeText = location.lat.toString(),
                        longitudeText = location.lng.toString(),
                        province = resolved?.provinceName ?: it.profile.province,
                        cityMunicipality = resolved?.muncityName ?: it.profile.cityMunicipality,
                        barangay = resolved?.barangayName ?: it.profile.barangay,
                    ),
                    profileMessage = null,
                )
            }
        }
    }

    /** "Available Days for Preaching" checkboxes — toggles [day] in the
     * current selection; nothing is saved until [saveAvailability]. */
    fun toggleAvailableDay(day: PreachingDay) = _uiState.update { state ->
        val updated = if (day in state.availableDays) state.availableDays - day else state.availableDays + day
        state.copy(availableDays = updated, availabilityMessage = null, availabilityError = null)
    }

    fun onAvailabilityRemarksChange(value: String) = _uiState.update { it.copy(availabilityRemarks = value, availabilityMessage = null, availabilityError = null) }

    /** "Save the selected days and remarks to the Publisher profile" —
     * plain profile info, same no-current-password-check treatment
     * [saveName] already gets (this isn't a login credential). */
    fun saveAvailability() {
        val state = _uiState.value
        val personId = authRepository.currentPersonId ?: run {
            _uiState.update { it.copy(availabilityError = "Session expired — please log in again.") }
            return
        }
        _uiState.update { it.copy(isSavingAvailability = true, availabilityError = null, availabilityMessage = null) }
        viewModelScope.launch {
            val person = personRepository.get(personId)
            if (person == null) {
                _uiState.update { it.copy(isSavingAvailability = false, availabilityError = "Account record not found.") }
                return@launch
            }
            personRepository.save(
                person.copy(
                    preachingAvailableDays = PreachingDay.entries.filter { it in state.availableDays }.map { it.name },
                    preachingAvailabilityRemarks = state.availabilityRemarks.trim().ifBlank { null },
                )
            )
            _uiState.update { it.copy(isSavingAvailability = false, availabilityMessage = "Preaching availability updated.") }
        }
    }

    /** Saves the person's own basic details. Only the personal fields change — never role, category, group, status or
     * congregation, which an administrator controls; the same checks as the Edit Publisher form apply. */
    fun saveProfile() {
        val form = _uiState.value.profile
        val problem = when {
            form.firstName.isBlank() -> "First Name is required."
            form.lastName.isBlank() -> "Last Name is required."
            form.address.isBlank() -> "Full Address is required."
            form.contact.isBlank() -> "Contact is required."
            else -> form.formProblem
        }
        if (problem != null) {
            _uiState.update { it.copy(profileError = problem, profileMessage = null) }
            return
        }
        val personId = authRepository.currentPersonId ?: run {
            _uiState.update { it.copy(profileError = "Session expired — please log in again.") }
            return
        }
        _uiState.update { it.copy(isSavingProfile = true, profileError = null, profileMessage = null) }
        viewModelScope.launch {
            val person = personRepository.get(personId)
            if (person == null) {
                _uiState.update { it.copy(isSavingProfile = false, profileError = "Account record not found.") }
                return@launch
            }
            // Written onto the stored record, so everything else on it (username, roles' links, status, remarks) is untouched.
            val updated = form.applyTo(person).copy(
                gender = person.gender,
                accountStatus = person.accountStatus,
                remarks = person.remarks,
            )
            personRepository.save(updated)
            _uiState.update { it.copy(isSavingProfile = false, profileMessage = "Your information was saved.") }
        }
    }

    fun onCurrentPasswordForUsernameChange(value: String) = _uiState.update { it.copy(currentPasswordForUsername = value, usernameError = null) }
    fun onNewUsernameChange(value: String) = _uiState.update { it.copy(newUsername = value, usernameError = null) }
    fun onCurrentPasswordChange(value: String) = _uiState.update { it.copy(currentPassword = value, passwordError = null) }
    fun onNewPasswordChange(value: String) = _uiState.update { it.copy(newPassword = value, passwordError = null) }
    fun onConfirmNewPasswordChange(value: String) = _uiState.update { it.copy(confirmNewPassword = value, passwordError = null) }

    fun saveUsername() {
        val state = _uiState.value
        if (state.newUsername.isBlank() || state.currentPasswordForUsername.isBlank()) {
            _uiState.update { it.copy(usernameError = "Enter your current password and a new username.") }
            return
        }
        _uiState.update { it.copy(isSavingUsername = true, usernameError = null, usernameMessage = null) }
        viewModelScope.launch {
            when (val result = authRepository.changeUsername(state.newUsername, state.currentPasswordForUsername)) {
                is AuthResult.Success -> {
                    forgetSavedSignIns()
                    _uiState.update {
                        it.copy(isSavingUsername = false, usernameMessage = "Username updated. Saved logins (Remember me, Biometrics, PIN, Pattern) were cleared — set them up again.", newUsername = "", currentPasswordForUsername = "")
                    }
                }
                is AuthResult.Error -> _uiState.update { it.copy(isSavingUsername = false, usernameError = result.message) }
            }
        }
    }

    fun savePassword() {
        val state = _uiState.value
        if (state.currentPassword.isBlank() || state.newPassword.isBlank()) {
            _uiState.update { it.copy(passwordError = "Enter your current and new password.") }
            return
        }
        if (state.newPassword != state.confirmNewPassword) {
            _uiState.update { it.copy(passwordError = "New password and confirmation don't match.") }
            return
        }
        _uiState.update { it.copy(isSavingPassword = true, passwordError = null) }
        viewModelScope.launch {
            when (val result = authRepository.changePassword(state.currentPassword, state.newPassword)) {
                is AuthResult.Success -> {
                    forgetSavedSignIns()
                    _uiState.update { it.copy(isSavingPassword = false, passwordChanged = true) }
                }
                is AuthResult.Error -> _uiState.update { it.copy(isSavingPassword = false, passwordError = result.message) }
            }
        }
    }
}
