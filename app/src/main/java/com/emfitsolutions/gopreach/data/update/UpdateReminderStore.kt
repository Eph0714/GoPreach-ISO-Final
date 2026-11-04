package com.emfitsolutions.gopreach.data.update

import android.content.Context
import androidx.core.content.edit
import java.util.concurrent.TimeUnit

private const val PREFS_NAME = "gopreach_update_reminder"
private const val KEY_SNOOZED_VERSION = "snoozed_version"
private const val KEY_SNOOZED_UNTIL = "snoozed_until"
private const val KEY_ACKNOWLEDGED_VERSION = "acknowledged_version"
private const val KEY_DEFERRED_VERSION = "deferred_until_login_version"

/** "Remind Me Later... show the update notification again later at an
 * appropriate time, such as: next login, after a configurable number of
 * hours, the next day" — one snooze at a time, keyed to the specific
 * version it was snoozed for (not "updates in general"): a snooze for
 * 1.61.0 never suppresses the dialog once 1.62.0 ships, since that's a
 * genuinely different, newer update the Publisher hasn't seen a prompt for
 * yet. Local/per-device only, same pattern as [InstalledUpdateInfoStore].
 * Only [com.emfitsolutions.gopreach.ui.components.update.UpdateViewModel]'s
 * silent/automatic checks ever consult this — the Settings screen's
 * explicit "Check for Updates" always shows an available update regardless
 * of any snooze in effect. */
class UpdateReminderStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Default "remind me later" window — spec's own "the next day" example. */
    private val defaultSnoozeMillis = TimeUnit.HOURS.toMillis(24)

    fun snooze(version: String, snoozeMillis: Long = defaultSnoozeMillis) {
        prefs.edit {
            putString(KEY_SNOOZED_VERSION, version)
            putLong(KEY_SNOOZED_UNTIL, System.currentTimeMillis() + snoozeMillis)
        }
    }

    /** True while [version] is still within its own snooze window — false
     * for every other version (including a newer one that's shipped since),
     * and false once the window has elapsed (the next automatic check after
     * that naturally shows the dialog again — "next login" or whenever the
     * next silent check happens to land, whichever comes first). */
    fun isSnoozed(version: String): Boolean {
        val snoozedVersion = prefs.getString(KEY_SNOOZED_VERSION, null) ?: return false
        if (snoozedVersion != version) return false
        return System.currentTimeMillis() < prefs.getLong(KEY_SNOOZED_UNTIL, 0L)
    }

    /** The person tapped UPDATE NOW for [version]: that exact release is not offered again by an automatic check (coming back from the installer, reopening the app, signing in again), whether or not the install finished. Only a different, newer version asks again. */
    fun acknowledge(version: String) {
        prefs.edit { putString(KEY_ACKNOWLEDGED_VERSION, version) }
    }

    fun isAcknowledged(version: String): Boolean = prefs.getString(KEY_ACKNOWLEDGED_VERSION, null) == version

    /**
     * "Update later": this release is not offered again by any automatic check (foreground, back online, a new launch) until the person SIGNS IN again. Signing in (or the
     * sign-in screen showing) calls [clearForLogin], so the reminder comes back at the next login. A newer release is a different version and is offered at once.
     */
    fun deferUntilLogin(version: String) {
        prefs.edit { putString(KEY_DEFERRED_VERSION, version) }
    }

    fun isDeferred(version: String): Boolean = prefs.getString(KEY_DEFERRED_VERSION, null) == version

    /** A sign-in (or the sign-in screen) is the reminder moment: "later" and an earlier UPDATE NOW answer no longer hold back the notice. */
    fun clearForLogin() {
        prefs.edit {
            remove(KEY_DEFERRED_VERSION)
            remove(KEY_ACKNOWLEDGED_VERSION)
        }
    }

    /** Clears any snooze in effect — called once an update actually
     * installs, so a fresh future version starts with no leftover snooze
     * state pointing at an old version number. */
    fun clear() {
        prefs.edit {
            remove(KEY_SNOOZED_VERSION)
            remove(KEY_SNOOZED_UNTIL)
        }
    }
}
