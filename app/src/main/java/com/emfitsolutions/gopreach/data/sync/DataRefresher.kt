package com.emfitsolutions.gopreach.data.sync

import android.util.Log
import com.emfitsolutions.gopreach.data.repository.BibleTextCategoryRepository
import com.emfitsolutions.gopreach.data.repository.BibleTextRecordRepository
import com.emfitsolutions.gopreach.data.repository.CreditHourCategoryRepository
import com.emfitsolutions.gopreach.data.repository.CreditHourRecordRepository
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.data.repository.MinistryTimerSessionRepository
import com.emfitsolutions.gopreach.data.repository.MonthlyPlannerGoalRepository
import com.emfitsolutions.gopreach.data.repository.PlannerDayRepository
import com.emfitsolutions.gopreach.data.repository.WeeklyPlannerGoalRepository
import com.emfitsolutions.gopreach.data.repository.YearlyPlannerGoalRepository
import com.emfitsolutions.gopreach.data.repository.personIdFromAuthEmail
import com.google.firebase.auth.FirebaseAuth
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DataRefresher"

/**
 * The one-shot "pull the latest data down" logic — originally inline inside
 * [SyncWorker], pulled out here so a plain "Refresh" action (see
 * [com.emfitsolutions.gopreach.ui.components.RefreshButton]) can call it
 * directly without going through [SyncWorker]'s upload phase or WorkManager
 * at all. "Add a refresh button, not sync" — the Publisher wants to
 * explicitly re-fetch from the server on demand without also flushing
 * whatever local edits happen to be pending, which is exactly what tapping
 * "Sync to Server" would additionally do.
 */
@Singleton
class DataRefresher @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
    private val plannerDayRepository: PlannerDayRepository,
    private val monthlyPlannerGoalRepository: MonthlyPlannerGoalRepository,
    private val weeklyPlannerGoalRepository: WeeklyPlannerGoalRepository,
    private val yearlyPlannerGoalRepository: YearlyPlannerGoalRepository,
    private val creditHourCategoryRepository: CreditHourCategoryRepository,
    private val creditHourRecordRepository: CreditHourRecordRepository,
    private val ministryTimerSessionRepository: MinistryTimerSessionRepository,
    private val interestedPersonRepository: InterestedPersonRepository,
    private val bibleTextCategoryRepository: BibleTextCategoryRepository,
    private val bibleTextRecordRepository: BibleTextRecordRepository,
) {
    /** Pulls every My Planner/My Bible Text collection with a single `get()`
     * each (see [pullFirestoreCollectionOnce]'s own doc comment for why a
     * one-shot fetch is worth having alongside the live listeners
     * [RemoteSyncCoordinator] keeps running). Each collection's pull is
     * independent — one failing (offline, genuinely denied, whatever) never
     * stops the rest, and this never throws itself; callers get back
     * whether it fully succeeded, but a partial result already updated
     * whatever it could. */
    suspend fun refreshNow(): Boolean {
        val publisherPersonId = personIdFromAuthEmail(firebaseAuth.currentUser?.email)
        val scopedPulls = if (publisherPersonId != null) {
            listOf(
                "plannerDays" to suspend { plannerDayRepository.pullOnce(publisherPersonId) },
                "monthlyPlannerGoals" to suspend { monthlyPlannerGoalRepository.pullOnce(publisherPersonId) },
                "weeklyPlannerGoals" to suspend { weeklyPlannerGoalRepository.pullOnce(publisherPersonId) },
                "yearlyPlannerGoals" to suspend { yearlyPlannerGoalRepository.pullOnce(publisherPersonId) },
                "creditHourRecords" to suspend { creditHourRecordRepository.pullOnce(publisherPersonId) },
                "ministryTimerSessions" to suspend { ministryTimerSessionRepository.pullOnce(publisherPersonId) },
                "bibleTextCategories" to suspend { bibleTextCategoryRepository.pullOnce(publisherPersonId) },
                "bibleTextRecords" to suspend { bibleTextRecordRepository.pullOnce(publisherPersonId) },
            )
        } else {
            emptyList()
        }
        var allSucceeded = true
        (scopedPulls + listOf(
            "creditHourCategories" to suspend { creditHourCategoryRepository.pullOnce() },
            "interestedPeople" to suspend { interestedPersonRepository.pullOnce() },
        )).forEach { (name, pull) ->
            runCatching { pull() }.onFailure {
                allSucceeded = false
                Log.w(TAG, "One-shot pull of '$name' failed: ${it.message}")
            }
        }
        return allSucceeded
    }
}
