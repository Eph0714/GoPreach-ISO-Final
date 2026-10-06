package com.emfitsolutions.gopreach.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.emfitsolutions.gopreach.data.model.Schedule

private const val TAG = "AlarmScheduler"
const val EXTRA_SCHEDULE_ID = "scheduleId"
const val EXTRA_SCHEDULE_TITLE = "scheduleTitle"

/**
 * Exact-time alarms for Calendar events — a different mechanism from every
 * other notification in this app (see [NotificationHelper]'s doc comment):
 * those all fire opportunistically (in-process while the app happens to be
 * running, or once a day from [com.emfitsolutions.gopreach.data.sync
 * .ReminderWorker]), which is fine for "don't forget to submit your report"
 * but not for "ring at the exact time of this calendar event," so this uses
 * [AlarmManager.setExactAndAllowWhileIdle] scheduled per event instead —
 * fires even if the app isn't running, waking [CalendarAlarmReceiver] which
 * starts [AlarmRingService] to actually ring until the user stops it.
 *
 * Callers never need to track which events were already scheduled — every
 * PendingIntent here is keyed by [Schedule.id]'s hash, so scheduling the same
 * id again (an edit) simply replaces the old trigger time
 * ([PendingIntent.FLAG_UPDATE_CURRENT]), and [CalendarAlarmRescheduler] is
 * the one place that also calls [cancel] for ids that disappeared entirely.
 */
object AlarmScheduler {
    fun schedule(context: Context, schedule: Schedule) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (alarmManager == null) {
            Log.w(TAG, "AlarmManager unavailable; cannot schedule alarm for ${schedule.id}")
            return
        }
        if (schedule.startTime <= System.currentTimeMillis()) {
            // Already in the past (or edited to be) — make sure no stale
            // trigger from a previous, later startTime is still armed.
            cancel(context, schedule.id)
            return
        }
        val pendingIntent = pendingIntentFor(context, schedule.id, schedule.title)
        val canScheduleExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        runCatching {
            if (canScheduleExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, schedule.startTime, pendingIntent)
            } else {
                // No "Alarms & reminders" permission granted — closest
                // achievable without it; may fire a little late under Doze.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, schedule.startTime, pendingIntent)
            }
        }.onFailure { Log.e(TAG, "Failed to schedule alarm for ${schedule.id}", it) }
    }

    fun cancel(context: Context, scheduleId: String) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(pendingIntentFor(context, scheduleId, title = ""))
    }

    /** Whether the OS currently lets this app schedule *exact* alarms — only
     * meaningful on API 31+ (always true below that). Surfaced in Settings so
     * the user can grant "Alarms & reminders" if they want Calendar Alarms to
     * ring at the precise minute rather than "close to it." */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
        return alarmManager.canScheduleExactAlarms()
    }

    private fun pendingIntentFor(context: Context, scheduleId: String, title: String): PendingIntent {
        val intent = Intent(context, CalendarAlarmReceiver::class.java).apply {
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(EXTRA_SCHEDULE_TITLE, title)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, scheduleId.hashCode(), intent, flags)
    }
}
