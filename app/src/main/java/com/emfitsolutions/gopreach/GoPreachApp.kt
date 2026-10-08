package com.emfitsolutions.gopreach

import com.emfitsolutions.gopreach.di.appModule
import com.emfitsolutions.gopreach.di.infraModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.android.ext.android.get
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.workmanager.factory.KoinWorkerFactory
import android.app.Application
import androidx.work.Configuration
import com.emfitsolutions.gopreach.data.sync.PresenceHeartbeat
import com.emfitsolutions.gopreach.data.sync.ReminderScheduler
import com.emfitsolutions.gopreach.data.sync.SyncScheduler
import com.emfitsolutions.gopreach.notifications.CalendarAlarmRescheduler
import com.emfitsolutions.gopreach.notifications.NotificationHelper
import com.emfitsolutions.gopreach.notifications.NotificationSoundCoordinator

/**
 * Application entry point. Koin is started here for DI across the app.
 *
 * Also configures WorkManager with [KoinWorkerFactory] so the offline sync queue
 * (Phase 1 — SyncWorker) can have its dependencies injected, and starts every
 * repository's Firestore listener via [RemoteSyncCoordinator] so the offline
 * cache reflects data created anywhere, not just on this device.
 */
class GoPreachApp : Application(), Configuration.Provider {

    

    val reminderScheduler: ReminderScheduler by inject()

    val calendarAlarmRescheduler: CalendarAlarmRescheduler by inject()

    val syncScheduler: SyncScheduler by inject()

    val notificationSoundCoordinator: NotificationSoundCoordinator by inject()

    val presenceHeartbeat: PresenceHeartbeat by inject()

    val backendPoller: com.emfitsolutions.gopreach.data.sync.BackendPoller by inject()

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@GoPreachApp)
            modules(infraModule, appModule)
        }
        kotlinx.coroutines.GlobalScope.launch { runCatching { get<com.emfitsolutions.gopreach.data.local.dao.CacheDao>().deleteBlankIds(); get<com.emfitsolutions.gopreach.data.local.dao.SyncQueueDao>().removeBlankIds() } }
        backendPoller.start()
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
            .setWorkerFactory(KoinWorkerFactory())
            .build()
}
