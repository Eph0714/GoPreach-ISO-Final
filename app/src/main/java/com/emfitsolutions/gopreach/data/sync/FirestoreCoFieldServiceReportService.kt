package com.emfitsolutions.gopreach.data.sync

import com.emfitsolutions.gopreach.data.model.CoReportStatus
import com.emfitsolutions.gopreach.data.model.FUTURE_MONTH_MESSAGE
import com.emfitsolutions.gopreach.data.model.coReportId
import com.emfitsolutions.gopreach.data.model.isFutureServiceMonth
import com.emfitsolutions.gopreach.data.repository.CircuitResult
import com.emfitsolutions.gopreach.data.repository.CoFieldServiceReportRepository
import com.emfitsolutions.gopreach.data.repository.CoFieldServiceReportService
import com.emfitsolutions.gopreach.data.repository.SubmissionActor
import com.emfitsolutions.gopreach.platform.nowMillis
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Transaction
import kotlinx.coroutines.tasks.await

/** Thrown inside a transaction lambda so none of its writes apply; never escapes this class. */
private class CoReportConflict(message: String) : Exception(message)

/**
 * Firestore implementation of [CoFieldServiceReportService]. The report itself is never copied: these transactions only move
 * the month's small status document (`coFieldServiceMonthStatus`) and append the audit line, each one re-reading the status
 * and checking the move is legal from its current state. The same moves are enforced again by firestore.rules.
 */
class FirestoreCoFieldServiceReportService(
    private val firestore: FirebaseFirestore,
    private val connectivityObserver: ConnectivityObserver,
) : CoFieldServiceReportService {

    private val statuses get() = firestore.collection(CoFieldServiceReportRepository.STATUSES)
    private val events get() = firestore.collection(CoFieldServiceReportRepository.EVENTS)

    private suspend fun run(block: suspend () -> Unit): CircuitResult {
        if (!connectivityObserver.isOnline()) return CircuitResult.Offline()
        return try {
            block()
            CircuitResult.Success
        } catch (e: Exception) {
            val conflict = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<CoReportConflict>().firstOrNull()
            if (conflict != null) CircuitResult.Conflict(conflict.message ?: "That report changed. Reload and try again.")
            else CircuitResult.Error(e.message ?: "Couldn't save. Please try again.")
        }
    }

    private fun statusOf(snap: DocumentSnapshot): CoReportStatus =
        snap.getString("status")?.let { runCatching { CoReportStatus.valueOf(it) }.getOrNull() } ?: CoReportStatus.NOT_SUBMITTED

    private fun Transaction.audit(
        reportId: String, congregationId: String, periodMonth: Long, actor: SubmissionActor,
        action: String, from: CoReportStatus, to: CoReportStatus, version: Int, remarks: String?, previousRemarks: String?,
    ) {
        set(
            events.document(),
            mapOf(
                "reportId" to reportId, "congregationId" to congregationId, "periodMonth" to periodMonth,
                "at" to nowMillis(), "userId" to actor.personId, "userName" to actor.name, "userRole" to actor.role, "action" to action,
                "fromStatus" to from.name, "toStatus" to to.name, "version" to version, "remarks" to remarks, "previousRemarks" to previousRemarks,
            ),
        )
    }

    private val statistics get() = firestore.collection(com.emfitsolutions.gopreach.data.repository.MeetingAttendanceRepository.STATISTICS)

    private fun statsMap(s: com.emfitsolutions.gopreach.data.model.CongregationMonthlyStatistics): Map<String, Any?> = mapOf(
        "congregationId" to s.congregationId, "serviceMonth" to s.serviceMonth,
        "fieldServiceReportCount" to s.fieldServiceReportCount, "elderCount" to s.elderCount, "ministerialServantCount" to s.ministerialServantCount,
        "publisherCount" to s.publisherCount, "auxiliaryPioneerCount" to s.auxiliaryPioneerCount, "regularPioneerCount" to s.regularPioneerCount,
        "unbaptizedPublisherCount" to s.unbaptizedPublisherCount,
        "averageMidweekAttendance" to s.averageMidweekAttendance, "averageWeekendAttendance" to s.averageWeekendAttendance,
        "midweekMeetingsRecorded" to s.midweekMeetingsRecorded, "weekendMeetingsRecorded" to s.weekendMeetingsRecorded,
        "midweekMeetingsMissing" to s.midweekMeetingsMissing, "weekendMeetingsMissing" to s.weekendMeetingsMissing,
        "attendanceRoundingMode" to s.attendanceRoundingMode.name, "sourceReportId" to s.sourceReportId, "snapshotStatus" to s.snapshotStatus,
        "submittedDate" to s.submittedDate, "receivedDate" to s.receivedDate,
        "createdBy" to s.createdBy, "createdAt" to s.createdAt, "updatedAt" to s.updatedAt,
    )

    override suspend fun submit(
        congregationId: String, periodMonth: Long, actor: SubmissionActor,
        statistics: com.emfitsolutions.gopreach.data.model.CongregationMonthlyStatistics?,
    ): CircuitResult {
        // A service month that has not started yet can never be sent — checked for every role (the rules check it again).
        if (isFutureServiceMonth(periodMonth, nowMillis())) return CircuitResult.Conflict(FUTURE_MONTH_MESSAGE)
        val id = coReportId(congregationId, periodMonth)
        return run {
            val ref = statuses.document(id)
            firestore.runTransaction { txn ->
                val snap = txn.get(ref)
                val from = statusOf(snap)
                val now = nowMillis()
                val version: Int
                if (!snap.exists()) {
                    version = 1
                    txn.set(
                        ref,
                        mapOf(
                            "congregationId" to congregationId, "periodMonth" to periodMonth, "status" to CoReportStatus.SUBMITTED.name,
                            "version" to 1, "submittedAt" to now, "submittedByPersonId" to actor.personId, "submittedByName" to actor.name,
                            "updatedAt" to now,
                        ),
                    )
                } else {
                    if (!from.canSend) throw CoReportConflict("This month is already ${from.label.lowercase()} — it cannot be submitted again.")
                    version = (snap.getLong("version") ?: 1L).toInt() + 1
                    txn.update(
                        ref,
                        mapOf(
                            "status" to CoReportStatus.SUBMITTED.name, "version" to version, "submittedAt" to now,
                            "submittedByPersonId" to actor.personId, "submittedByName" to actor.name, "updatedAt" to now,
                        ),
                    )
                }
                txn.audit(
                    id, congregationId, periodMonth, actor,
                    if (from == CoReportStatus.RETURNED) "Corrected report submitted" else "Report submitted to the Circuit Overseer",
                    from, CoReportStatus.SUBMITTED, version, null, null,
                )
                // The month's historical statistics, written together with the submission (the rules tie them to the report's status).
                if (statistics != null) txn.set(this.statistics.document(id), statsMap(statistics))
                null
            }.await()
        }
    }

    override suspend fun undoSubmission(congregationId: String, periodMonth: Long, actor: SubmissionActor): CircuitResult {
        val id = coReportId(congregationId, periodMonth)
        return run {
            val ref = statuses.document(id)
            firestore.runTransaction { txn ->
                val snap = txn.get(ref)
                if (!snap.exists()) throw CoReportConflict("This month has not been submitted.")
                val from = statusOf(snap)
                if (!from.canUndo) {
                    throw CoReportConflict(
                        if (from == CoReportStatus.RECEIVED) "The Circuit Overseer has already received this report. Only the Circuit Overseer can return it."
                        else "This month is ${from.label.lowercase()} — there is no submission to undo.",
                    )
                }
                val statsRef = statistics.document(id)
                val statsSnap = txn.get(statsRef)
                val now = nowMillis()
                val previous = snap.getString("coRemarks")
                if (statsSnap.exists()) txn.update(statsRef, mapOf("snapshotStatus" to CoReportStatus.NOT_SUBMITTED.name, "updatedAt" to now))
                // The active remarks are cleared; they stay in the audit history (previousRemarks).
                txn.update(
                    ref,
                    mapOf(
                        "status" to CoReportStatus.NOT_SUBMITTED.name, "coRemarks" to null, "coRemarksAt" to null, "coRemarksByName" to null,
                        "undoneAt" to now, "undoneByPersonId" to actor.personId, "undoneByName" to actor.name, "updatedAt" to now,
                    ),
                )
                txn.audit(id, congregationId, periodMonth, actor, "Report sending undone", from, CoReportStatus.NOT_SUBMITTED, (snap.getLong("version") ?: 1L).toInt(), null, previous)
                null
            }.await()
        }
    }

    /** Shared by receive / return / remarks: re-read the status, check [allowedFrom], write [fields], append the audit line. */
    private fun review(
        reportId: String, actor: SubmissionActor, action: String, to: CoReportStatus?, allowedFrom: Set<CoReportStatus>,
        fields: (now: Long) -> Map<String, Any?>, remarks: String?,
    ): suspend () -> Unit = {
        val ref = statuses.document(reportId)
        firestore.runTransaction { txn ->
            val snap = txn.get(ref)
            val statsRef = statistics.document(reportId)
            val statsSnap = txn.get(statsRef)
            if (!snap.exists()) throw CoReportConflict("This month has not been submitted.")
            val from = statusOf(snap)
            if (from !in allowedFrom) throw CoReportConflict("This month is ${from.label.lowercase()} — that action isn't available now.")
            val now = nowMillis()
            val target = to ?: from
            txn.update(ref, fields(now) + mapOf("status" to target.name, "updatedAt" to now))
            // The historical statistics follow the report: Received freezes them, Returned opens them again.
            if (to != null && statsSnap.exists()) {
                txn.update(
                    statsRef,
                    mapOf(
                        "snapshotStatus" to target.name, "receivedDate" to (if (target == CoReportStatus.RECEIVED) now else null), "updatedAt" to now,
                    ),
                )
            }
            txn.audit(
                reportId, snap.getString("congregationId").orEmpty(), snap.getLong("periodMonth") ?: 0L, actor, action, from, target,
                (snap.getLong("version") ?: 1L).toInt(), remarks, snap.getString("coRemarks"),
            )
            null
        }.await()
    }

    override suspend fun receive(reportId: String, remarks: String?, actor: SubmissionActor): CircuitResult {
        val note = remarks?.trim()?.takeIf { it.isNotEmpty() }
        return run(
            review(
                reportId, actor, "Report received by the Circuit Overseer", CoReportStatus.RECEIVED, setOf(CoReportStatus.SUBMITTED),
                { now ->
                    buildMap {
                        put("receivedAt", now); put("receivedByPersonId", actor.personId); put("receivedByName", actor.name)
                        if (note != null) { put("coRemarks", note); put("coRemarksAt", now); put("coRemarksByName", actor.name) }
                    }
                },
                note,
            ),
        )
    }

    override suspend fun returnForCorrection(reportId: String, reason: String, actor: SubmissionActor): CircuitResult {
        val why = reason.trim()
        if (why.isEmpty()) return CircuitResult.Conflict("Please enter the CO Remarks explaining what needs to be corrected.")
        return run(
            review(
                reportId, actor, "Report returned for correction", CoReportStatus.RETURNED, setOf(CoReportStatus.SUBMITTED, CoReportStatus.RECEIVED),
                { now ->
                    mapOf(
                        "returnedAt" to now, "returnedByPersonId" to actor.personId, "returnedByName" to actor.name, "returnReason" to why,
                        "coRemarks" to why, "coRemarksAt" to now, "coRemarksByName" to actor.name,
                    )
                },
                why,
            ),
        )
    }

    override suspend fun saveRemarks(reportId: String, remarks: String, actor: SubmissionActor): CircuitResult {
        val note = remarks.trim().ifEmpty { null }
        return run(
            review(
                reportId, actor, "CO remarks updated", null, setOf(CoReportStatus.SUBMITTED, CoReportStatus.RECEIVED, CoReportStatus.RETURNED),
                { now -> mapOf("coRemarks" to note, "coRemarksAt" to now, "coRemarksByName" to actor.name) },
                note,
            ),
        )
    }
}
