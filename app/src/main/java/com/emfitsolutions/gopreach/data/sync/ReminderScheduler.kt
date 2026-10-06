package com.emfitsolutions.gopreach.data.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules [ReminderWorker] to run roughly once a day for the lifetime of the
 * app install (spec: monthly report reminders, 5-days-before-month-end
 * no-preaching-activity nudge). [ExistingPeriodicWorkPolicy.KEEP] — calling
 * this again (e.g. every app launch, from [com.emfitsolutions.gopreach
 * .GoPreachApp]) never resets or duplicates the existing schedule.
 *
 * Once a day, not a tighter interval, since every check here is date-window
 * based (whole-day granularity: "5 days before month end," "last 2 days of
 * the month") — nothing is lost by a check landing up to ~24h after its
 * window technically opened, and this is the app's only local-notification
 * mechanism today (see NotificationHelper's doc comment on why: no push
 * backend yet).
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val UNIQUE_WORK_NAME = "gopreach_reminders"
    }

    fun ensureScheduled() {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
