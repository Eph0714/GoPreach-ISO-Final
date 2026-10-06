package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.PlannerDay
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.mirrorFirestoreCollection
import com.emfitsolutions.gopreach.data.sync.pullFirestoreCollectionOnce
import com.emfitsolutions.gopreach.di.ApplicationScope
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val COLLECTION = "plannerDays"

/** My Planner spec §16-§27 — one [PlannerDay] per (publisher, date), plus
 * every day a Publisher has ever touched for the Month tab's calendar
 * highlighting. */
@Singleton
class PlannerDayRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    private val monthlyReportRepository: MonthlyReportRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    fun observeForPublisher(publisherPersonId: String): Flow<List<PlannerDay>> =
        observeAll().map { list -> list.filter { it.publisherPersonId == publisherPersonId } }

    /** One specific day — `null` until the Publisher has actually saved
     * anything for it (a never-touched day is never pre-created just because
     * its tab was opened). */
    fun observeDay(publisherPersonId: String, dayStart: Long): Flow<PlannerDay?> {
        val id = PlannerDay.idFor(publisherPersonId, dayStart)
        return observeAll().map { list -> list.firstOrNull { it.id == id } }
    }

    fun observeAll(): Flow<List<PlannerDay>> = offline.observeCollection(COLLECTION)

    suspend fun save(day: PlannerDay): PlannerDay {
        val id = day.id.ifBlank { PlannerDay.idFor(day.publisherPersonId, day.dayStart) }
        val withId = day.copy(id = id, updatedAt = System.currentTimeMillis())
        offline.save(COLLECTION, id, withId)
        return withId
    }

    /** Shared by the Day tab's Hours/Minutes steppers and the Ministry
     * Timer's STOP action (spec §20.7 — "saved duration contributes to
     * ministry time") — one write path so both ever add minutes into
     * [PlannerDay.totalMinutes] the exact same way. [dayStart] is snapped to
     * its own day's start regardless of what's passed in, matching
     * [observeDay]'s own id derivation. Never negative (spec §18/§29). */
    suspend fun addMinutes(publisherPersonId: String, dayStart: Long, additionalMinutes: Int) {
        val alignedDayStart = com.emfitsolutions.gopreach.domain.DayBounds.of(dayStart).startInclusive
        // A month whose report is already submitted is closed — no more ministry time (Timer included).
        if (monthlyReportRepository.isMonthSubmitted(publisherPersonId, alignedDayStart)) return
        val current = observeDay(publisherPersonId, alignedDayStart).first()
        val newTotal = ((current?.totalMinutes ?: 0) + additionalMinutes).coerceAtLeast(0)
        val base = current ?: PlannerDay(
            id = PlannerDay.idFor(publisherPersonId, alignedDayStart),
            publisherPersonId = publisherPersonId,
            dayStart = alignedDayStart,
            createdAt = System.currentTimeMillis(),
        )
        save(base.copy(totalMinutes = newTotal))
    }

    /** Scoped to just this Publisher's own documents — matching
     * firestore.rules' own `resource.data.publisherPersonId ==
     * personIdFromToken()` check on this collection is what makes an
     * unconstrained `list` even *legal* server-side; without this filter,
     * Firestore can't statically prove the query only returns documents the
     * rule allows and rejects the whole listener with PERMISSION_DENIED for
     * every Publisher, on every device, every time — not a per-device bug,
     * a query/rule mismatch that was silently breaking this for everyone. */
    fun startRemoteSync(publisherPersonId: String): Flow<Unit> =
        mirrorFirestoreCollection(
            firestore, offline, appScope, COLLECTION, PlannerDay::class.java,
            query = firestore.collection(COLLECTION).whereEqualTo("publisherPersonId", publisherPersonId),
        ) { it.id }

    /** See [pullFirestoreCollectionOnce]'s doc comment — a one-shot fallback
     * for a device whose live listener can't sustain a connection. */
    suspend fun pullOnce(publisherPersonId: String) = pullFirestoreCollectionOnce(
        firestore, offline, COLLECTION, PlannerDay::class.java,
        query = firestore.collection(COLLECTION).whereEqualTo("publisherPersonId", publisherPersonId),
    ) { it.id }
}
