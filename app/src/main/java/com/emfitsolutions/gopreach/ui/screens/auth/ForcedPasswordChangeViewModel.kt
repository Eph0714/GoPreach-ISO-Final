package com.emfitsolutions.gopreach.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.location.LocationTracker
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.repository.AuthRepository
import com.emfitsolutions.gopreach.data.repository.AuthResult
import com.emfitsolutions.gopreach.data.repository.CredentialStore
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.PhilippineLocationRepository
import com.emfitsolutions.gopreach.data.repository.QuickLoginMethod
import com.emfitsolutions.gopreach.data.repository.QuickLoginStore
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.ui.components.PublisherFormState
import com.emfitsolutions.gopreach.ui.screens.login.PendingLoginNotice
import com.emfitsolutions.gopreach.ui.screens.login.deviceHasBiometrics
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the first-time setup is. A Publisher goes through all of them; anyone else only [CREDENTIALS]. */
enum class SetupStep { LOADING, CREDENTIALS, PROFILE_REVIEW, PROFILE_EDIT, METHODS, FINISHING, NEEDS_ONLINE }

data class FirstLoginUiState(
    val step: SetupStep = SetupStep.LOADING,
    val isPublisher: Boolean = false,
    val newUsername: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    /** The details being reviewed — edited in place during [SetupStep.PROFILE_EDIT]. */
    val form: PublisherFormState = PublisherFormState(),
    val showConfirmDialog: Boolean = false,
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
    val capturingLocation: Boolean = false,
    val locationError: String? = null,
    val biometricOn: Boolean = false,
    val pinOn: Boolean = false,
    val patternOn: Boolean = false,
    val deviceHasBiometrics: Boolean = false,
    /** Whether the optional login methods can be set up now: they need the password typed in step 1, which is only held in memory. */
    val canSetUpMethods: Boolean = false,
    val methodMessage: String? = null,
)

/**
 * First-time setup after signing in with temporary credentials.
 *
 * Publisher: choose own username + password -> check/correct personal details -> confirm and save ->
 * optionally set up Biometrics/PIN/Pattern -> signed out automatically -> must log in again with the new
 * credentials, and only that sign-in finishes setup. Everyone else: choose credentials, then sign in again.
 *
 * The step is derived from the saved [Person], so it resumes correctly if the app is closed part-way:
 * temporary password still present -> credentials; changed but not confirmed -> review; confirmed -> the
 * re-login check (a session older than the confirmation is signed out; a newer sign-in completes setup).
 */
@HiltViewModel
class ForcedPasswordChangeViewModel @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val authRepository: AuthRepository,
    private val personRepository: PersonRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val credentialStore: CredentialStore,
    private val quickLoginStore: QuickLoginStore,
    private val locationTracker: LocationTracker,
    private val philippineLocationRepository: PhilippineLocationRepository,
    private val pendingLoginNotice: PendingLoginNotice,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FirstLoginUiState())
    val uiState: StateFlow<FirstLoginUiState> = _uiState.asStateFlow()

    private var person: Person? = null
    /** The form as last accepted in the review — what Cancel in the editor goes back to. */
    private var reviewedForm: PublisherFormState = PublisherFormState()
    /** Held only in memory, only until the wizard ends: needed to set up the optional login methods. */
    private var passwordInMemory: String = ""

    init {
        viewModelScope.launch { resolveStep() }
    }

    private suspend fun resolveStep() {
        val personId = authRepository.currentPersonId
        val loaded = personId?.let { personRepository.get(it) }
        if (personId == null || loaded == null) {
            _uiState.update { it.copy(step = SetupStep.CREDENTIALS, errorMessage = "Couldn't load your account. Please log in again.") }
            return
        }
        person = loaded
        val isPublisher = roleAssignmentRepository.observeForPerson(personId).first().any { it.resolvedRoleTypeOrNull() is RoleType.Publisher }
        val form = PublisherFormState.from(loaded, null, null)
        reviewedForm = form
        _uiState.update {
            it.copy(isPublisher = isPublisher, form = form, newUsername = "", deviceHasBiometrics = deviceHasBiometrics(context))
        }
        when {
            !isPublisher || loaded.temporaryPassword != null -> _uiState.update { it.copy(step = SetupStep.CREDENTIALS) }
            loaded.setupConfirmedAt == null -> _uiState.update { it.copy(step = SetupStep.PROFILE_REVIEW) }
            else -> checkRelogin(loaded.setupConfirmedAt!!)
        }
    }

    /** Setup was confirmed earlier. Only a password sign-in made AFTER the confirmation proves the new credentials. */
    private suspend fun checkRelogin(confirmedAt: Long) {
        val signedInAt = authRepository.lastSignInAtMillis()
        when {
            signedInAt == null -> _uiState.update { it.copy(step = SetupStep.NEEDS_ONLINE) }
            signedInAt > confirmedAt -> {
                _uiState.update { it.copy(step = SetupStep.FINISHING) }
                when (val result = authRepository.completeFirstLogin()) {
                    // The session now reports setup finished, so navigation moves on to the dashboard by itself.
                    is AuthResult.Success -> Unit
                    is AuthResult.Error -> _uiState.update { it.copy(step = SetupStep.NEEDS_ONLINE, errorMessage = result.message) }
                }
            }
            else -> {
                // This session predates the confirmation (the app was closed before the automatic sign-out).
                pendingLoginNotice.post("Please Log In Again", "Your account setup was saved. Log in again with your new username and password to finish.")
                authRepository.signOut()
            }
        }
    }

    // --- Step 1: credentials -------------------------------------------------------------------

    fun onUsernameChange(value: String) = _uiState.update { it.copy(newUsername = value, errorMessage = null) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(newPassword = value, errorMessage = null) }
    fun onConfirmPasswordChange(value: String) = _uiState.update { it.copy(confirmPassword = value, errorMessage = null) }

    fun submitCredentials() {
        val state = _uiState.value
        val problem = when {
            state.newUsername.isBlank() -> "Choose a username."
            state.newPassword.length < 8 -> "Password must be at least 8 characters."
            state.newPassword != state.confirmPassword -> "Passwords don't match."
            else -> null
        }
        if (problem != null) { _uiState.update { it.copy(errorMessage = problem) }; return }
        _uiState.update { it.copy(isBusy = true, errorMessage = null) }
        viewModelScope.launch {
            if (!state.isPublisher) {
                // Not a Publisher: choose credentials, sign out, log in again (the original flow).
                // Posted first: the sign-out inside forcedPasswordChange swaps in the Login screen straight away.
                pendingLoginNotice.post("Account Setup Complete", "Your account information has been successfully saved. Please log in again using your new credentials.")
                when (val result = authRepository.forcedPasswordChange(state.newUsername.trim(), state.newPassword)) {
                    is AuthResult.Success -> {
                        forgetStaleSignIns()
                        _uiState.update { it.copy(isBusy = false) }
                    }
                    is AuthResult.Error -> {
                        pendingLoginNotice.take()
                        _uiState.update { it.copy(isBusy = false, errorMessage = result.message) }
                    }
                }
                return@launch
            }
            when (val result = authRepository.changeCredentialsKeepingSession(state.newUsername.trim(), state.newPassword)) {
                is AuthResult.Success -> {
                    // Whatever was saved for the temporary login (Remember me, Biometrics, PIN, Pattern) is stale now.
                    forgetStaleSignIns()
                    passwordInMemory = state.newPassword
                    person = result.person
                    val form = PublisherFormState.from(result.person, null, null)
                    reviewedForm = form
                    _uiState.update {
                        it.copy(isBusy = false, step = SetupStep.PROFILE_REVIEW, form = form, newPassword = "", confirmPassword = "", canSetUpMethods = true)
                    }
                }
                is AuthResult.Error -> _uiState.update { it.copy(isBusy = false, errorMessage = result.message) }
            }
        }
    }

    private fun forgetStaleSignIns() {
        runCatching { credentialStore.clearRemembered(); credentialStore.clearBiometric(); quickLoginStore.disableAll() }
    }

    // --- Step 2: check / correct the personal details ------------------------------------------

    fun onFormChange(form: PublisherFormState) = _uiState.update { it.copy(form = form, errorMessage = null) }
    fun startEditing() = _uiState.update { it.copy(step = SetupStep.PROFILE_EDIT, errorMessage = null) }

    fun cancelEditing() = _uiState.update { it.copy(step = SetupStep.PROFILE_REVIEW, form = reviewedForm, errorMessage = null, locationError = null) }

    /** The same checks the Edit Publisher form makes. */
    private fun profileProblem(form: PublisherFormState): String? = when {
        form.firstName.isBlank() -> "First Name is required."
        form.lastName.isBlank() -> "Last Name is required."
        form.address.isBlank() -> "Full Address is required."
        form.contact.isBlank() -> "Contact is required."
        else -> form.formProblem
    }

    fun applyEdits() {
        val form = _uiState.value.form
        val problem = profileProblem(form)
        if (problem != null) { _uiState.update { it.copy(errorMessage = problem) }; return }
        reviewedForm = form
        _uiState.update { it.copy(step = SetupStep.PROFILE_REVIEW, errorMessage = null) }
    }

    fun askToConfirm() {
        val problem = profileProblem(_uiState.value.form)
        if (problem != null) { _uiState.update { it.copy(errorMessage = problem) }; return }
        _uiState.update { it.copy(showConfirmDialog = true, errorMessage = null) }
    }

    fun dismissConfirm() = _uiState.update { it.copy(showConfirmDialog = false) }

    fun hasLocationPermission(): Boolean = locationTracker.hasLocationPermission()

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
                    form = it.form.copy(
                        latitudeText = location.lat.toString(),
                        longitudeText = location.lng.toString(),
                        province = resolved?.provinceName ?: it.form.province,
                        cityMunicipality = resolved?.muncityName ?: it.form.cityMunicipality,
                        barangay = resolved?.barangayName ?: it.form.barangay,
                    ),
                )
            }
        }
    }

    /** "Confirm & Save" in the final dialog: nothing is marked confirmed before this. */
    fun confirmAndSave() {
        val current = person ?: return
        val form = _uiState.value.form
        _uiState.update { it.copy(isBusy = true, showConfirmDialog = false, errorMessage = null) }
        viewModelScope.launch {
            when (val result = authRepository.confirmFirstLoginProfile(form.applyTo(current))) {
                is AuthResult.Success -> {
                    person = result.person
                    reviewedForm = form
                    _uiState.update { it.copy(isBusy = false, step = SetupStep.METHODS) }
                }
                is AuthResult.Error -> _uiState.update { it.copy(isBusy = false, errorMessage = result.message) }
            }
        }
    }

    // --- Step 3 (optional): extra login methods --------------------------------------------------

    private fun methodUsername(): String? = person?.username?.takeIf { it.isNotBlank() }

    /** Called after the system biometric prompt succeeded. */
    fun enableBiometric() {
        val username = methodUsername()
        if (username == null || passwordInMemory.isEmpty()) { _uiState.update { it.copy(methodMessage = "Couldn't turn on Biometrics. You can do it later in Account Settings.") }; return }
        runCatching { credentialStore.saveBiometric(username, passwordInMemory) }
            .onSuccess { _uiState.update { it.copy(biometricOn = true, methodMessage = null) } }
            .onFailure { _uiState.update { it.copy(methodMessage = "Couldn't turn on Biometrics. You can do it later in Account Settings.") } }
    }

    fun onBiometricError(message: String) = _uiState.update { it.copy(methodMessage = message) }

    fun enrollQuickLogin(method: QuickLoginMethod, secret: String) {
        val username = methodUsername()
        if (username == null || passwordInMemory.isEmpty()) { _uiState.update { it.copy(methodMessage = "Couldn't set up your ${method.label}. You can do it later in Account Settings.") }; return }
        runCatching { quickLoginStore.enroll(method, secret, username, passwordInMemory) }
            .onSuccess {
                _uiState.update {
                    when (method) {
                        QuickLoginMethod.PIN -> it.copy(pinOn = true, methodMessage = null)
                        QuickLoginMethod.PATTERN -> it.copy(patternOn = true, methodMessage = null)
                    }
                }
            }
            .onFailure { _uiState.update { it.copy(methodMessage = "Couldn't set up your ${method.label}. You can do it later in Account Settings.") } }
    }

    // --- Finish: automatic sign-out ---------------------------------------------------------------

    /** Skip for Now / Continue: the setup is saved, so sign out and send the Publisher back to Login. */
    fun finishAndSignOut() {
        passwordInMemory = ""
        pendingLoginNotice.post("Account Setup Complete", "Your account information has been successfully saved. Please log in again using your new credentials.")
        authRepository.signOut()
    }

    fun signOutNow() = authRepository.signOut()
}
