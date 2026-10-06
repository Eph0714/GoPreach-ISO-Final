package com.emfitsolutions.gopreach.data.repository

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME = "gopreach_notification_dismissed"

/**
 * Per-device, per-signed-in-Person set of dismissed notification ids — spec:
 * "Delete Notification"/"Clear Old Notifications". A dismissed
 * [com.emfitsolutions.gopreach.ui.screens.notifications.NotificationItem]
 * disappears from the bell's dropdown on this device only; it never touches
 * the underlying record (the ForwardRequest/Announcement/etc. itself, or its
 * status) — same "local-only, this is just what the balloon shows" reasoning
 * as [NotificationSeenStore], just tracked per-item instead of as a per-
 * category watermark. Items are addressed by [NotificationItem.id] (the
 * underlying record's own id), which is stable across app restarts and
 * recompositions.
 *
 * Deliberately local rather than a Firestore `notifications/{id}.isRead`
 * flag — this app's notification balloon has never stored its own
 * notification documents (it's a live projection over the source
 * collections, see [com.emfitsolutions.gopreach.ui.screens.notifications
 * .NotificationCenterViewModel]'s doc comment), and duplicating that as a
 * separate synced collection just to carry a dismissed flag isn't worth
 * introducing a second Firestore write path here.
 */
@Singleton
class NotificationDismissedStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _dismissedByPerson = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val dismissedByPerson: StateFlow<Map<String, Set<String>>> = _dismissedByPerson

    private fun key(personId: String) = "dismissed:$personId"

    fun dismissedFor(personId: String): Set<String> =
        _dismissedByPerson.value[personId] ?: prefs.getStringSet(key(personId), emptySet())?.toSet().orEmpty()

    /** Dismisses one notification (spec: "Delete Notification"). */
    fun dismiss(personId: String, notificationId: String) {
        val updated = dismissedFor(personId) + notificationId
        persist(personId, updated)
    }

    /** Dismisses every id in [notificationIds] at once (spec: "Clear Old
     * Notifications" / a "Clear All" action) — everything currently shown
     * disappears from the balloon in one tap, without touching any
     * underlying record. */
    fun dismissAll(personId: String, notificationIds: Collection<String>) {
        val updated = dismissedFor(personId) + notificationIds
        persist(personId, updated)
    }

    private fun persist(personId: String, updated: Set<String>) {
        prefs.edit { putStringSet(key(personId), updated) }
        _dismissedByPerson.value = _dismissedByPerson.value + (personId to updated)
    }
}
