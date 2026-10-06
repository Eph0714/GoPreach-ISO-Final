package com.emfitsolutions.gopreach.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.repository.NotificationSoundRepository
import com.emfitsolutions.gopreach.data.repository.ThemePreference
import com.emfitsolutions.gopreach.data.repository.ThemePreferenceRepository
import com.emfitsolutions.gopreach.notifications.NotificationHelper
import com.emfitsolutions.gopreach.ui.theme.ThemeColorOption
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val themePreferenceRepository: ThemePreferenceRepository,
    private val notificationSoundRepository: NotificationSoundRepository,
) : ViewModel() {
    val theme: StateFlow<ThemePreference> = themePreferenceRepository.preference
    val colorOption: StateFlow<ThemeColorOption> = themePreferenceRepository.colorOption
    val customColor: StateFlow<Color> = themePreferenceRepository.customColor
    val notificationSoundUri: StateFlow<Uri?> = notificationSoundRepository.soundUri
    val notificationsEnabled: StateFlow<Boolean> = notificationSoundRepository.enabled
    val notificationSoundsEnabled: StateFlow<Boolean> = notificationSoundRepository.soundEnabled
    val popupNotificationsEnabled: StateFlow<Boolean> = notificationSoundRepository.popupEnabled
    val transferRequestNotificationsEnabled: StateFlow<Boolean> = notificationSoundRepository.transferRequestEnabled
    val announcementNotificationsEnabled: StateFlow<Boolean> = notificationSoundRepository.announcementEnabled
    val messageNotificationsEnabled: StateFlow<Boolean> = notificationSoundRepository.messagesEnabled
    val importantNotificationsEnabled: StateFlow<Boolean> = notificationSoundRepository.importantEnabled

    fun setTheme(value: ThemePreference) = themePreferenceRepository.setPreference(value)
    fun setColorOption(value: ThemeColorOption) = themePreferenceRepository.setColorOption(value)
    fun setCustomColor(value: Color) = themePreferenceRepository.setCustomColor(value)

    /** [uri] null means "use the system default notification sound," same as
     * picking "Default" in the system ringtone picker. Applies immediately —
     * see [NotificationHelper.applySoundPreference]'s doc comment for why
     * this is the one place that recreates the notification channel. */
    fun setNotificationSound(uri: Uri?) {
        notificationSoundRepository.setSoundUri(uri)
        NotificationHelper.applySoundPreference(context, uri)
    }

    /** Master on/off switch for every notification [NotificationHelper.notify]
     * posts (Transfer Request, Announcement, report reminders) — spec: "allow
     * the publisher to turn on and turn off notification." Calendar Alarms
     * are untouched by this; see [NotificationSoundRepository]'s doc comment. */
    fun setNotificationsEnabled(value: Boolean) = notificationSoundRepository.setEnabled(value)

    /** "NOTIFICATION SETTINGS" — four independent per-device toggles layered
     * on top of [setNotificationsEnabled]'s master switch, checked in
     * [NotificationHelper.notify]: whether a notification plays a sound at
     * all (popup still shows), whether a popup shows at all, and separately
     * gating the two categories the spec names (Transfer Request/
     * Announcement) — every other category (Monthly Report reminders,
     * Calendar events) is unaffected by the last two and stays governed by
     * the master switch alone, same as before these existed. */
    fun setNotificationSoundsEnabled(value: Boolean) = notificationSoundRepository.setSoundEnabled(value)
    fun setPopupNotificationsEnabled(value: Boolean) = notificationSoundRepository.setPopupEnabled(value)
    fun setTransferRequestNotificationsEnabled(value: Boolean) = notificationSoundRepository.setTransferRequestEnabled(value)
    fun setAnnouncementNotificationsEnabled(value: Boolean) = notificationSoundRepository.setAnnouncementEnabled(value)
    fun setMessageNotificationsEnabled(value: Boolean) = notificationSoundRepository.setMessagesEnabled(value)
    fun setImportantNotificationsEnabled(value: Boolean) = notificationSoundRepository.setImportantEnabled(value)

    /** "ADD NOTIFICATION DEBUGGING... Test Notification button" — runs every
     * check [com.emfitsolutions.gopreach.notifications.NotificationHelper
     * .notify] itself would silently act on, then (only if every check
     * passed) actually posts a real notification through the exact same
     * path a genuine Announcement/Transfer Request/Message would use, so
     * "the test succeeds" really does mean a real notification would too. */
    fun runNotificationDiagnostics(): List<com.emfitsolutions.gopreach.notifications.NotificationDiagnostic> =
        NotificationHelper.diagnostics(context)

    fun sendTestNotification() {
        NotificationHelper.notify(
            context,
            id = 9999,
            title = "Test Notification",
            text = "If you can see and hear this, GoPreach notifications are working correctly.",
            category = com.emfitsolutions.gopreach.data.repository.NotificationCategory.MONTHLY_REPORT,
        )
    }
}
