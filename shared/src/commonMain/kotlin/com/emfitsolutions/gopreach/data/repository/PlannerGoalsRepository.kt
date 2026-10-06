package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.MonthlyPlannerGoal
import com.emfitsolutions.gopreach.data.model.WeeklyPlannerGoal
import com.emfitsolutions.gopreach.data.model.YearlyPlannerGoal
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.platform.nowMillis
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val MONTHLY_COLLECTION = "monthlyPlannerGoals"

/** My Planner → Month spec §24-§25. */
class MonthlyPlannerGoalRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    fun observeForPublisher(publisherPersonId: String): Flow<List<MonthlyPlannerGoal>> =
        observeAll().map { list -> list.filter { it.publisherPersonId == publisherPersonId } }

    fun observeAll(): Flow<List<MonthlyPlannerGoal>> = offline.observeCollection(MONTHLY_COLLECTION)

    suspend fun save(goal: MonthlyPlannerGoal): MonthlyPlannerGoal {
        val id = goal.id.ifBlank { MonthlyPlannerGoal.idFor(goal.publisherPersonId, goal.year, goal.month) }
        val withId = goal.copy(id = id, updatedAt = nowMillis())
        offline.save(MONTHLY_COLLECTION, id, withId)
        return withId
    }

    fun startRemoteSync(publisherPersonId: String): Flow<Unit> =
        remote.mirror(MONTHLY_COLLECTION, MonthlyPlannerGoal::class, equalTo = "publisherPersonId" to publisherPersonId) { it.id }

    suspend fun pullOnce(publisherPersonId: String) = remote.pullOnce(MONTHLY_COLLECTION, MonthlyPlannerGoal::class, equalTo = "publisherPersonId" to publisherPersonId) { it.id }
}

private const val WEEKLY_COLLECTION = "weeklyPlannerGoals"

/** My Planner → Week (Dashboard/My Planner integration). */
class WeeklyPlannerGoalRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    fun observeForPublisher(publisherPersonId: String): Flow<List<WeeklyPlannerGoal>> =
        observeAll().map { list -> list.filter { it.publisherPersonId == publisherPersonId } }

    fun observeAll(): Flow<List<WeeklyPlannerGoal>> = offline.observeCollection(WEEKLY_COLLECTION)

    suspend fun save(goal: WeeklyPlannerGoal): WeeklyPlannerGoal {
        val id = goal.id.ifBlank { WeeklyPlannerGoal.idFor(goal.publisherPersonId, goal.weekStart) }
        val withId = goal.copy(id = id, updatedAt = nowMillis())
        offline.save(WEEKLY_COLLECTION, id, withId)
        return withId
    }

    fun startRemoteSync(publisherPersonId: String): Flow<Unit> =
        remote.mirror(WEEKLY_COLLECTION, WeeklyPlannerGoal::class, equalTo = "publisherPersonId" to publisherPersonId) { it.id }

    suspend fun pullOnce(publisherPersonId: String) = remote.pullOnce(WEEKLY_COLLECTION, WeeklyPlannerGoal::class, equalTo = "publisherPersonId" to publisherPersonId) { it.id }
}

private const val YEARLY_COLLECTION = "yearlyPlannerGoals"

/** My Planner → Year spec §26. */
class YearlyPlannerGoalRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    fun observeForPublisher(publisherPersonId: String): Flow<List<YearlyPlannerGoal>> =
        observeAll().map { list -> list.filter { it.publisherPersonId == publisherPersonId } }

    fun observeAll(): Flow<List<YearlyPlannerGoal>> = offline.observeCollection(YEARLY_COLLECTION)

    suspend fun save(goal: YearlyPlannerGoal): YearlyPlannerGoal {
        val id = goal.id.ifBlank { YearlyPlannerGoal.idFor(goal.publisherPersonId, goal.year) }
        val withId = goal.copy(id = id, updatedAt = nowMillis())
        offline.save(YEARLY_COLLECTION, id, withId)
        return withId
    }

    fun startRemoteSync(publisherPersonId: String): Flow<Unit> =
        remote.mirror(YEARLY_COLLECTION, YearlyPlannerGoal::class, equalTo = "publisherPersonId" to publisherPersonId) { it.id }

    suspend fun pullOnce(publisherPersonId: String) = remote.pullOnce(YEARLY_COLLECTION, YearlyPlannerGoal::class, equalTo = "publisherPersonId" to publisherPersonId) { it.id }
}
