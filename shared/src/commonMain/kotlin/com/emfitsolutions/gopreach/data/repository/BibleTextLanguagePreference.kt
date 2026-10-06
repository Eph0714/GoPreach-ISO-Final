package com.emfitsolutions.gopreach.data.repository

import android.content.Context
import androidx.core.content.edit

private const val PREFS_NAME = "gopreach_bible_text_prefs"
private const val KEY_LANGUAGE_ID = "last_bible_language_id"

/**
 * "If the publisher selects a Bible language, make it permanent except the
 * publisher will change it" — the last Bible Language picked in Add/Edit
 * Bible Text (My Bible Text Record) becomes the default for every new
 * record after that, per-device (same "own choice on this device only,
 * never synced" pattern as [NotificationSoundRepository]), until the
 * publisher explicitly picks a different one. A plain static object, not a
 * Hilt repository — it's read once when a dialog composes and written once
 * when the picker changes, so there's no need to thread an injected
 * instance through that screen.
 */
object BibleTextLanguagePreference {
    fun get(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LANGUAGE_ID, null)

    fun set(context: Context, languageId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putString(KEY_LANGUAGE_ID, languageId) }
    }
}
