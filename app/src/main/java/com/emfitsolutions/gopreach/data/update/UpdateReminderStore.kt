package com.emfitsolutions.gopreach.data.update

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME = "gopreach_update_reminder"
private const val KEY_SNOOZED_VERSION = "snoozed_version"
private const val KEY_SNOOZED_UNTIL = "snoozed_until"

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
@Singleton
class UpdateReminderStore @Inject constructor(
    @ApplicationContext context: Context,
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

    /** Clears any snooze in effect — called once an update actually
     * installs, so a fresh future version starts with no leftover snooze
     * state pointing at an old version number. */
    fun clear() {
        prefs.edit { clear() }
    }
}
