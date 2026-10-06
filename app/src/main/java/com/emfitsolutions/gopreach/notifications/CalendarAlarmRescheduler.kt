package com.emfitsolutions.gopreach.notifications

import android.content.Context
import com.emfitsolutions.gopreach.data.model.ScheduleKind
import com.emfitsolutions.gopreach.data.repository.ScheduleRepository
import com.emfitsolutions.gopreach.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single choke point that keeps every Calendar event's [AlarmScheduler]
 * alarm in sync with the offline cache — started once from
 * [com.emfitsolutions.gopreach.GoPreachApp.onCreate], same as
 * [com.emfitsolutions.gopreach.data.sync.RemoteSyncCoordinator]. Re-arms
 * every future CALENDAR_EVENT/PERSONAL_NOTE alarm whenever the cached
 * [com.emfitsolutions.gopreach.data.model.Schedule] list changes for *any*
 * reason: a create/edit/delete on this device, a change synced down from
 * another device, or simply the app starting fresh after a reboot (Android
 * clears exact alarms on reboot — see [BootCompletedReceiver]). Also cancels
 * the alarm for any id that dropped out of the list (deleted, or moved into
 * the past), which per-save call sites can't reliably do on their own since
 * a delete only knows the id, not whether an alarm was ever armed for it.
 */
@Singleton
class CalendarAlarmRescheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduleRepository: ScheduleRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private var previouslyArmedIds: Set<String> = emptySet()

    fun start() {
        appScope.launch {
            scheduleRepository.observeAll().collect { all ->
                val alarmEligible = all.filter { it.kind != ScheduleKind.CHAT_SCHEDULE }
                val currentIds = alarmEligible.map { it.id }.toSet()
                (previouslyArmedIds - currentIds).forEach { AlarmScheduler.cancel(context, it) }
                alarmEligible.forEach { AlarmScheduler.schedule(context, it) }
                previouslyArmedIds = currentIds
            }
        }
    }
}
