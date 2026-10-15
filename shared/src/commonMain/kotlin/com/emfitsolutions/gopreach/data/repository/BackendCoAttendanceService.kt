package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.CoAttendanceSnapshot
import com.emfitsolutions.gopreach.data.model.CoMonthStatus
import com.emfitsolutions.gopreach.data.model.CoReportEvent
import com.emfitsolutions.gopreach.data.model.CoReportStatus
import com.emfitsolutions.gopreach.data.model.FUTURE_MONTH_MESSAGE
import com.emfitsolutions.gopreach.data.model.coReportId
import com.emfitsolutions.gopreach.data.model.isFutureServiceMonth
import com.emfitsolutions.gopreach.data.remote.SyncApi
import com.emfitsolutions.gopreach.data.sync.ConnectivityObserver
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import com.emfitsolutions.gopreach.platform.nowMillis

/**
 * The Meeting Attendance workflow for the Hostinger backend: the same moves as [BackendCoFieldServiceReportService] (send, undo, receive,
 * return, cancel), each one pushed to the server in a single request that re-checks the role, the month's current state and the lock.
 * The month's status is always the first document of a request, because the server accepts a snapshot only for a status already saved.
 */
class BackendCoAttendanceService(
    api: SyncApi,
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
    connectivity: ConnectivityObserver,
) : CoAttendanceService {
    private val writer = ServerWriter(api, connectivity)
    private val statuses = CoAttendanceRepository.STATUSES
    private val snapshots = CoAttendanceRepository.SNAPSHOTS
    private val events = CoAttendanceRepository.EVENTS

    private fun event(id: String, s: CoMonthStatus, actor: SubmissionActor, action: String, from: CoReportStatus, to: CoReportStatus, remarks: String?, previous: String?) =
        writer.op(
            events, remote.newId(events),
            CoReportEvent(
                reportId = id, congregationId = s.congregationId, periodMonth = s.periodMonth, at = nowMillis(), userId = actor.personId, userName = actor.name,
                userRole = actor.role, action = action, fromStatus = from.name, toStatus = to.name, version = s.version, remarks = remarks, previousRemarks = previous,
            ),
        )

    override suspend fun submit(congregationId: String, periodMonth: Long, actor: SubmissionActor, snapshot: CoAttendanceSnapshot, expectedVersion: Int?): CircuitResult {
        if (isFutureServiceMonth(periodMonth, nowMillis())) return CircuitResult.Conflict(FUTURE_MONTH_MESSAGE)
        val id = coReportId(congregationId, periodMonth)
        val now = nowMillis()
        val existing = offline.get<CoMonthStatus>(statuses, id)
        val from = existing?.status ?: CoReportStatus.NOT_SUBMITTED
        if (expectedVersion != null && (existing?.version ?: 0) != expectedVersion) {
            return CircuitResult.Conflict("This month was already sent as version ${existing?.version ?: 0} - nothing was sent again. Refresh and check its status.")
        }
        if (existing != null && !(from.canSend || from.canSendUpdate)) return CircuitResult.Conflict("This month is already ${from.label.lowercase()} — it cannot be submitted again.")
        val next = (existing ?: CoMonthStatus(id = id, congregationId = congregationId, periodMonth = periodMonth)).copy(
            status = CoReportStatus.SUBMITTED, version = (existing?.version ?: 0) + 1, submittedAt = now, submittedByPersonId = actor.personId, submittedByName = actor.name, updatedAt = now,
        )
        val copyId = "${id}_${next.version}"
        val ops = listOf(
            writer.op(statuses, id, next),
            writer.op(
                snapshots, copyId,
                snapshot.copy(id = copyId, congregationId = congregationId, periodMonth = periodMonth, version = next.version, submittedAt = now, submittedByPersonId = actor.personId, submittedByName = actor.name),
            ),
            event(
                id, next, actor,
                when (from) {
                    CoReportStatus.RETURNED -> "Corrected attendance submitted (version ${next.version})"
                    CoReportStatus.SUBMITTED -> "Updated attendance snapshot sent while awaiting receipt (version ${next.version})"
                    CoReportStatus.CANCELLED -> "Attendance sent again after cancellation (version ${next.version})"
                    else -> "Attendance submitted to the Circuit Overseer (version ${next.version})"
                },
                from, CoReportStatus.SUBMITTED, null, null,
            ),
        )
        val (outcome, results) = writer.pushChecked(ops)
        if (outcome is CircuitResult.Success) { offline.cacheFromServer(statuses, id, next); return outcome }
        if (results == null || results.getOrNull(0)?.isOk != true) return outcome // nothing was committed (the status is the first op)

        // The status was accepted but another part was not: retry those once; a month must never be left "sent" without its frozen copy.
        val failed = ops.indices.filter { results.getOrNull(it)?.isOk != true }
        val retry = writer.pushChecked(failed.map { ops[it] }).second
        val stillFailed = failed.filterIndexed { n, _ -> retry?.getOrNull(n)?.isOk != true }
        if (stillFailed.isEmpty()) { offline.cacheFromServer(statuses, id, next); return CircuitResult.Success }
        if (stillFailed.any { ops[it].collection == snapshots && ops[it].id == copyId }) {
            offline.cacheFromServer(statuses, id, next)
            undoSubmission(congregationId, periodMonth, actor)
            return CircuitResult.Conflict("The attendance snapshot could not be saved on the server, so this month was NOT sent. Nothing changed - please check your connection and send it again.")
        }
        offline.cacheFromServer(statuses, id, next)
        return outcome
    }

    override suspend fun undoSubmission(congregationId: String, periodMonth: Long, actor: SubmissionActor): CircuitResult {
        val id = coReportId(congregationId, periodMonth)
        val s = offline.get<CoMonthStatus>(statuses, id) ?: return CircuitResult.Conflict("This month has not been submitted.")
        if (!s.status.canUndo) return CircuitResult.Conflict(
            if (s.status == CoReportStatus.RECEIVED) "The Circuit Overseer has already received this report. Only the Circuit Overseer can return it."
            else "This month is ${s.status.label.lowercase()} — there is no submission to undo.",
        )
        val now = nowMillis()
        val next = s.copy(
            status = CoReportStatus.NOT_SUBMITTED, coRemarks = null, coRemarksAt = null, coRemarksByName = null,
            undoneAt = now, undoneByPersonId = actor.personId, undoneByName = actor.name, updatedAt = now,
        )
        val ops = listOf(
            writer.op(statuses, id, next),
            event(id, s, actor, "Attendance sending undone", s.status, CoReportStatus.NOT_SUBMITTED, null, s.coRemarks),
        )
        return writer.push(ops).also { if (it is CircuitResult.Success) offline.cacheFromServer(statuses, id, next) }
    }

    private suspend fun review(
        reportId: String, actor: SubmissionActor, action: String, to: CoReportStatus, allowedFrom: Set<CoReportStatus>,
        remarks: String?, expectedVersion: Int?, change: (CoMonthStatus, Long) -> CoMonthStatus,
    ): CircuitResult {
        val s = offline.get<CoMonthStatus>(statuses, reportId) ?: return CircuitResult.Conflict("This month has not been submitted.")
        if (expectedVersion != null && s.version != expectedVersion) {
            return CircuitResult.Conflict("A newer version (version ${s.version}) of this report has been sent since you opened version $expectedVersion. Review the new version first.")
        }
        if (s.status !in allowedFrom) return CircuitResult.Conflict("This month is ${s.status.label.lowercase()} — that action isn't available now.")
        val now = nowMillis()
        val next = change(s, now).copy(status = to, updatedAt = now)
        val ops = listOf(writer.op(statuses, reportId, next), event(reportId, s, actor, action, s.status, to, remarks, s.coRemarks))
        return writer.push(ops).also { if (it is CircuitResult.Success) offline.cacheFromServer(statuses, reportId, next) }
    }

    override suspend fun receive(reportId: String, remarks: String?, actor: SubmissionActor, expectedVersion: Int?): CircuitResult {
        val note = remarks?.trim()?.takeIf { it.isNotEmpty() }
        return review(reportId, actor, "Attendance received by the Circuit Overseer", CoReportStatus.RECEIVED, setOf(CoReportStatus.SUBMITTED), note, expectedVersion) { s, now ->
            s.copy(receivedAt = now, receivedByPersonId = actor.personId, receivedByName = actor.name).let {
                if (note != null) it.copy(coRemarks = note, coRemarksAt = now, coRemarksByName = actor.name) else it
            }
        }
    }

    override suspend fun returnForCorrection(reportId: String, reason: String, actor: SubmissionActor, expectedVersion: Int?): CircuitResult {
        val why = reason.trim()
        if (why.isEmpty()) return CircuitResult.Conflict("Please enter the reason explaining what needs to be corrected.")
        return review(reportId, actor, "Attendance returned to the congregation", CoReportStatus.RETURNED, setOf(CoReportStatus.SUBMITTED, CoReportStatus.RECEIVED), why, expectedVersion) { s, now ->
            s.copy(returnedAt = now, returnedByPersonId = actor.personId, returnedByName = actor.name, returnReason = why, coRemarks = why, coRemarksAt = now, coRemarksByName = actor.name)
        }
    }

    override suspend fun cancelSubmission(reportId: String, reason: String, actor: SubmissionActor, expectedVersion: Int?): CircuitResult {
        val why = reason.trim()
        if (why.isEmpty()) return CircuitResult.Conflict("Please enter the reason for cancelling this submission.")
        val current = offline.get<CoMonthStatus>(statuses, reportId)
        return review(reportId, actor, "Attendance submission cancelled by the Circuit Overseer", CoReportStatus.CANCELLED, setOf(CoReportStatus.SUBMITTED, CoReportStatus.RECEIVED), why, expectedVersion) { s, now ->
            s.copy(cancelledAt = now, cancelledByPersonId = actor.personId, cancelledByName = actor.name, cancelReason = why, cancelledFromStatus = (current ?: s).status.name)
        }
    }
}
