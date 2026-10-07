package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.platform.KeyValueStores
import com.emfitsolutions.gopreach.platform.edit
import com.emfitsolutions.gopreach.platform.pbkdf2
import com.emfitsolutions.gopreach.platform.secureRandomBytes
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi


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
@OptIn(ExperimentalEncodingApi::class)
class OfflineAuthStore(stores: KeyValueStores) {
    private val prefs by lazy { stores.openSecure(PREFS_NAME) }

    /** Called after every successful *online* sign-in (see [AuthRepository.signIn]). */
    fun saveVerifier(username: String, password: String, personId: String) {
        val salt = secureRandomBytes(16)
        val hash = pbkdf2(password, salt)
        prefs.edit {
            putString(KEY_USERNAME, username)
            putString(KEY_SALT, Base64.encode(salt))
            putString(KEY_HASH, Base64.encode(hash))
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
        val salt = Base64.decode(saltBase64)
        val expectedHash = Base64.decode(hashBase64)
        val actualHash = pbkdf2(password, salt)
        return if (actualHash.contentEquals(expectedHash)) personId else null
    }

    /** Deliberately *not* called by [AuthRepository.signOut] — a normal logout
     * must not disable this device's ability to sign back in offline later.
     * Only ever cleared implicitly by [saveVerifier] overwriting it with a
     * different account's verifier (see this class's own doc comment on why
     * that single-slot replacement is an accepted limit, not a bug). */
    fun clear() {
        prefs.edit {
            remove(KEY_USERNAME); remove(KEY_SALT); remove(KEY_HASH); remove(KEY_PERSON_ID)
        }
    }

    private fun pbkdf2(password: String, salt: ByteArray): ByteArray =
        pbkdf2(password, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS, "PBKDF2WithHmacSHA256")
}
