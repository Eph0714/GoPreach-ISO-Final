package com.emfitsolutions.gopreach.data.repository

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME = "gopreach_announcement_seen"

/**
 * "In every Publisher App, they can see a notification balloon... regarding
 * the announcement" — per-device, per-signed-in-Person "last seen" timestamp
 * (keyed by personId, since more than one account can sign in on the same
 * device over time), used only to compute the badge's unseen count. This is
 * deliberately local-only, same reasoning as [ThemePreferenceRepository]: it
 * never needs to sync across devices or be visible to anyone else, and
 * keeping it out of Firestore avoids a write on every single announcement
 * view.
 */
@Singleton
class AnnouncementSeenStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Live, so a badge deriving from this recomputes the instant
     * [markSeenNow] runs, with no separate re-read/refresh trigger needed —
     * callers read one personId's value via `.map { it[personId] ?: 0L }`. */
    private val _lastSeenAtByPerson = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastSeenAtByPerson: StateFlow<Map<String, Long>> = _lastSeenAtByPerson

    fun lastSeenAt(personId: String): Long =
        _lastSeenAtByPerson.value[personId] ?: prefs.getLong(personId, 0L)

    fun markSeenNow(personId: String) {
        val now = System.currentTimeMillis()
        prefs.edit { putLong(personId, now) }
        _lastSeenAtByPerson.value = _lastSeenAtByPerson.value + (personId to now)
    }
}
