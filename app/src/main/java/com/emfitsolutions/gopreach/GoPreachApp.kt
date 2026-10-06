package com.emfitsolutions.gopreach

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.emfitsolutions.gopreach.data.sync.PresenceHeartbeat
import com.emfitsolutions.gopreach.data.sync.ReminderScheduler
import com.emfitsolutions.gopreach.data.sync.RemoteSyncCoordinator
import com.emfitsolutions.gopreach.data.sync.SyncScheduler
import com.emfitsolutions.gopreach.notifications.CalendarAlarmRescheduler
import com.emfitsolutions.gopreach.notifications.NotificationHelper
import com.emfitsolutions.gopreach.notifications.NotificationSoundCoordinator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. Hilt root for DI across the app.
 *
 * Also configures WorkManager with [HiltWorkerFactory] so the offline sync queue
 * (Phase 1 — SyncWorker) can have its dependencies injected, and starts every
 * repository's Firestore listener via [RemoteSyncCoordinator] so the offline
 * cache reflects data created anywhere, not just on this device.
 */
@HiltAndroidApp
class GoPreachApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var remoteSyncCoordinator: RemoteSyncCoordinator

    @Inject
    lateinit var reminderScheduler: ReminderScheduler

    @Inject
    lateinit var calendarAlarmRescheduler: CalendarAlarmRescheduler

    @Inject
    lateinit var syncScheduler: SyncScheduler

    @Inject
    lateinit var notificationSoundCoordinator: NotificationSoundCoordinator

    @Inject
    lateinit var presenceHeartbeat: PresenceHeartbeat

    override fun onCreate() {
        super.onCreate()
        remoteSyncCoordinator.startAll()
        NotificationHelper.ensureChannel(this)
        reminderScheduler.ensureScheduled()
        calendarAlarmRescheduler.start()
        // "Fix the Notification Sound system" — see NotificationSoundCoordinator's
        // own doc comment: every notification trigger now runs at application
        // scope, for as long as the process is alive, instead of being tied to
        // whichever screen (if any) is currently on top.
        notificationSoundCoordinator.start()
        // "Make the App Synchronize to server automatically if there are
        // internet or mobile data available" — see SyncScheduler
        // .ensureAutomaticSyncStarted's own doc comment for the two triggers
        // this sets up (immediate on reconnect, periodic floor).
        syncScheduler.ensureAutomaticSyncStarted()
        // "Add Online Users Indicator" — keeps this session's own `presence`
        // document current for as long as it's signed in and online; see
        // PresenceHeartbeat's own doc comment.
        presenceHeartbeat.start()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
