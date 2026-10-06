package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.platform.KeyValueStores
import com.emfitsolutions.gopreach.platform.nowMillis


/**
 * Secure on-device storage for the two opt-in sign-in conveniences, each kept
 * separate from the other and from the active session:
 *
 *  - **Remember Login** — the username/password pair behind the "Remember me" checkbox.
 *    The login screen never shows it on open; the password is only filled in once the
 *    username typed matches [rememberedUsername].
 *  - **Biometric login** — a pair saved only when the user explicitly enrolled biometric
 *    login for this device (after signing in with a password, or from Settings). A
 *    biometric prompt gates reading it; [isBiometricEnrolled] is what distinguishes
 *    "this device has biometrics" from "the user set up biometric login for GoPreach".
 *
 * Backed by [EncryptedSharedPreferences] (AES-256, key material in the Android Keystore),
 * never plain SharedPreferences. Nothing here is ever logged.
 */
class CredentialStore(private val stores: KeyValueStores) {

    private val prefs by lazy { stores.openSecure(PREFS_NAME) }

    // --- Remember Login ---------------------------------------------------------

    fun save(username: String, password: String) {
        prefs.edit().putString(KEY_USERNAME, username).putString(KEY_PASSWORD, password).apply()
    }

    /** Forgets only the remembered login — a biometric enrollment is untouched. */
    fun clearRemembered() {
        prefs.edit().remove(KEY_USERNAME).remove(KEY_PASSWORD).apply()
    }

    /** The remembered username alone (not a secret) — what the login screen matches typing against. */
    fun rememberedUsername(): String? = prefs.getString(KEY_USERNAME, null)

    /** The remembered password for [username], or null if that isn't the remembered account. */
    fun rememberedPasswordFor(username: String): String? {
        val saved = prefs.getString(KEY_USERNAME, null) ?: return null
        if (!saved.equals(username.trim(), ignoreCase = true)) return null
        return prefs.getString(KEY_PASSWORD, null)
    }

    // --- Biometric login --------------------------------------------------------

    fun saveBiometric(username: String, password: String) {
        prefs.edit().putString(KEY_BIO_USERNAME, username).putString(KEY_BIO_PASSWORD, password).apply()
    }

    fun clearBiometric() {
        prefs.edit().remove(KEY_BIO_USERNAME).remove(KEY_BIO_PASSWORD).apply()
    }

    /** Set when the user taps "Not now" on the post-login offer, so it isn't repeated every sign-in. */
    fun biometricOfferDeclined(): Boolean = prefs.getBoolean(KEY_BIO_DECLINED, false)

    fun setBiometricOfferDeclined(declined: Boolean) {
        prefs.edit().putBoolean(KEY_BIO_DECLINED, declined).apply()
    }

    fun isBiometricEnrolled(): Boolean =
        prefs.getString(KEY_BIO_USERNAME, null) != null && prefs.getString(KEY_BIO_PASSWORD, null) != null

    fun biometricUsername(): String? = prefs.getString(KEY_BIO_USERNAME, null)

    /** Only call after the biometric prompt has succeeded. */
    fun readBiometric(): Pair<String, String>? {
        val username = prefs.getString(KEY_BIO_USERNAME, null)
        val password = prefs.getString(KEY_BIO_PASSWORD, null)
        return if (username != null && password != null) username to password else null
    }

    companion object {
        private const val PREFS_NAME = "gopreach_credentials"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_BIO_USERNAME = "biometricUsername"
        private const val KEY_BIO_PASSWORD = "biometricPassword"
        private const val KEY_BIO_DECLINED = "biometricOfferDeclined"
    }
}
