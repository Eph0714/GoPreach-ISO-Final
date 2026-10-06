package com.emfitsolutions.gopreach.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Fired by [AlarmScheduler] at a Calendar event's exact start time — hands
 * off to [AlarmRingService] to actually ring, since a plain notification
 * can't loop a sound or vibrate indefinitely on its own. */
class CalendarAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: return
        val title = intent.getStringExtra(EXTRA_SCHEDULE_TITLE).orEmpty().ifBlank { "Calendar Event" }
        val serviceIntent = Intent(context, AlarmRingService::class.java).apply {
            putExtra(AlarmRingService.EXTRA_TITLE, title)
            putExtra(AlarmRingService.EXTRA_SCHEDULE_ID, scheduleId)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
