package com.emfitsolutions.gopreach.ui.screens.login

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.repository.AuthRepository
import com.emfitsolutions.gopreach.data.repository.CredentialStore
import com.emfitsolutions.gopreach.domain.UserSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK

/** True when this device has a fingerprint/face enrolled in the system. It says nothing about
 * whether the user set up biometric login for GoPreach — see [CredentialStore.isBiometricEnrolled]. */
fun deviceHasBiometrics(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

/** What this device can really check a person with: a fingerprint or face, or else only its screen lock (PIN, pattern or password), or nothing. Asked of Android, never assumed. */
enum class DeviceAuthKind { BIOMETRIC, SCREEN_LOCK, NONE }

fun deviceAuthKind(context: Context): DeviceAuthKind = when {
    deviceHasBiometrics(context) -> DeviceAuthKind.BIOMETRIC
    BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS -> DeviceAuthKind.SCREEN_LOCK
    else -> DeviceAuthKind.NONE
}

/** True when the device can unlock GoPreach with a fingerprint, a face or its screen lock. */
fun deviceCanAuthenticate(context: Context): Boolean = deviceAuthKind(context) != DeviceAuthKind.NONE

/** The label of the sign-in button: only what the device actually offers. */
fun deviceAuthLoginLabel(kind: DeviceAuthKind): String = when (kind) {
    DeviceAuthKind.BIOMETRIC -> "Login with Biometrics"
    DeviceAuthKind.SCREEN_LOCK -> "Login with Device Screen Lock"
    DeviceAuthKind.NONE -> ""
}

/** Fires the system biometric prompt on top of [activity] and reports back through plain callbacks. */
fun launchBiometricPrompt(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
    negativeButtonText: String,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // The user backing out on purpose isn't an error worth surfacing.
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    onError(errString.toString())
                }
            }
        },
    )
    // A fingerprint or face where the device has one (with "Use password" as the way out); otherwise the system's own screen-lock prompt (PIN, pattern or password).
    // Nothing is read or stored by GoPreach either way: Android only answers whether the person passed.
    val promptInfo = BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle).apply {
        if (deviceAuthKind(activity) == DeviceAuthKind.SCREEN_LOCK) setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        else setNegativeButtonText(negativeButtonText).setAllowedAuthenticators(AUTHENTICATORS)
    }.build()
    prompt.authenticate(promptInfo)
}

/**
 * The "Enable Biometric Login?" offer made right after a successful password sign-in. The login
 * screen is gone by then, so the offer lives here (shown by [BiometricEnrollmentOfferHost] at the
 * nav graph root). The credentials are held only until the user answers, or the session ends.
 */
class BiometricEnrollmentOffer(private val credentialStore: CredentialStore) {
    private val _pending = MutableStateFlow<Pair<String, String>?>(null)
    val pending: StateFlow<Pair<String, String>?> = _pending.asStateFlow()

    fun offer(username: String, password: String) { _pending.value = username to password }

    /** The user said Enable and passed the biometric prompt. */
    fun confirm() {
        _pending.value?.let { (u, p) ->
            runCatching {
                credentialStore.saveBiometric(u, p)
                credentialStore.setBiometricOfferDeclined(false)
            }
        }
        _pending.value = null
    }

    /** "Not now" — don't ask again on every sign-in. */
    fun decline() {
        runCatching { credentialStore.setBiometricOfferDeclined(true) }
        _pending.value = null
    }

    /** Session ended / signed out with the offer unanswered — forget the password. */
    fun clear() { _pending.value = null }
}

class BiometricOfferViewModel(val offer: BiometricEnrollmentOffer) : ViewModel()

/**
 * Biometric enrollment from Settings (Account / Security): the user explicitly turns it on,
 * proves their password, then proves the device biometric, and only then is anything saved.
 */
class BiometricSetupViewModel(
    private val authRepository: AuthRepository,
    private val credentialStore: CredentialStore,
    private val userSession: UserSession,
    private val context: Context,
) : ViewModel() {

    private val _enrolled = MutableStateFlow(runCatching { credentialStore.isBiometricEnrolled() }.getOrDefault(false))
    val enrolled: StateFlow<Boolean> = _enrolled.asStateFlow()

    val deviceSupportsBiometrics: Boolean get() = deviceCanAuthenticate(context)

    /** The username that would be enrolled — the signed-in account's. */
    fun accountUsername(): String? = userSession.state.value.person?.username?.takeIf { it.isNotBlank() }

    /** Step 1: the password must be this account's current one. Returns an error message, or null. */
    suspend fun verifyPassword(password: String): String? =
        authRepository.verifyCurrentPassword(password).exceptionOrNull()?.message

    /** Step 2 (after the biometric prompt succeeded): associate biometric login with this account on this device. */
    fun completeEnrollment(password: String): Boolean {
        val username = accountUsername() ?: return false
        return runCatching { credentialStore.saveBiometric(username, password) }.isSuccess.also { if (it) _enrolled.value = true }
    }

    fun disable() {
        runCatching { credentialStore.clearBiometric() }
        _enrolled.value = false
    }
}
