package com.emfitsolutions.gopreach.data.repository

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME = "gopreach_offline_auth"
private const val KEY_USERNAME = "username"
private const val KEY_SALT = "salt"
private const val KEY_HASH = "hash"
private const val KEY_PERSON_ID = "personId"
private const val PBKDF2_ITERATIONS = 120_000
private const val KEY_LENGTH_BITS = 256

/**
 * "GoPreach App: Offline Login" spec §1-§2 — every successful *online* sign-in
 * saves a securely-hashed verifier for that username/password pair (PBKDF2 with
 * a random per-record salt, at rest in [EncryptedSharedPreferences] backed by
 * the Android Keystore) so [AuthRepository.offlineSignIn] can later prove the
 * same password is being entered again without ever storing it in plaintext or
 * needing the server reachable. This is intentionally separate from
 * [CredentialStore] — that one exists for the *opt-in* "Remember me" biometric
 * shortcut and stores the raw password (needed to replay a real online sign-in
 * for that flow); this one exists for every user who has ever signed in
 * successfully on this device, opt-in or not, and never stores the password
 * itself, only a one-way hash of it.
 *
 * Single-slot, like [CredentialStore]: only the most recently successfully
 * authenticated username's verifier is kept, matching this app's "one signed-in
 * person at a time" usage pattern. A second person signing in on the same
 * device online replaces it; that's an accepted scope limit for a shared
 * device, not a security hole (each person can still only unlock their own
 * cached data, never someone else's, while online).
 */
@Singleton
class OfflineAuthStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Called after every successful *online* sign-in (see [AuthRepository.signIn]). */
    fun saveVerifier(username: String, password: String, personId: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(password, salt)
        prefs.edit {
            putString(KEY_USERNAME, username)
            putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            putString(KEY_PERSON_ID, personId)
        }
    }

    /** True when this device has *any* saved verifier for [username] at all —
     * lets [AuthRepository.offlineSignIn] tell "you've never successfully
     * signed in on this exact device before" (nothing to check the password
     * against) apart from "wrong password for a device that does have one." */
    fun hasSavedVerifierFor(username: String): Boolean =
        prefs.getString(KEY_USERNAME, null)?.equals(username, ignoreCase = false) == true

    /** Returns the matching [personId] if [username]/[password] match the saved
     * verifier, or null if there's no saved verifier for this username, or the
     * password doesn't match it. Never touches the network. */
    fun verify(username: String, password: String): String? {
        val storedUsername = prefs.getString(KEY_USERNAME, null) ?: return null
        if (!storedUsername.equals(username, ignoreCase = false)) return null
        val saltBase64 = prefs.getString(KEY_SALT, null) ?: return null
        val hashBase64 = prefs.getString(KEY_HASH, null) ?: return null
        val personId = prefs.getString(KEY_PERSON_ID, null) ?: return null
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val expectedHash = Base64.decode(hashBase64, Base64.NO_WRAP)
        val actualHash = pbkdf2(password, salt)
        return if (actualHash.contentEquals(expectedHash)) personId else null
    }

    /** Deliberately *not* called by [AuthRepository.signOut] — a normal logout
     * must not disable this device's ability to sign back in offline later.
     * Only ever cleared implicitly by [saveVerifier] overwriting it with a
     * different account's verifier (see this class's own doc comment on why
     * that single-slot replacement is an accepted limit, not a bug). */
    fun clear() {
        prefs.edit { clear() }
    }

    private fun pbkdf2(password: String, salt: ByteArray): ByteArray {
        val spec: KeySpec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}
