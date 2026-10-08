package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.CoMonthStatus
import com.emfitsolutions.gopreach.data.model.CoReportEvent
import com.emfitsolutions.gopreach.data.model.MonthLockedException
import com.emfitsolutions.gopreach.data.model.isMonthLocked
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import com.emfitsolutions.gopreach.domain.MonthBounds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** Who is acting — recorded on every audit line. [role] is the role name shown in the history. */
data class SubmissionActor(val personId: String, val name: String, val role: String = "")

/**
 * Every move of a congregation's Field Service Report month — each one server transaction that re-reads the status, checks the
 * move is legal from its *current* state, and writes the status and the audit line together. firestore.rules enforces the same
 * roles and moves independently (and locks the month's records while it is Submitted / Received).
 */
interface CoFieldServiceReportService {
    /**
     * NOT_SUBMITTED / RETURNED → SUBMITTED. Refused for a service month that has not started. [statistics] is the month's historical
     * statistics snapshot, written in the same transaction (created, or refreshed after a return).
     */
    suspend fun submit(
        congregationId: String, periodMonth: Long, actor: SubmissionActor,
        statistics: com.emfitsolutions.gopreach.data.model.CongregationMonthlyStatistics? = null,
        /** The frozen copy of the sent report the Circuit Overseer will read; the service stamps its id and send number. */
        received: com.emfitsolutions.gopreach.data.model.CoReceivedReport? = null,
    ): CircuitResult

    /** SUBMITTED → NOT_SUBMITTED, before the overseer has received it. */
    suspend fun undoSubmission(congregationId: String, periodMonth: Long, actor: SubmissionActor): CircuitResult

    /** Circuit Overseer: SUBMITTED → RECEIVED, with optional remarks. */
    suspend fun receive(reportId: String, remarks: String?, actor: SubmissionActor): CircuitResult

    /** Circuit Overseer: SUBMITTED / RECEIVED → RETURNED. The [reason] is also kept as the month's remarks. */
    suspend fun returnForCorrection(reportId: String, reason: String, actor: SubmissionActor): CircuitResult

    /** Circuit Overseer: add or change the remarks of a submitted / received / returned month. */
    suspend fun saveRemarks(reportId: String, remarks: String, actor: SubmissionActor): CircuitResult
}

class CoFieldServiceReportRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    fun observeStatuses(): Flow<List<CoMonthStatus>> = offline.observeCollection(STATUSES)
    fun observeStatus(reportId: String): Flow<CoMonthStatus?> = observeStatuses().map { list -> list.firstOrNull { it.id == reportId } }
    fun observeEvents(): Flow<List<CoReportEvent>> = offline.observeCollection<CoReportEvent>(EVENTS).map { it.sortedBy { e -> e.at } }

    /** Status documents: every signed-in account (they hold no report figures). */
    fun startStatusSync(): Flow<Unit> = remote.mirror(STATUSES, CoMonthStatus::class) { it.id }

    /** The audit trail: the Super-Admin (a Circuit Overseer's is mirrored per congregation). */
    fun startRemoteSync(): Flow<Unit> = merge(remote.mirror(EVENTS, CoReportEvent::class) { it.id })

    companion object {
        const val STATUSES = "coFieldServiceMonthStatus"
        const val EVENTS = "coFieldServiceReportEvents"
    }
}

/**
 * Client-side companion of the server's month lock: before a record of a service month is written here it checks the month
 * is not Submitted / Received. The server rules refuse such a write regardless; this keeps an offline device from queueing a
 * change that could never synchronize, and lets the screens explain why.
 */
class MonthLockGuard(
    private val reports: CoFieldServiceReportRepository,
    private val people: PersonRepository,
) {
    /** [dayMillis] is any instant inside the month. */
    suspend fun requireOpen(congregationId: String?, dayMillis: Long) {
        if (congregationId.isNullOrBlank() || dayMillis <= 0L) return
        val month = MonthBounds.of(dayMillis).startInclusive
        if (isMonthLocked(reports.observeStatuses().first(), congregationId, month)) throw MonthLockedException()
    }

    /** For records that carry no congregation of their own: the publisher's current one is used. */
    suspend fun requireOpenForPublisher(publisherPersonId: String, dayMillis: Long) {
        if (publisherPersonId.isBlank()) return
        requireOpen(people.get(publisherPersonId)?.activeCongregationId, dayMillis)
    }
}
