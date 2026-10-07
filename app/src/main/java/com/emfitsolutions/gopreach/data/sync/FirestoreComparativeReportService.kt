package com.emfitsolutions.gopreach.data.sync

import com.emfitsolutions.gopreach.data.model.COMPARATIVE_DUPLICATE_MESSAGE
import com.emfitsolutions.gopreach.data.model.COMPARATIVE_RECEIVED_MESSAGE
import com.emfitsolutions.gopreach.data.model.COMPARATIVE_SUBMITTED_MESSAGE
import com.emfitsolutions.gopreach.data.model.ComparativeReport
import com.emfitsolutions.gopreach.data.model.ComparativeStatus
import com.emfitsolutions.gopreach.data.repository.CircuitResult
import com.emfitsolutions.gopreach.data.repository.ComparativeReportRepository
import com.emfitsolutions.gopreach.data.repository.ComparativeReportService
import com.emfitsolutions.gopreach.data.repository.SubmissionActor
import com.emfitsolutions.gopreach.platform.nowMillis
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Transaction
import kotlinx.coroutines.tasks.await

/** Thrown inside a transaction lambda so none of its writes apply; never escapes this class. */
private class ComparativeConflict(message: String) : Exception(message)

/**
 * Firestore implementation of [ComparativeReportService]. Each move is a transaction that re-reads the report, checks the move is
 * legal from its current server state (a Received report is refused with the locked message), and writes the report and its
 * history line together. firestore.rules enforces the same moves again.
 */
class FirestoreComparativeReportService(
    private val firestore: FirebaseFirestore,
    private val connectivityObserver: ConnectivityObserver,
) : ComparativeReportService {

    private val reports get() = firestore.collection(ComparativeReportRepository.REPORTS)
    private val history get() = firestore.collection(ComparativeReportRepository.HISTORY)
    private val remarks get() = firestore.collection(ComparativeReportRepository.REMARKS)

    private suspend fun run(block: suspend () -> Unit): CircuitResult {
        if (!connectivityObserver.isOnline()) return CircuitResult.Offline()
        return try {
            block()
            CircuitResult.Success
        } catch (e: Exception) {
            val conflict = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<ComparativeConflict>().firstOrNull()
            if (conflict != null) CircuitResult.Conflict(conflict.message ?: "That report changed. Reload and try again.")
            else CircuitResult.Error(e.message ?: "Couldn't save. Please try again.")
        }
    }

    private fun statusOf(snap: DocumentSnapshot): ComparativeStatus? =
        snap.getString("status")?.let { runCatching { ComparativeStatus.valueOf(it) }.getOrNull() }

    private fun lockedMessage(s: ComparativeStatus) = if (s == ComparativeStatus.RECEIVED) COMPARATIVE_RECEIVED_MESSAGE else COMPARATIVE_SUBMITTED_MESSAGE

    private fun Transaction.audit(
        reportId: String, congregationId: String, actor: SubmissionActor, action: String,
        from: ComparativeStatus?, to: ComparativeStatus?, version: Int, remarks: String? = null,
    ) {
        set(
            history.document(),
            mapOf(
                "reportId" to reportId, "congregationId" to congregationId, "action" to action,
                "fromStatus" to from?.name, "toStatus" to to?.name, "version" to version,
                "userId" to actor.personId, "userName" to actor.name, "userRole" to actor.role, "at" to nowMillis(), "remarks" to remarks,
            ),
        )
    }

    private fun fullMap(r: ComparativeReport, now: Long, actor: SubmissionActor): Map<String, Any?> = mapOf(
        "congregationId" to r.congregationId, "reportNumber" to r.reportNumber,
        "periodAStart" to r.periodAStart, "periodAEnd" to r.periodAEnd, "periodBStart" to r.periodBStart, "periodBEnd" to r.periodBEnd,
        "status" to ComparativeStatus.SUBMITTED.name, "version" to 1, "reportSnapshot" to r.reportSnapshot, "sourceSnapshotIds" to r.sourceSnapshotIds,
        "attendanceRoundingMode" to r.attendanceRoundingMode, "createdBy" to r.createdBy, "createdByName" to r.createdByName, "createdAt" to r.createdAt,
        "submittedBy" to actor.personId, "submittedByName" to actor.name, "submittedByRole" to actor.role, "submittedAt" to now, "updatedAt" to now,
    )

    override suspend fun submit(report: ComparativeReport, actor: SubmissionActor): CircuitResult = run {
        val ref = reports.document(report.id)
        firestore.runTransaction { txn ->
            val snap = txn.get(ref)
            val now = nowMillis()
            if (!snap.exists()) {
                txn.set(ref, fullMap(report, now, actor))
                txn.audit(report.id, report.congregationId, actor, "Submitted to the Circuit Overseer", ComparativeStatus.DRAFT, ComparativeStatus.SUBMITTED, 1)
            } else {
                val from = statusOf(snap) ?: throw ComparativeConflict("This report could not be read.")
                if (!from.congregationCanEdit) throw ComparativeConflict(lockedMessage(from))
                val version = (snap.getLong("version") ?: 1L).toInt() + if (from == ComparativeStatus.RETURNED) 1 else 0
                txn.update(
                    ref,
                    mapOf(
                        "status" to ComparativeStatus.SUBMITTED.name, "version" to version, "reportSnapshot" to report.reportSnapshot,
                        "sourceSnapshotIds" to report.sourceSnapshotIds, "attendanceRoundingMode" to report.attendanceRoundingMode,
                        "submittedBy" to actor.personId, "submittedByName" to actor.name, "submittedByRole" to actor.role, "submittedAt" to now, "updatedAt" to now,
                    ),
                )
                txn.audit(
                    report.id, report.congregationId, actor,
                    if (from == ComparativeStatus.RETURNED) "Corrected report resubmitted" else "Submitted to the Circuit Overseer",
                    from, ComparativeStatus.SUBMITTED, version,
                )
            }
            null
        }.await()
    }

    override suspend fun receive(reportId: String, actor: SubmissionActor): CircuitResult = run {
        val ref = reports.document(reportId)
        firestore.runTransaction { txn ->
            val snap = txn.get(ref)
            val from = statusOf(snap) ?: throw ComparativeConflict("This report no longer exists.")
            if (from == ComparativeStatus.RECEIVED) throw ComparativeConflict("This Comparative Report was already received.")
            if (from != ComparativeStatus.SUBMITTED) throw ComparativeConflict("Only a submitted report can be received. It is now ${from.label.lowercase()}.")
            val now = nowMillis()
            txn.update(ref, mapOf("status" to ComparativeStatus.RECEIVED.name, "receivedBy" to actor.personId, "receivedByName" to actor.name, "receivedAt" to now, "updatedAt" to now))
            txn.audit(reportId, snap.getString("congregationId").orEmpty(), actor, "Received by the Circuit Overseer", from, ComparativeStatus.RECEIVED, (snap.getLong("version") ?: 1L).toInt())
            null
        }.await()
    }

    override suspend fun returnForCorrection(reportId: String, reason: String, actor: SubmissionActor): CircuitResult {
        if (reason.isBlank()) return CircuitResult.Conflict("Enter the reason for returning this report.")
        return run {
            val ref = reports.document(reportId)
            firestore.runTransaction { txn ->
                val snap = txn.get(ref)
                val from = statusOf(snap) ?: throw ComparativeConflict("This report no longer exists.")
                if (from != ComparativeStatus.SUBMITTED) throw ComparativeConflict("Only a submitted report can be returned. It is now ${from.label.lowercase()}.")
                val now = nowMillis()
                txn.update(
                    ref,
                    mapOf(
                        "status" to ComparativeStatus.RETURNED.name, "returnedBy" to actor.personId, "returnedByName" to actor.name, "returnedAt" to now,
                        "returnReason" to reason.trim(), "currentCoRemarks" to reason.trim(), "updatedAt" to now,
                    ),
                )
                txn.audit(reportId, snap.getString("congregationId").orEmpty(), actor, "Returned for correction", from, ComparativeStatus.RETURNED, (snap.getLong("version") ?: 1L).toInt(), reason.trim())
                null
            }.await()
        }
    }

    override suspend fun addRemark(reportId: String, remark: String, actor: SubmissionActor): CircuitResult {
        if (remark.isBlank()) return CircuitResult.Conflict("Enter a remark first.")
        return run {
            val ref = reports.document(reportId)
            firestore.runTransaction { txn ->
                val snap = txn.get(ref)
                val from = statusOf(snap) ?: throw ComparativeConflict("This report no longer exists.")
                if (from != ComparativeStatus.SUBMITTED && from != ComparativeStatus.RETURNED) throw ComparativeConflict(lockedMessage(from))
                val now = nowMillis()
                val cong = snap.getString("congregationId").orEmpty()
                txn.update(ref, mapOf("currentCoRemarks" to remark.trim(), "updatedAt" to now))
                txn.set(
                    remarks.document(),
                    mapOf(
                        "comparativeReportId" to reportId, "congregationId" to cong, "authorUserId" to actor.personId, "authorName" to actor.name,
                        "authorRole" to actor.role, "remark" to remark.trim(), "createdAt" to now,
                    ),
                )
                txn.audit(reportId, cong, actor, "CO remarks added", from, from, (snap.getLong("version") ?: 1L).toInt(), remark.trim())
                null
            }.await()
        }
    }
}
