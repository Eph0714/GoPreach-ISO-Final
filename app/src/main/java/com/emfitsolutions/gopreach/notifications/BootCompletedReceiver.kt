package com.emfitsolutions.gopreach.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Exact alarms (see [AlarmScheduler]) don't survive a reboot — Android
 * clears them. This receiver's only job is to exist: receiving
 * `BOOT_COMPLETED` starts the app process, which runs
 * [com.emfitsolutions.gopreach.GoPreachApp.onCreate] and, through it,
 * [CalendarAlarmRescheduler], which re-arms every future Calendar event's
 * alarm from the offline cache. Nothing else needs to happen here. */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Intentionally empty — see doc comment above.
    }
}
