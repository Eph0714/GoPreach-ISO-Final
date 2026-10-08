package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.json.DocJson
import com.emfitsolutions.gopreach.data.json.encode
import com.emfitsolutions.gopreach.data.model.COMPARATIVE_RECEIVED_MESSAGE
import com.emfitsolutions.gopreach.data.model.COMPARATIVE_SUBMITTED_MESSAGE
import com.emfitsolutions.gopreach.data.model.CoMonthStatus
import com.emfitsolutions.gopreach.data.model.CoReportEvent
import com.emfitsolutions.gopreach.data.model.CoReportStatus
import com.emfitsolutions.gopreach.data.model.ComparativeReport
import com.emfitsolutions.gopreach.data.model.ComparativeReportHistory
import com.emfitsolutions.gopreach.data.model.ComparativeReportRemark
import com.emfitsolutions.gopreach.data.model.ComparativeStatus
import com.emfitsolutions.gopreach.data.model.CongregationMonthlyStatistics
import com.emfitsolutions.gopreach.data.model.FUTURE_MONTH_MESSAGE
import com.emfitsolutions.gopreach.data.model.coReportId
import com.emfitsolutions.gopreach.data.model.isFutureServiceMonth
import com.emfitsolutions.gopreach.data.remote.PushOp
import com.emfitsolutions.gopreach.data.remote.SyncApi
import com.emfitsolutions.gopreach.data.sync.ConnectivityObserver
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import com.emfitsolutions.gopreach.platform.nowMillis
import kotlinx.serialization.json.JsonObject

/**
 * The report-workflow moves for the Hostinger backend. There are no database transactions there, so each move reads the current
 * document from the device (kept current by the pull sync), builds the next version, and pushes the changed documents together in
 * one request. The SERVER then re-checks every document with its port of firestore.rules (who may move which status, locked months,
 * immutable periods), so a stale device can never make an illegal move: the server answers "denied" and nothing is applied.
 */
internal class ServerWriter(
    private val api: SyncApi,
    private val connectivity: ConnectivityObserver,
) {
    class Op(val collection: String, val id: String, val json: JsonObject?, val delete: Boolean = false)

    fun deleteOp(collection: String, id: String) = Op(collection, id, null, delete = true)

    inline fun <reified T> op(collection: String, id: String, value: T) =
        Op(collection, id, DocJson.parseToJsonElement(DocJson.encode(value)) as JsonObject)

    /** Pushes [ops] in order; null = all accepted, otherwise the message to show. */
    suspend fun push(ops: List<Op>): CircuitResult {
        if (!connectivity.isOnline()) return CircuitResult.Offline()
        return try {
            val results = api.push(ops.map { PushOp(it.collection, it.id, if (it.delete) "delete" else "set", it.json) })
            val bad = results.firstOrNull { !it.isOk }
            when {
                bad == null -> CircuitResult.Success
                bad.status == "denied" -> CircuitResult.Conflict(bad.reason?.takeIf { it.isNotBlank() } ?: "The server did not allow that change. Reload and try again.")
                else -> CircuitResult.Conflict(bad.reason?.takeIf { it.isNotBlank() } ?: "The server could not accept that change.")
            }
        } catch (e: Exception) {
            CircuitResult.Error(e.message ?: "Couldn't reach the server. Please try again.")
        }
    }
}

class BackendCoFieldServiceReportService(
    api: SyncApi,
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
    connectivity: ConnectivityObserver,
) : CoFieldServiceReportService {
    private val writer = ServerWriter(api, connectivity)
    private val statuses = CoFieldServiceReportRepository.STATUSES
    private val events = CoFieldServiceReportRepository.EVENTS
    private val stats = MeetingAttendanceRepository.STATISTICS

    private fun event(id: String, s: CoMonthStatus, actor: SubmissionActor, action: String, from: CoReportStatus, to: CoReportStatus, remarks: String?, previous: String?) =
        writer.op(
            events, remote.newId(events),
            CoReportEvent(
                reportId = id, congregationId = s.congregationId, periodMonth = s.periodMonth, at = nowMillis(), userId = actor.personId, userName = actor.name,
                userRole = actor.role, action = action, fromStatus = from.name, toStatus = to.name, version = s.version, remarks = remarks, previousRemarks = previous,
            ),
        )

    private suspend fun statsDoc(id: String) = offline.get<CongregationMonthlyStatistics>(stats, id)

    override suspend fun submit(congregationId: String, periodMonth: Long, actor: SubmissionActor, statistics: CongregationMonthlyStatistics?): CircuitResult {
        if (isFutureServiceMonth(periodMonth, nowMillis())) return CircuitResult.Conflict(FUTURE_MONTH_MESSAGE)
        val id = coReportId(congregationId, periodMonth)
        val now = nowMillis()
        val existing = offline.get<CoMonthStatus>(statuses, id)
        val from = existing?.status ?: CoReportStatus.NOT_SUBMITTED
        if (existing != null && !from.canSend) return CircuitResult.Conflict("This month is already ${from.label.lowercase()} — it cannot be submitted again.")
        val next = (existing ?: CoMonthStatus(id = id, congregationId = congregationId, periodMonth = periodMonth)).copy(
            status = CoReportStatus.SUBMITTED, version = (existing?.version ?: 0) + 1, submittedAt = now, submittedByPersonId = actor.personId, submittedByName = actor.name, updatedAt = now,
        )
        val ops = buildList {
            add(writer.op(statuses, id, next))
            if (statistics != null) add(writer.op(stats, id, statistics))
            add(event(id, next, actor, if (from == CoReportStatus.RETURNED) "Corrected report submitted" else "Report submitted to the Circuit Overseer", from, CoReportStatus.SUBMITTED, null, null))
        }
        return writer.push(ops).also { if (it is CircuitResult.Success) offline.cacheFromServer(statuses, id, next) }
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
        val ops = buildList {
            statsDoc(id)?.let { add(writer.op(stats, id, it.copy(snapshotStatus = CoReportStatus.NOT_SUBMITTED.name, updatedAt = now))) }
            add(writer.op(statuses, id, next))
            add(event(id, s, actor, "Report sending undone", s.status, CoReportStatus.NOT_SUBMITTED, null, s.coRemarks))
        }
        return writer.push(ops).also { if (it is CircuitResult.Success) offline.cacheFromServer(statuses, id, next) }
    }

    private suspend fun review(
        reportId: String, actor: SubmissionActor, action: String, to: CoReportStatus?, allowedFrom: Set<CoReportStatus>,
        remarks: String?, change: (CoMonthStatus, Long) -> CoMonthStatus,
    ): CircuitResult {
        val s = offline.get<CoMonthStatus>(statuses, reportId) ?: return CircuitResult.Conflict("This month has not been submitted.")
        if (s.status !in allowedFrom) return CircuitResult.Conflict("This month is ${s.status.label.lowercase()} — that action isn't available now.")
        val now = nowMillis()
        val target = to ?: s.status
        val next = change(s, now).copy(status = target, updatedAt = now)
        val ops = buildList {
            add(writer.op(statuses, reportId, next))
            if (to != null) statsDoc(reportId)?.let {
                add(writer.op(stats, reportId, it.copy(snapshotStatus = target.name, receivedDate = if (target == CoReportStatus.RECEIVED) now else null, updatedAt = now)))
            }
            add(event(reportId, s, actor, action, s.status, target, remarks, s.coRemarks))
        }
        return writer.push(ops).also { if (it is CircuitResult.Success) offline.cacheFromServer(statuses, reportId, next) }
    }

    override suspend fun receive(reportId: String, remarks: String?, actor: SubmissionActor): CircuitResult {
        val note = remarks?.trim()?.takeIf { it.isNotEmpty() }
        return review(reportId, actor, "Report received by the Circuit Overseer", CoReportStatus.RECEIVED, setOf(CoReportStatus.SUBMITTED), note) { s, now ->
            s.copy(receivedAt = now, receivedByPersonId = actor.personId, receivedByName = actor.name).let {
                if (note != null) it.copy(coRemarks = note, coRemarksAt = now, coRemarksByName = actor.name) else it
            }
        }
    }

    override suspend fun returnForCorrection(reportId: String, reason: String, actor: SubmissionActor): CircuitResult {
        val why = reason.trim()
        if (why.isEmpty()) return CircuitResult.Conflict("Please enter the CO Remarks explaining what needs to be corrected.")
        return review(reportId, actor, "Report returned for correction", CoReportStatus.RETURNED, setOf(CoReportStatus.SUBMITTED, CoReportStatus.RECEIVED), why) { s, now ->
            s.copy(returnedAt = now, returnedByPersonId = actor.personId, returnedByName = actor.name, returnReason = why, coRemarks = why, coRemarksAt = now, coRemarksByName = actor.name)
        }
    }

    override suspend fun saveRemarks(reportId: String, remarks: String, actor: SubmissionActor): CircuitResult {
        val note = remarks.trim().ifEmpty { null }
        return review(reportId, actor, "CO remarks updated", null, setOf(CoReportStatus.SUBMITTED, CoReportStatus.RECEIVED, CoReportStatus.RETURNED), note) { s, now ->
            s.copy(coRemarks = note, coRemarksAt = now, coRemarksByName = actor.name)
        }
    }
}

class BackendComparativeReportService(
    api: SyncApi,
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
    connectivity: ConnectivityObserver,
) : ComparativeReportService {
    private val writer = ServerWriter(api, connectivity)
    private val reports = ComparativeReportRepository.REPORTS
    private val history = ComparativeReportRepository.HISTORY
    private val remarks = ComparativeReportRepository.REMARKS

    private fun lockedMessage(s: ComparativeStatus) = if (s == ComparativeStatus.RECEIVED) COMPARATIVE_RECEIVED_MESSAGE else COMPARATIVE_SUBMITTED_MESSAGE

    private fun audit(r: ComparativeReport, actor: SubmissionActor, action: String, from: ComparativeStatus?, to: ComparativeStatus?, text: String? = null) =
        writer.op(
            history, remote.newId(history),
            ComparativeReportHistory(
                reportId = r.id, congregationId = r.congregationId, action = action, fromStatus = from?.name, toStatus = to?.name, version = r.version,
                userId = actor.personId, userName = actor.name, userRole = actor.role, at = nowMillis(), remarks = text,
            ),
        )

    override suspend fun submit(report: ComparativeReport, actor: SubmissionActor): CircuitResult {
        val existing = offline.get<ComparativeReport>(reports, report.id)
        val base = existing ?: report
        if (!base.status.congregationCanEdit) return CircuitResult.Conflict(lockedMessage(base.status))
        val now = nowMillis()
        val next = report.copy(
            status = ComparativeStatus.SUBMITTED, version = base.version + if (base.status == ComparativeStatus.RETURNED) 1 else 0,
            submittedBy = actor.personId, submittedByName = actor.name, submittedByRole = actor.role, submittedAt = now, updatedAt = now,
            returnReason = base.returnReason, returnedBy = base.returnedBy, returnedByName = base.returnedByName, returnedAt = base.returnedAt, currentCoRemarks = base.currentCoRemarks,
        )
        val ops = listOf(
            writer.op(reports, next.id, next),
            audit(next, actor, if (base.status == ComparativeStatus.RETURNED) "Corrected report resubmitted" else "Submitted to the Circuit Overseer", base.status, ComparativeStatus.SUBMITTED),
        )
        return writer.push(ops).also { if (it is CircuitResult.Success) offline.cacheFromServer(reports, next.id, next) }
    }

    private suspend fun load(id: String) = offline.get<ComparativeReport>(reports, id)

    override suspend fun receive(reportId: String, actor: SubmissionActor): CircuitResult {
        val r = load(reportId) ?: return CircuitResult.Conflict("This report is not on this device yet. Sync and try again.")
        if (r.status == ComparativeStatus.RECEIVED) return CircuitResult.Conflict("This Comparative Report was already received.")
        if (r.status != ComparativeStatus.SUBMITTED) return CircuitResult.Conflict("Only a submitted report can be received. It is now ${r.status.label.lowercase()}.")
        val now = nowMillis()
        val next = r.copy(status = ComparativeStatus.RECEIVED, receivedBy = actor.personId, receivedByName = actor.name, receivedAt = now, updatedAt = now)
        return writer.push(listOf(writer.op(reports, reportId, next), audit(next, actor, "Received by the Circuit Overseer", r.status, ComparativeStatus.RECEIVED)))
            .also { if (it is CircuitResult.Success) offline.cacheFromServer(reports, reportId, next) }
    }

    override suspend fun returnForCorrection(reportId: String, reason: String, actor: SubmissionActor): CircuitResult {
        if (reason.isBlank()) return CircuitResult.Conflict("Enter the reason for returning this report.")
        val r = load(reportId) ?: return CircuitResult.Conflict("This report is not on this device yet. Sync and try again.")
        if (r.status != ComparativeStatus.SUBMITTED) return CircuitResult.Conflict("Only a submitted report can be returned. It is now ${r.status.label.lowercase()}.")
        val now = nowMillis()
        val next = r.copy(
            status = ComparativeStatus.RETURNED, returnedBy = actor.personId, returnedByName = actor.name, returnedAt = now,
            returnReason = reason.trim(), currentCoRemarks = reason.trim(), updatedAt = now,
        )
        return writer.push(listOf(writer.op(reports, reportId, next), audit(next, actor, "Returned for correction", r.status, ComparativeStatus.RETURNED, reason.trim())))
            .also { if (it is CircuitResult.Success) offline.cacheFromServer(reports, reportId, next) }
    }

    override suspend fun addRemark(reportId: String, remark: String, actor: SubmissionActor): CircuitResult {
        if (remark.isBlank()) return CircuitResult.Conflict("Enter a remark first.")
        val r = load(reportId) ?: return CircuitResult.Conflict("This report is not on this device yet. Sync and try again.")
        if (r.status != ComparativeStatus.SUBMITTED && r.status != ComparativeStatus.RETURNED) return CircuitResult.Conflict(lockedMessage(r.status))
        val now = nowMillis()
        val next = r.copy(currentCoRemarks = remark.trim(), updatedAt = now)
        val note = ComparativeReportRemark(
            comparativeReportId = reportId, congregationId = r.congregationId, authorUserId = actor.personId, authorName = actor.name,
            authorRole = actor.role, remark = remark.trim(), createdAt = now,
        )
        return writer.push(
            listOf(writer.op(remarks, remote.newId(remarks), note), writer.op(reports, reportId, next), audit(next, actor, "CO remarks added", r.status, r.status, remark.trim())),
        ).also { if (it is CircuitResult.Success) offline.cacheFromServer(reports, reportId, next) }
    }
}
