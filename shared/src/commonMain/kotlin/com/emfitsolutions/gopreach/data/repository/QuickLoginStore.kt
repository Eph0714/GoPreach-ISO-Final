package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.platform.KeyValueStores
import com.emfitsolutions.gopreach.platform.nowMillis

import com.emfitsolutions.gopreach.domain.QuickLoginPolicy
import com.emfitsolutions.gopreach.domain.SecretHasher

enum class QuickLoginMethod(val label: String, val key: String) {
    PIN("PIN", "pin"),
    PATTERN("Pattern", "pattern"),
}

/** Result of checking a PIN/pattern the user entered. */
sealed interface QuickLoginCheck {
    /** Correct — [username]/[password] are what the normal, server-verified sign-in is run with. */
    data class Success(val username: String, val password: String) : QuickLoginCheck
    /** Wrong; [attemptsBeforeLock] more wrong tries are allowed before the next temporary lock. */
    data class Wrong(val attemptsBeforeLock: Int) : QuickLoginCheck
    /** Too many wrong tries; try again after [remainingMs]. */
    data class Locked(val remainingMs: Long) : QuickLoginCheck
    /** Wrong too many times in a row — the method was switched off; sign in with the password and set it up again. */
    data object Disabled : QuickLoginCheck
    data object NotSetUp : QuickLoginCheck
}

/**
 * On-device PIN and Pattern login. Neither is a way around account security: the PIN/pattern only
 * unlocks the account's own password, which is then used for the normal Firebase sign-in, so the
 * server still verifies the account every time.
 *
 * What is stored, in [EncryptedSharedPreferences] (AES-256, key in the Android Keystore), never
 * included in any backup and never sent anywhere:
 *  - a salted PBKDF2 hash of the PIN/pattern (never the PIN/pattern itself), used to check an entry;
 *  - the account's username and password, so a correct entry can sign in;
 *  - the failed-attempt counter and lock time, so closing the app doesn't reset the throttle.
 * It is bound to this device, so it does not move to a new phone: set it up again there.
 */
class QuickLoginStore(private val stores: KeyValueStores) {

    private val prefs by lazy { stores.openSecure(PREFS_NAME) }

    fun isEnrolled(method: QuickLoginMethod): Boolean =
        prefs.getString(k(method, HASH), null) != null && prefs.getString(k(method, PASSWORD), null) != null

    fun username(method: QuickLoginMethod): String? = prefs.getString(k(method, USERNAME), null)

    /** Saves [secret] (as a salted hash) and the account's sign-in credentials for [method]. */
    fun enroll(method: QuickLoginMethod, secret: String, username: String, password: String) {
        prefs.edit()
            .putString(k(method, HASH), SecretHasher.hash(secret))
            .putString(k(method, USERNAME), username)
            .putString(k(method, PASSWORD), password)
            .putInt(k(method, FAILURES), 0)
            .putLong(k(method, LOCKED_UNTIL), 0L)
            .apply()
    }

    fun disable(method: QuickLoginMethod) {
        prefs.edit()
            .remove(k(method, HASH)).remove(k(method, USERNAME)).remove(k(method, PASSWORD))
            .remove(k(method, FAILURES)).remove(k(method, LOCKED_UNTIL))
            .apply()
    }

    fun disableAll() = QuickLoginMethod.entries.forEach(::disable)

    /** How long [method] is still locked, in ms (0 when it isn't). */
    fun lockRemainingMs(method: QuickLoginMethod, now: Long = nowMillis()): Long =
        (prefs.getLong(k(method, LOCKED_UNTIL), 0L) - now).coerceAtLeast(0L)

    fun verify(method: QuickLoginMethod, secret: String, now: Long = nowMillis()): QuickLoginCheck {
        val hash = prefs.getString(k(method, HASH), null)
        val username = prefs.getString(k(method, USERNAME), null)
        val password = prefs.getString(k(method, PASSWORD), null)
        if (hash == null || username == null || password == null) return QuickLoginCheck.NotSetUp

        val remaining = lockRemainingMs(method, now)
        if (remaining > 0) return QuickLoginCheck.Locked(remaining)

        if (SecretHasher.matches(secret, hash)) {
            prefs.edit().putInt(k(method, FAILURES), 0).putLong(k(method, LOCKED_UNTIL), 0L).apply()
            return QuickLoginCheck.Success(username, password)
        }

        val failures = prefs.getInt(k(method, FAILURES), 0) + 1
        if (failures >= QuickLoginPolicy.FAILURES_BEFORE_DISABLE) {
            disable(method)
            return QuickLoginCheck.Disabled
        }
        val lockMs = QuickLoginPolicy.lockDurationMs(failures)
        prefs.edit()
            .putInt(k(method, FAILURES), failures)
            .putLong(k(method, LOCKED_UNTIL), if (lockMs > 0) now + lockMs else 0L)
            .apply()
        return if (lockMs > 0) QuickLoginCheck.Locked(lockMs)
        else QuickLoginCheck.Wrong(QuickLoginPolicy.FAILURES_BEFORE_LOCK - (failures % QuickLoginPolicy.FAILURES_BEFORE_LOCK))
    }

    private fun k(method: QuickLoginMethod, field: String) = "${method.key}_$field"

    private companion object {
        const val PREFS_NAME = "gopreach_quicklogin"
        const val HASH = "hash"
        const val USERNAME = "username"
        const val PASSWORD = "password"
        const val FAILURES = "failures"
        const val LOCKED_UNTIL = "lockedUntil"
    }
}
