package com.emfitsolutions.gopreach.ui.screens.login

import android.content.Context
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.R
import com.emfitsolutions.gopreach.data.repository.AuthRepository
import com.emfitsolutions.gopreach.data.repository.AuthResult
import com.emfitsolutions.gopreach.data.repository.CredentialStore
import com.emfitsolutions.gopreach.data.repository.QuickLoginCheck
import com.emfitsolutions.gopreach.data.repository.QuickLoginMethod
import com.emfitsolutions.gopreach.data.repository.QuickLoginStore
import com.emfitsolutions.gopreach.data.sync.ConnectivityObserver
import com.emfitsolutions.gopreach.data.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AuthDebug"

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val rememberMe: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    /** Set once sign-in succeeds; the nav layer reacts to this to route the user
     * on to either the forced-password-change flow or their home screen. */
    val requiresPasswordChange: Boolean? = null,
    val signedIn: Boolean = false,
    /** The user explicitly set up biometric login for GoPreach on this device. Distinct from
     * the device merely having a fingerprint/face — "Login with Biometrics" is always shown,
     * but only signs in when this is true. */
    val biometricEnrolled: Boolean = false,
    /** "Biometric Login Not Set Up" message after tapping the button without enrolling. */
    val showBiometricNotSetUp: Boolean = false,
    /** A title + message dialog: PIN/Pattern/Passkey tapped without being set up, or a method turned off. */
    val notice: Pair<String, String>? = null,
    /** The PIN/Pattern sign-in dialog that's open, if any, with the last attempt's outcome. */
    val quickLoginMethod: QuickLoginMethod? = null,
    val quickLoginError: String? = null,
    val quickLoginLockedMs: Long = 0L,
)

class LoginViewModel(
    private val authRepository: AuthRepository,
    private val credentialStore: CredentialStore,
    private val quickLoginStore: QuickLoginStore,
    private val connectivityObserver: ConnectivityObserver,
    private val syncScheduler: SyncScheduler,
    private val biometricEnrollmentOffer: BiometricEnrollmentOffer,
    private val pendingLoginNotice: PendingLoginNotice,
    private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    /** The remembered account's username (not a secret). The password stays in the secure
     * store and is only read once the typed username matches this. */
    private var rememberedUsername: String? = null

    /** True while the password field holds a value we filled in from the remembered login,
     * so it can be taken back out if the username is edited to something else. */
    private var passwordAutoFilled = false


    init {
        // CredentialStore is backed by EncryptedSharedPreferences, which can throw on a
        // Keystore failure — a "remember me" convenience must never take down the login
        // screen, so every read is guarded and the worst case is a blank form.
        rememberedUsername = runCatching { credentialStore.rememberedUsername() }
            .onFailure { Log.e(TAG, "Failed to read remembered username: ${it::class.simpleName}") }
            .getOrNull()
        val enrolled = runCatching { credentialStore.isBiometricEnrolled() }.getOrDefault(false)
        // Both fields always start empty — after a session expiry, a logout or a fresh launch.
        // The password is never filled in just because the screen opened.
        _uiState.update {
            it.copy(
                username = "",
                password = "",
                rememberMe = rememberedUsername != null,
                biometricEnrolled = enrolled,
                // e.g. "Account Setup Complete" right after the first-time setup signed the user out.
                notice = pendingLoginNotice.take(),
            )
        }
    }

    fun onUsernameChange(value: String) {
        _uiState.update { it.copy(username = value, errorMessage = null) }
        val remembered = rememberedUsername
        val matches = remembered != null && value.trim().equals(remembered, ignoreCase = true)
        val current = _uiState.value
        if (matches && current.password.isEmpty()) {
            // Only the remembered account's own username unlocks the saved password.
            val saved = runCatching { credentialStore.rememberedPasswordFor(value) }.getOrNull()
            if (saved != null) {
                passwordAutoFilled = true
                _uiState.update { it.copy(password = saved) }
            }
        } else if (!matches && passwordAutoFilled) {
            passwordAutoFilled = false
            _uiState.update { it.copy(password = "") }
        }
    }

    fun onPasswordChange(value: String) {
        passwordAutoFilled = false
        _uiState.update { it.copy(password = value, errorMessage = null) }
    }

    fun onRememberMeChange(value: Boolean) = _uiState.update { it.copy(rememberMe = value) }

    fun signIn() {
        val state = _uiState.value
        // The ViewModel is the one place every entry point funnels through, so "never start a
        // second sign-in while one is already running" belongs here, not in each caller.
        if (state.isLoading) return
        val username = state.username.trim()
        val password = state.password
        val validationError = when {
            username.isBlank() && password.isBlank() -> context.getString(R.string.login_error_missing_fields)
            username.isBlank() -> context.getString(R.string.login_error_missing_username)
            password.isBlank() -> context.getString(R.string.login_error_missing_password)
            else -> null
        }
        Log.d(TAG, "Username validation result: ${if (username.isBlank()) "MISSING" else "OK"}")
        Log.d(TAG, "Password validation result: ${if (password.isBlank()) "MISSING" else "OK"}")
        if (validationError != null) {
            _uiState.update { it.copy(errorMessage = validationError) }
            return
        }
        performSignIn(username, password, viaBiometric = false)
    }

    /** "Login with Biometrics" tapped. Returns true only when the biometric prompt should be shown:
     * biometric login must have been enrolled for GoPreach first, and the device must still have a
     * biometric. Otherwise nothing is authenticated and the user is told why. */
    fun onBiometricButtonClick(deviceHasBiometrics: Boolean): Boolean {
        if (_uiState.value.isLoading) return false
        val enrolled = runCatching { credentialStore.isBiometricEnrolled() }.getOrDefault(false)
        _uiState.update { it.copy(biometricEnrolled = enrolled) }
        if (!enrolled) {
            _uiState.update { it.copy(showBiometricNotSetUp = true) }
            return false
        }
        if (!deviceHasBiometrics) {
            _uiState.update { it.copy(errorMessage = "No fingerprint or face is set up on this device.") }
            return false
        }
        return true
    }

    fun dismissBiometricNotSetUp() = _uiState.update { it.copy(showBiometricNotSetUp = false) }

    /** "Login with PIN/Pattern" tapped: sign-in is only offered once that method was set up on this device. */
    fun onQuickLoginClick(method: QuickLoginMethod) {
        if (_uiState.value.isLoading) return
        val enrolled = runCatching { quickLoginStore.isEnrolled(method) }.getOrDefault(false)
        if (!enrolled) {
            val message = when (method) {
                QuickLoginMethod.PIN -> "Please log in using your username and password first, then create a PIN in Account Settings."
                QuickLoginMethod.PATTERN -> "Please log in using your username and password first, then create a Pattern in Account Settings."
            }
            _uiState.update { it.copy(notice = "${method.label} Login Not Set Up" to message) }
            return
        }
        _uiState.update {
            it.copy(
                quickLoginMethod = method,
                quickLoginError = null,
                quickLoginLockedMs = runCatching { quickLoginStore.lockRemainingMs(method) }.getOrDefault(0L),
            )
        }
    }

    /** Passkeys need a WebAuthn server GoPreach doesn't have yet, so none can be registered. */
    fun onPasskeyClick() = _uiState.update {
        it.copy(notice = "Passkey Not Set Up" to "Passkey login isn't available yet. Please log in with your username and password, or use a PIN, Pattern or Biometrics.")
    }

    /** Whose account a PIN/Pattern would sign in — shown in the dialog so it's clear who is signing in. */
    fun quickLoginUsername(method: QuickLoginMethod): String? = runCatching { quickLoginStore.username(method) }.getOrNull()

    fun dismissNotice() = _uiState.update { it.copy(notice = null) }
    fun dismissQuickLogin() = _uiState.update { it.copy(quickLoginMethod = null, quickLoginError = null, quickLoginLockedMs = 0L) }

    /** A PIN/Pattern was entered. A correct one runs the normal server-verified sign-in with the account's own
     * credentials; a wrong one counts toward a temporary lock. */
    fun onQuickLoginSecret(method: QuickLoginMethod, secret: String) {
        if (_uiState.value.isLoading) return
        when (val check = runCatching { quickLoginStore.verify(method, secret) }.getOrDefault(QuickLoginCheck.NotSetUp)) {
            is QuickLoginCheck.Success -> {
                dismissQuickLogin()
                performSignIn(check.username, check.password, viaBiometric = true)
            }
            is QuickLoginCheck.Wrong -> _uiState.update {
                it.copy(quickLoginError = "Wrong ${method.label}. ${check.attemptsBeforeLock} ${if (check.attemptsBeforeLock == 1) "try" else "tries"} left before a short lock.")
            }
            is QuickLoginCheck.Locked -> _uiState.update { it.copy(quickLoginError = null, quickLoginLockedMs = check.remainingMs) }
            QuickLoginCheck.Disabled -> _uiState.update {
                it.copy(
                    quickLoginMethod = null,
                    quickLoginError = null,
                    quickLoginLockedMs = 0L,
                    notice = "${method.label} Login Turned Off" to "Too many wrong tries. Please log in with your username and password, then set up your ${method.label} again.",
                )
            }
            QuickLoginCheck.NotSetUp -> _uiState.update { it.copy(quickLoginMethod = null, notice = "${method.label} Login Not Set Up" to "Please log in using your username and password first.") }
        }
    }

    /** Invoked after [androidx.biometric.BiometricPrompt] reports success — only then is the
     * enrolled credential read, and the normal sign-in runs with it. */
    fun onBiometricAuthSucceeded() {
        if (_uiState.value.isLoading) return
        val saved = runCatching { credentialStore.readBiometric() }.getOrNull() ?: run {
            _uiState.update { it.copy(errorMessage = context.getString(R.string.login_error_no_saved_credential)) }
            return
        }
        performSignIn(saved.first, saved.second, viaBiometric = true)
    }

    fun onBiometricError(message: String) = _uiState.update { it.copy(errorMessage = message) }

    /** "Offline Login" spec §1 — no network means Firebase's own sign-in call
     * can't be made at all (it always requires a round trip; there's no offline
     * path in the SDK), so this checks connectivity first and, when offline,
     * verifies against the locally-hashed credential saved by this device's
     * last successful *online* sign-in instead — see
     * [AuthRepository.offlineSignIn]. "Remember me"'s saved pair is unrelated
     * (that's for the optional biometric shortcut) and isn't touched here. */
    private fun performSignIn(username: String, password: String, viaBiometric: Boolean) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            // Bug fix ("Never allow: Infinite Loading, Frozen Login Button") —
            // a try/finally around the whole request, not just a when-branch
            // on the two AuthResult cases: [AuthRepository.signIn]/
            // [offlineSignIn] already guarantee they never throw, but
            // [connectivityObserver.isOnline] itself is a plain
            // ConnectivityManager call outside that guarantee, and this
            // finally block is what makes "isLoading always ends up false
            // again" true regardless of what actually goes wrong, not just
            // for the two outcomes this function already anticipated.
            try {
                val online = connectivityObserver.isOnline()
                Log.d(TAG, "Database/API connection result: ${if (online) "ONLINE" else "OFFLINE"}")
                val result = if (online) authRepository.signIn(username, password) else authRepository.offlineSignIn(username, password)
                when (result) {
                    is AuthResult.Success -> {
                        Log.d(TAG, "Authentication result: SUCCESS")
                        // Bug fix: a real, successful sign-in must never be
                        // reported as a failure just because the "Remember
                        // me" convenience write afterward hit a Keystore
                        // error — this is a non-essential side effect of an
                        // otherwise-complete login, so it's caught and
                        // logged, not allowed to fall through to this
                        // function's own catch block below and overwrite a
                        // real success with an error message.
                        runCatching {
                            // A biometric sign-in leaves the Remember Login choice alone.
                            if (viaBiometric) return@runCatching
                            if (_uiState.value.rememberMe) {
                                credentialStore.save(username, password)
                                rememberedUsername = username
                            } else {
                                credentialStore.clearRemembered()
                                rememberedUsername = null
                            }
                        }.onFailure { Log.e(TAG, "Failed to update saved credential: ${it::class.simpleName}") }
                        // "Automatically start synchronization when... the user
                        // logs in" — this device may have pending writes queued
                        // from a previous session (or simply hasn't had a
                        // connectivity transition since launch, so
                        // SyncScheduler's own automatic triggers never fired
                        // yet); a fresh login is a natural moment to flush
                        // rather than waiting on the 15-minute periodic floor.
                        syncScheduler.triggerSyncIfOnline()
                        // After a password sign-in, offer (never silently do) biometric login — only if the
                        // device has a biometric, it isn't already enrolled for this account, and the user
                        // hasn't said "not now". The offer is shown from the nav graph, since the login
                        // screen itself goes away as soon as the session is signed in.
                        val offer = !viaBiometric && !result.requiresPasswordChange && runCatching {
                            deviceHasBiometrics(context) &&
                                !credentialStore.biometricOfferDeclined() &&
                                !(credentialStore.isBiometricEnrolled() && credentialStore.biometricUsername().equals(username, ignoreCase = true))
                        }.getOrDefault(false)
                        if (offer) biometricEnrollmentOffer.offer(username, password)
                        _uiState.update {
                            it.copy(isLoading = false, signedIn = true, requiresPasswordChange = result.requiresPasswordChange)
                        }
                    }
                    is AuthResult.Error -> {
                        Log.d(TAG, "Authentication result: FAILED")
                        if (viaBiometric && result.message.contains("Invalid username or password", ignoreCase = true)) {
                            // The saved password no longer works (it was changed elsewhere): drop every saved
                            // unlock so nothing keeps trying a stale password, and ask for a fresh set-up.
                            runCatching { credentialStore.clearBiometric(); quickLoginStore.disableAll() }
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    biometricEnrolled = false,
                                    notice = "Login Methods Turned Off" to "Your saved sign-in is out of date, so PIN, Pattern and Biometrics were turned off. Please log in with your username and password and set them up again.",
                                )
                            }
                            return@launch
                        }
                        _uiState.update { it.copy(isLoading = false, errorMessage = result.message) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected login error: ${e::class.simpleName}")
                _uiState.update { it.copy(isLoading = false, errorMessage = "Something went wrong. Please try again.") }
            }
        }
    }

    fun consumeNavigationEvent() {
        _uiState.update { it.copy(signedIn = false, requiresPasswordChange = null) }
    }
}
