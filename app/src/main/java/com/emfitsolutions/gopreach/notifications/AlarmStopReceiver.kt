package com.emfitsolutions.gopreach.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The ringing notification's "Stop Alarm" action — see
 * [AlarmRingService.stop], also reused by the in-app "Stop Alarm" banner. */
class AlarmStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmRingService.stop(context)
    }
}
