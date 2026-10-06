package com.emfitsolutions.gopreach.data.repository

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME = "gopreach_notification_settings"
private const val KEY_SOUND_URI = "notification_sound_uri"
private const val KEY_ENABLED = "notifications_enabled"
private const val KEY_SOUND_ENABLED = "notification_sounds_enabled"
private const val KEY_POPUP_ENABLED = "notification_popups_enabled"
private const val KEY_TRANSFER_REQUEST_ENABLED = "notification_transfer_requests_enabled"
private const val KEY_ANNOUNCEMENT_ENABLED = "notification_announcements_enabled"
private const val KEY_MESSAGES_ENABLED = "notification_messages_enabled"
private const val KEY_IMPORTANT_ENABLED = "notification_important_enabled"

/**
 * Per-device choice of which system sound plays for every incoming
 * notification this app posts — Transfer Request, Announcement, and Calendar
 * Alarm alike all share this one setting (spec: "make a default notification
 * sound to all the incoming notification"), same "own choice on this device
 * only, never synced" pattern as [ThemePreferenceRepository]. Null means "use
 * the system default notification sound" (Android's own default when a
 * channel has no sound explicitly set), not silence — the user picks that by
 * browsing to their phone's own notification sounds via the system ringtone
 * picker (see SettingsScreen), same picker Android's own Settings app uses.
 *
 * Also carries [enabled] — spec: "allow the publisher to turn on and turn off
 * notification" — a single per-device master switch for every notification
 * [com.emfitsolutions.gopreach.notifications.NotificationHelper.notify] posts
 * (Transfer Request, Announcement, report reminders). Calendar Alarms are
 * deliberately unaffected: those ring from an event the Publisher explicitly
 * scheduled themselves (see [com.emfitsolutions.gopreach.notifications
 * .AlarmRingService]), a different mechanism from this on/off switch for
 * *incoming* notifications from other people/the system.
 */
@Singleton
class NotificationSoundRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _soundUri = MutableStateFlow(readStoredUri())
    val soundUri: StateFlow<Uri?> = _soundUri

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled

    // "NOTIFICATION SETTINGS: Enable Notification Sounds / Transfer Request
    // Notifications / Announcement Notifications / Show Popup Notifications"
    // — four additional, independent per-device toggles layered on top of
    // [enabled] (the pre-existing overall on/off switch, unchanged, still
    // gates every one of these). Each defaults to true so nothing changes
    // for a user who's never opened this new section of Settings.
    private val _soundEnabled = MutableStateFlow(prefs.getBoolean(KEY_SOUND_ENABLED, true))
    val soundEnabled: StateFlow<Boolean> = _soundEnabled

    private val _popupEnabled = MutableStateFlow(prefs.getBoolean(KEY_POPUP_ENABLED, true))
    val popupEnabled: StateFlow<Boolean> = _popupEnabled

    private val _transferRequestEnabled = MutableStateFlow(prefs.getBoolean(KEY_TRANSFER_REQUEST_ENABLED, true))
    val transferRequestEnabled: StateFlow<Boolean> = _transferRequestEnabled

    private val _announcementEnabled = MutableStateFlow(prefs.getBoolean(KEY_ANNOUNCEMENT_ENABLED, true))
    val announcementEnabled: StateFlow<Boolean> = _announcementEnabled

    private val _messagesEnabled = MutableStateFlow(prefs.getBoolean(KEY_MESSAGES_ENABLED, true))
    val messagesEnabled: StateFlow<Boolean> = _messagesEnabled

    private val _importantEnabled = MutableStateFlow(prefs.getBoolean(KEY_IMPORTANT_ENABLED, true))
    val importantEnabled: StateFlow<Boolean> = _importantEnabled

    private fun readStoredUri(): Uri? =
        validatedSoundUri(context, prefs.getString(KEY_SOUND_URI, null)) { prefs.edit { remove(KEY_SOUND_URI) } }

    fun setSoundUri(uri: Uri?) {
        prefs.edit { if (uri == null) remove(KEY_SOUND_URI) else putString(KEY_SOUND_URI, uri.toString()) }
        _soundUri.value = uri
    }

    fun setEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, value) }
        _enabled.value = value
    }

    fun setSoundEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_SOUND_ENABLED, value) }
        _soundEnabled.value = value
    }

    fun setPopupEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_POPUP_ENABLED, value) }
        _popupEnabled.value = value
    }

    fun setTransferRequestEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_TRANSFER_REQUEST_ENABLED, value) }
        _transferRequestEnabled.value = value
    }

    fun setAnnouncementEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_ANNOUNCEMENT_ENABLED, value) }
        _announcementEnabled.value = value
    }

    fun setMessagesEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_MESSAGES_ENABLED, value) }
        _messagesEnabled.value = value
    }

    fun setImportantEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_IMPORTANT_ENABLED, value) }
        _importantEnabled.value = value
    }

    companion object {
        /**
         * Static read used by [com.emfitsolutions.gopreach.notifications
         * .NotificationHelper.notify] — that's a plain object (not Hilt-
         * injected, so it can be called from WorkManager/Compose callsites
         * without threading a repository instance through every one of
         * them), so it reads this same SharedPreferences file directly
         * rather than going through the injected [enabled] StateFlow above.
         */
        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

        /** Static reads for the four new per-category/behavior toggles —
         * same rationale as [isEnabled]: [NotificationHelper.notify] is a
         * plain object, not Hilt-injected. */
        fun isSoundEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_SOUND_ENABLED, true)

        fun isPopupEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_POPUP_ENABLED, true)

        fun isTransferRequestEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_TRANSFER_REQUEST_ENABLED, true)

        fun isAnnouncementEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ANNOUNCEMENT_ENABLED, true)

        fun isMessagesEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_MESSAGES_ENABLED, true)

        fun isImportantEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_IMPORTANT_ENABLED, true)

        /** One place [NotificationHelper.notify] checks a category's own
         * toggle, instead of a `when` scattered at every call site — Monthly
         * Report reminders have no toggle of their own (they've never had
         * one) and fall under the "Important" switch alongside Calendar
         * events, matching [com.emfitsolutions.gopreach.notifications
         * .NotificationHelper]'s own channel grouping for the two. */
        fun isCategoryEnabled(context: Context, category: NotificationCategory): Boolean = when (category) {
            NotificationCategory.TRANSFER_REQUEST -> isTransferRequestEnabled(context)
            NotificationCategory.ANNOUNCEMENT -> isAnnouncementEnabled(context)
            NotificationCategory.MESSAGE -> isMessagesEnabled(context)
            NotificationCategory.MONTHLY_REPORT, NotificationCategory.CALENDAR_SCHEDULE -> isImportantEnabled(context)
        }

        /** Same static-read rationale as [isEnabled] — used by
         * [com.emfitsolutions.gopreach.notifications.NotificationHelper
         * .ensureChannel] so a brand-new category channel is created with
         * whichever sound the user already picked, not the system default,
         * if this is a reinstall or the channel was otherwise cleared. */
        fun readStoredSoundUri(context: Context): Uri? =
            validatedSoundUri(context, context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_SOUND_URI, null)) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { remove(KEY_SOUND_URI) }
            }

        /** "Never allow an invalid sound resource to cause the notification
         * to become silent" — [Uri.parse] never throws (it happily builds a
         * `Uri` object out of any string, valid sound or not), so a stored
         * URI pointing at a since-uninstalled ringtone app's sound or a
         * deleted media file used to sail straight through into
         * [android.app.NotificationChannel.setSound]/[NotificationCompat
         * .Builder.setSound] with nothing ever checking it actually
         * resolves to a playable sound — Android's own behavior in that case
         * is to simply not play anything, which reads exactly like this
         * bug's own symptom ("no notification sound is played"). Resolves
         * the URI through [RingtoneManager.getRingtone] (the same API the
         * system's own ringtone picker and every notification actually use
         * to play a sound) and clears the stored preference the moment it
         * doesn't resolve, falling back to `null` (the system default
         * sound, applied by every caller of this repository) rather than a
         * silently-broken custom one. */
        private fun validatedSoundUri(context: Context, stored: String?, onInvalid: () -> Unit): Uri? {
            val uri = stored?.let(Uri::parse) ?: return null
            val resolvesToARealSound = runCatching { RingtoneManager.getRingtone(context, uri) != null }.getOrDefault(false)
            if (!resolvesToARealSound) {
                onInvalid()
                return null
            }
            return uri
        }
    }
}
