package com.emfitsolutions.gopreach.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.emfitsolutions.gopreach.R
import com.emfitsolutions.gopreach.data.repository.NotificationCategory
import com.emfitsolutions.gopreach.data.repository.NotificationSoundRepository

// "_v2" — bug fix ("notification sound isn't working"): a NotificationChannel's
// sound/importance is immutable once created (Android ignores every later
// createNotificationChannel call for the same id), so any device whose
// channel got created before this file's sound-handling existed (or with a
// different sound) was permanently stuck, regardless of what the code below
// says or what the user picks in Settings. Renaming forces a fresh channel —
// with today's correct settings — on every device, old installs included.
//
// "CHECK NOTIFICATION CHANNEL SETTINGS... Each channel must be correctly
// configured" — one channel per category (Announcements/Transfer Requests/
// Messages/everything else), not one shared channel for all of them, so
// muting or re-tuning one category in the system Settings app never affects
// another. IMPORTANCE_HIGH on every one of them (spec: "Notification
// importance: HIGH") — the old shared channel used IMPORTANCE_DEFAULT.
const val ANNOUNCEMENTS_CHANNEL_ID = "gopreach_announcements"
const val TRANSFER_REQUESTS_CHANNEL_ID = "gopreach_transfer_requests"
const val MESSAGES_CHANNEL_ID = "gopreach_messages"
const val IMPORTANT_CHANNEL_ID = "gopreach_important"
const val CALENDAR_ALARM_CHANNEL_ID = "gopreach_calendar_alarms"
const val LOCATION_SHARING_CHANNEL_ID = "gopreach_location_sharing"

private fun NotificationCategory.channelId(): String = when (this) {
    NotificationCategory.ANNOUNCEMENT -> ANNOUNCEMENTS_CHANNEL_ID
    NotificationCategory.TRANSFER_REQUEST -> TRANSFER_REQUESTS_CHANNEL_ID
    NotificationCategory.MESSAGE -> MESSAGES_CHANNEL_ID
    NotificationCategory.MONTHLY_REPORT, NotificationCategory.CALENDAR_SCHEDULE -> IMPORTANT_CHANNEL_ID
}

/**
 * Local-only notifications (no push backend yet — see BUILD_PLAN.md's
 * "Redesign the Publisher Dashboard" phase note): Monthly Report reminders
 * ([com.emfitsolutions.gopreach.data.sync.ReminderWorker], a periodic
 * WorkManager check), "Transfer/Forward" alerts (fired in-process — see
 * [com.emfitsolutions.gopreach.ui.screens.pipeline.ForwardRequestsViewModel]
 * — the moment a new pending request streams in while this app is running),
 * new-Announcement alerts, Group Chat messages ([com.emfitsolutions.gopreach
 * .ui.components.GroupChatMessageNotifier]), and Calendar Alarms (see
 * [com.emfitsolutions.gopreach.notifications.AlarmScheduler]). Every call
 * here is a no-op if the user hasn't granted POST_NOTIFICATIONS (Android
 * 13+) — never crashes, never nags twice for the same permission in one call.
 *
 * Four per-category channels ([ANNOUNCEMENTS_CHANNEL_ID]/
 * [TRANSFER_REQUESTS_CHANNEL_ID]/[MESSAGES_CHANNEL_ID]/[IMPORTANT_CHANNEL_ID],
 * see [NotificationCategory.channelId]) each play whichever sound the user
 * picked in Settings (see [applySoundPreference]) — a brand-new channel is
 * created with that sound explicitly set (never left to chance) so "make a
 * default notification sound to all the incoming notification" holds from
 * the very first notification, not just after the user opens Settings once;
 * [CALENDAR_ALARM_CHANNEL_ID] backs the ringing Calendar Alarm notification
 * itself and is deliberately silent — [AlarmRingService] plays that same
 * user-picked sound on a loop until stopped, so the channel doesn't also
 * play it once on top.
 *
 * [notify] also honors the Publisher-facing on/off switch (spec: "allow the
 * publisher to turn on and turn off notification") — see
 * [NotificationSoundRepository.isEnabled]. That switch does not affect
 * Calendar Alarms, which ring from an event the user scheduled themselves
 * through a different mechanism entirely (see [AlarmScheduler]).
 */
object NotificationHelper {
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val soundUri = NotificationSoundRepository.readStoredSoundUri(context)
        listOf(
            ANNOUNCEMENTS_CHANNEL_ID to "Announcements",
            TRANSFER_REQUESTS_CHANNEL_ID to "Transfer Requests",
            MESSAGES_CHANNEL_ID to "Group Chat Messages",
            IMPORTANT_CHANNEL_ID to "Important Notifications",
        ).forEach { (id, name) ->
            if (manager.getNotificationChannel(id) == null) {
                manager.createNotificationChannel(buildSoundedChannel(id, name, soundUri))
            }
        }
        if (manager.getNotificationChannel(CALENDAR_ALARM_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CALENDAR_ALARM_CHANNEL_ID, "Calendar Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Calendar event alarms"
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
        if (manager.getNotificationChannel(LOCATION_SHARING_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(LOCATION_SHARING_CHANNEL_ID, "Location Sharing", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Ongoing status while your location is being shared"
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
    }

    /**
     * Recreates all four category channels with [soundUri] — Android ignores
     * a NotificationChannel's sound/importance/etc. after it's first
     * created, so "let the user change the notification sound" (spec)
     * requires deleting and recreating every one of them, not just calling
     * createNotificationChannel again with different settings. [soundUri]
     * null restores the system default notification sound. Only called when
     * the user actually changes their pick in Settings (not on every app
     * start) so a user's own further tweaks to a channel via the system
     * Settings app aren't silently reset on every launch.
     */
    fun applySoundPreference(context: Context, soundUri: Uri?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        listOf(
            ANNOUNCEMENTS_CHANNEL_ID to "Announcements",
            TRANSFER_REQUESTS_CHANNEL_ID to "Transfer Requests",
            MESSAGES_CHANNEL_ID to "Group Chat Messages",
            IMPORTANT_CHANNEL_ID to "Important Notifications",
        ).forEach { (id, name) ->
            manager.deleteNotificationChannel(id)
            manager.createNotificationChannel(buildSoundedChannel(id, name, soundUri))
        }
    }

    /** [soundUri] null still gets an explicit sound — Android's own default
     * notification sound — rather than leaving the channel's sound unset, so
     * every notification reliably makes a sound (spec: "make a sound to all
     * notification") regardless of OEM quirks around a channel's implicit
     * default. [NotificationManager.IMPORTANCE_HIGH] — spec: "Notification
     * importance: HIGH... sound enabled... correct audio usage." */
    private fun buildSoundedChannel(id: String, name: String, soundUri: Uri?): NotificationChannel =
        NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
            description = "GoPreach $name"
            enableVibration(true)
            setSound(
                soundUri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }

    /** Posts a local notification — a no-op if the Publisher has turned
     * notifications off overall (spec: "allow the publisher to turn on and
     * turn off notification"), if [category]'s own toggle is off (Settings →
     * Notifications' per-category switches), or if "Show Popup
     * Notifications" is off — same as it's already a no-op without
     * POST_NOTIFICATIONS permission.
     *
     * [NotificationCategory.TRANSFER_REQUEST] posts at
     * [NotificationCompat.PRIORITY_HIGH] — "🔴 High Priority: Incoming
     * Transfer Request" / "🟡 Normal Priority: New Announcement" — a more
     * noticeable heads-up than every other category's PRIORITY_DEFAULT. */
    fun notify(context: Context, id: Int, title: String, text: String, category: NotificationCategory = NotificationCategory.CALENDAR_SCHEDULE) {
        if (!hasPermission(context)) return
        if (!NotificationSoundRepository.isEnabled(context)) return
        if (!NotificationSoundRepository.isPopupEnabled(context)) return
        if (!NotificationSoundRepository.isCategoryEnabled(context, category)) return
        val soundOn = NotificationSoundRepository.isSoundEnabled(context)
        val builder = NotificationCompat.Builder(context, category.channelId())
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(if (category == NotificationCategory.TRANSFER_REQUEST) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
        // "Enable Notification Sounds" off — the popup still shows, just
        // without sound/vibration. Bug fix: this used to call
        // .setSilent(!soundOn) unconditionally (i.e. also .setSilent(false)
        // on every normal notification) — setSilent() is a narrower, less
        // consistently-supported API across OEM skins/API levels than simply
        // never touching it when there's nothing to silence, so it's now
        // only ever called when actually needed.
        if (!soundOn) builder.setSilent(true)
        // Below API 26 there is no NotificationChannel at all, so the sound
        // set on [buildSoundedChannel] never applies — bug fix: the sound
        // (and default vibration) has to be set directly on the notification
        // itself for it to play anything on Android 7.0/7.1. Harmless to set
        // on O+ too; the channel's own sound simply takes priority there.
        if (soundOn && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            val soundUri = NotificationSoundRepository.readStoredSoundUri(context)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            builder.setSound(soundUri).setDefaults(NotificationCompat.DEFAULT_VIBRATE)
        }
        val notification = builder.build()
        runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** "ADD NOTIFICATION DEBUGGING... Display diagnostic results" — every
     * check [notify] itself would otherwise silently fail on, surfaced as
     * one line each so Settings' "Test Notification" button can show exactly
     * which one (if any) is the reason a test notification wouldn't make a
     * sound. Read-only — this never changes anything, just reports. */
    fun diagnostics(context: Context): List<NotificationDiagnostic> {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager?.getNotificationChannel(NotificationCategory.MONTHLY_REPORT.channelId())
        return listOf(
            NotificationDiagnostic(
                "Notification permission",
                hasPermission(context),
                "Notifications are blocked for GoPreach at the Android system level. Open Android's app notification settings and allow them.",
            ),
            NotificationDiagnostic(
                "Notifications enabled system-wide",
                NotificationManagerCompat.from(context).areNotificationsEnabled(),
                "Notifications for GoPreach are turned off in the device's own Settings app, separately from GoPreach's own switch below.",
            ),
            NotificationDiagnostic(
                "GoPreach notification switch",
                NotificationSoundRepository.isEnabled(context),
                "The master \"Notifications\" switch in Settings → Notifications is off.",
            ),
            NotificationDiagnostic(
                "Popup notifications",
                NotificationSoundRepository.isPopupEnabled(context),
                "\"Show Popup Notifications\" is off in Settings → Notifications.",
            ),
            NotificationDiagnostic(
                "Notification sound enabled",
                NotificationSoundRepository.isSoundEnabled(context),
                "\"Enable Notification Sounds\" is off in Settings → Notifications — notifications will show silently.",
            ),
            NotificationDiagnostic(
                "Notification channel available",
                Build.VERSION.SDK_INT < Build.VERSION_CODES.O || channel != null,
                "The Android notification channel hasn't been created yet — reopening the app should fix this.",
            ),
            NotificationDiagnostic(
                "Channel not muted by Android",
                channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE,
                "This category's channel has been muted in Android's own notification settings — open them and turn it back on.",
            ),
        )
    }
}

/** One line of [NotificationHelper.diagnostics] — [reason] is only shown
 * when [passed] is false. */
data class NotificationDiagnostic(val label: String, val passed: Boolean, val reason: String)
