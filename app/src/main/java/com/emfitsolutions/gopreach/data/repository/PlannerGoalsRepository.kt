package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.MonthlyPlannerGoal
import com.emfitsolutions.gopreach.data.model.WeeklyPlannerGoal
import com.emfitsolutions.gopreach.data.model.YearlyPlannerGoal
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.mirrorFirestoreCollection
import com.emfitsolutions.gopreach.data.sync.pullFirestoreCollectionOnce
import com.emfitsolutions.gopreach.di.ApplicationScope
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val MONTHLY_COLLECTION = "monthlyPlannerGoals"

/** My Planner → Month spec §24-§25. */
@Singleton
class MonthlyPlannerGoalRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    fun observeForPublisher(publisherPersonId: String): Flow<List<MonthlyPlannerGoal>> =
        observeAll().map { list -> list.filter { it.publisherPersonId == publisherPersonId } }

    fun observeAll(): Flow<List<MonthlyPlannerGoal>> = offline.observeCollection(MONTHLY_COLLECTION)

    suspend fun save(goal: MonthlyPlannerGoal): MonthlyPlannerGoal {
        val id = goal.id.ifBlank { MonthlyPlannerGoal.idFor(goal.publisherPersonId, goal.year, goal.month) }
        val withId = goal.copy(id = id, updatedAt = System.currentTimeMillis())
        offline.save(MONTHLY_COLLECTION, id, withId)
        return withId
    }

    fun startRemoteSync(publisherPersonId: String): Flow<Unit> =
        mirrorFirestoreCollection(
            firestore, offline, appScope, MONTHLY_COLLECTION, MonthlyPlannerGoal::class.java,
            query = firestore.collection(MONTHLY_COLLECTION).whereEqualTo("publisherPersonId", publisherPersonId),
        ) { it.id }

    suspend fun pullOnce(publisherPersonId: String) = pullFirestoreCollectionOnce(
        firestore, offline, MONTHLY_COLLECTION, MonthlyPlannerGoal::class.java,
        query = firestore.collection(MONTHLY_COLLECTION).whereEqualTo("publisherPersonId", publisherPersonId),
    ) { it.id }
}

private const val WEEKLY_COLLECTION = "weeklyPlannerGoals"

/** My Planner → Week (Dashboard/My Planner integration). */
@Singleton
class WeeklyPlannerGoalRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    fun observeForPublisher(publisherPersonId: String): Flow<List<WeeklyPlannerGoal>> =
        observeAll().map { list -> list.filter { it.publisherPersonId == publisherPersonId } }

    fun observeAll(): Flow<List<WeeklyPlannerGoal>> = offline.observeCollection(WEEKLY_COLLECTION)

    suspend fun save(goal: WeeklyPlannerGoal): WeeklyPlannerGoal {
        val id = goal.id.ifBlank { WeeklyPlannerGoal.idFor(goal.publisherPersonId, goal.weekStart) }
        val withId = goal.copy(id = id, updatedAt = System.currentTimeMillis())
        offline.save(WEEKLY_COLLECTION, id, withId)
        return withId
    }

    fun startRemoteSync(publisherPersonId: String): Flow<Unit> =
        mirrorFirestoreCollection(
            firestore, offline, appScope, WEEKLY_COLLECTION, WeeklyPlannerGoal::class.java,
            query = firestore.collection(WEEKLY_COLLECTION).whereEqualTo("publisherPersonId", publisherPersonId),
        ) { it.id }

    suspend fun pullOnce(publisherPersonId: String) = pullFirestoreCollectionOnce(
        firestore, offline, WEEKLY_COLLECTION, WeeklyPlannerGoal::class.java,
        query = firestore.collection(WEEKLY_COLLECTION).whereEqualTo("publisherPersonId", publisherPersonId),
    ) { it.id }
}

private const val YEARLY_COLLECTION = "yearlyPlannerGoals"

/** My Planner → Year spec §26. */
@Singleton
class YearlyPlannerGoalRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    fun observeForPublisher(publisherPersonId: String): Flow<List<YearlyPlannerGoal>> =
        observeAll().map { list -> list.filter { it.publisherPersonId == publisherPersonId } }

    fun observeAll(): Flow<List<YearlyPlannerGoal>> = offline.observeCollection(YEARLY_COLLECTION)

    suspend fun save(goal: YearlyPlannerGoal): YearlyPlannerGoal {
        val id = goal.id.ifBlank { YearlyPlannerGoal.idFor(goal.publisherPersonId, goal.year) }
        val withId = goal.copy(id = id, updatedAt = System.currentTimeMillis())
        offline.save(YEARLY_COLLECTION, id, withId)
        return withId
    }

    fun startRemoteSync(publisherPersonId: String): Flow<Unit> =
        mirrorFirestoreCollection(
            firestore, offline, appScope, YEARLY_COLLECTION, YearlyPlannerGoal::class.java,
            query = firestore.collection(YEARLY_COLLECTION).whereEqualTo("publisherPersonId", publisherPersonId),
        ) { it.id }

    suspend fun pullOnce(publisherPersonId: String) = pullFirestoreCollectionOnce(
        firestore, offline, YEARLY_COLLECTION, YearlyPlannerGoal::class.java,
        query = firestore.collection(YEARLY_COLLECTION).whereEqualTo("publisherPersonId", publisherPersonId),
    ) { it.id }
}
