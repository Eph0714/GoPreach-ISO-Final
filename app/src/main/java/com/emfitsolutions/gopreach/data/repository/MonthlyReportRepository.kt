package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.sync.saveNow
import com.emfitsolutions.gopreach.data.model.MonthlyReport
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.mirrorFirestoreCollection
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import com.emfitsolutions.gopreach.data.model.ReportStatus
import com.emfitsolutions.gopreach.domain.MonthBounds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val COLLECTION = "monthlyReports"

private val SUBMITTED_STATUSES = setOf(ReportStatus.SUBMITTED, ReportStatus.CORRECTED, ReportStatus.POSTED)

/** Spec §5.2 — publisher monthly ministry reports; also the source for the
 * Admin-side Bible-study/hours report views (spec §5.1). */
class MonthlyReportRepository(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    private val appScope: CoroutineScope,
) {
    fun observeAll(): Flow<List<MonthlyReport>> = offline.observeCollection(COLLECTION)

    /** The months (as month-start millis) of this Publisher's reports that are submitted,
     * corrected or posted — My Planner is closed for those months. */
    fun observeSubmittedMonths(publisherPersonId: String): Flow<Map<Long, Double>> = observeAll().map { reports ->
        reports
            .filter { it.publisherPersonId == publisherPersonId && it.status in SUBMITTED_STATUSES }
            .associate { MonthBounds.of(it.periodMonth).startInclusive to (it.hoursRendered ?: 0.0) }
    }

    /** True when the month containing [anyMillis] already has a submitted report. */
    suspend fun isMonthSubmitted(publisherPersonId: String, anyMillis: Long): Boolean =
        MonthBounds.of(anyMillis).startInclusive in observeSubmittedMonths(publisherPersonId).first()

    suspend fun save(report: MonthlyReport): MonthlyReport {
        val id = report.id.ifBlank { reportIdFor(report) }
        val withId = report.copy(id = id)
        offline.save(COLLECTION, id, withId)
        return withId
    }

    /** Bug fix ("the publisher can still edit the report even though it's
     * already posted"): [save] only writes to this device's own local cache
     * and queues the change — this app is "manual/periodic sync only" by
     * design (see [OfflineFirestoreRepository]'s own doc comment), so an
     * elder marking a report Posted on *their* device could sit unsynced
     * indefinitely, never reaching Firestore, and therefore never reaching
     * the Publisher's own device's live listener either — the Publisher's
     * copy of the report just kept showing SUBMITTED, and their Submit
     * button was (correctly, per that stale data) never disabled. Posting/
     * un-posting is exactly the "can't wait for the next manual sync" case
     * [OfflineFirestoreRepository.saveNow] exists for: still cache-first
     * (never blocks on this call succeeding), but also pushes straight to
     * Firestore immediately when online, so the lock actually takes effect
     * right away instead of whenever someone next happens to sync. */
    suspend fun saveNow(report: MonthlyReport): MonthlyReport {
        val id = report.id.ifBlank { reportIdFor(report) }
        val withId = report.copy(id = id)
        offline.saveNow(firestore, COLLECTION, id, withId)
        return withId
    }

    /** One document per publisher and month: a second device (or a repeated request) submitting the
     * same month lands on the same document, so the server can recognise and reject the duplicate
     * instead of storing a second report. Falls back to a random id if either part is missing. */
    private fun reportIdFor(report: MonthlyReport): String {
        if (report.publisherPersonId.isBlank() || report.periodMonth <= 0L) return firestore.collection(COLLECTION).document().id
        val month = java.text.SimpleDateFormat("yyyyMM", java.util.Locale.US).format(java.util.Date(report.periodMonth))
        return "${report.publisherPersonId}_$month"
    }

    suspend fun delete(reportId: String) = offline.delete(COLLECTION, reportId)

    fun startRemoteSync(): Flow<Unit> =
        mirrorFirestoreCollection(firestore, offline, appScope, COLLECTION, MonthlyReport::class.java) { it.id }
}
