package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.COMPARATIVE_DUPLICATE_MESSAGE
import com.emfitsolutions.gopreach.data.model.COMPARATIVE_RECEIVED_MESSAGE
import com.emfitsolutions.gopreach.data.model.COMPARATIVE_SUBMITTED_MESSAGE
import com.emfitsolutions.gopreach.data.model.ComparativeReport
import com.emfitsolutions.gopreach.data.model.ComparativeReportHistory
import com.emfitsolutions.gopreach.data.model.ComparativeReportRemark
import com.emfitsolutions.gopreach.data.model.ComparativeSnapshot
import com.emfitsolutions.gopreach.data.model.ComparativeStatus
import com.emfitsolutions.gopreach.data.model.comparativeReportId
import com.emfitsolutions.gopreach.data.model.toJson
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import com.emfitsolutions.gopreach.domain.ComparativePeriods
import com.emfitsolutions.gopreach.domain.MonthBounds
import com.emfitsolutions.gopreach.platform.nowMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** The first-of-month millis of a report's two periods. */
data class ComparativePeriodsInput(val aStart: Long, val aEnd: Long, val bStart: Long, val bEnd: Long)

sealed class ComparativeSaveResult {
    data class Saved(val report: ComparativeReport) : ComparativeSaveResult()

    /** A report with these same periods already exists for the congregation; [existing] can be opened instead. */
    data class Duplicate(val existing: ComparativeReport, val message: String = COMPARATIVE_DUPLICATE_MESSAGE) : ComparativeSaveResult()
    data class Refused(val message: String) : ComparativeSaveResult()
}

/** Why the congregation may not change this report right now (null = it may). Mirrors what the server rules allow. */
fun ComparativeReport.refusalToChange(): String? = when (status) {
    ComparativeStatus.RECEIVED -> COMPARATIVE_RECEIVED_MESSAGE
    ComparativeStatus.SUBMITTED -> COMPARATIVE_SUBMITTED_MESSAGE
    else -> null
}

/**
 * Formal Comparative Reports (congregationComparativeReports), offline-first for the congregation's drafts: a draft or a returned report
 * is saved to the device at once and synchronized later. Submitting, receiving, returning and remarking need the server (see
 * [ComparativeReportService]). Every change appends a history line. The rules re-check everything, so a stale offline edit of a report
 * that has since been received simply fails to sync.
 */
class ComparativeReportRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    fun observeReports(): Flow<List<ComparativeReport>> = offline.observeCollection(REPORTS)
    fun observeHistory(reportId: String): Flow<List<ComparativeReportHistory>> =
        offline.observeCollection<ComparativeReportHistory>(HISTORY).map { l -> l.filter { it.reportId == reportId }.sortedBy { it.at } }
    fun observeRemarks(reportId: String): Flow<List<ComparativeReportRemark>> =
        offline.observeCollection<ComparativeReportRemark>(REMARKS).map { l -> l.filter { it.comparativeReportId == reportId }.sortedBy { it.createdAt } }

    fun idFor(congregationId: String, p: ComparativePeriodsInput) = comparativeReportId(congregationId, p.aStart, p.aEnd, p.bStart, p.bEnd)

    private suspend fun nextNumber(congregationId: String, year: Int): String {
        val used = observeReports().first().filter { it.congregationId == congregationId }.mapNotNull { it.reportNumber.substringAfterLast('-', "").toIntOrNull() }
        return "CR-$year-" + ((used.maxOrNull() ?: 0) + 1).toString().padStart(4, '0')
    }

    suspend fun history(
        congregationId: String, reportId: String, action: String, actor: SubmissionActor,
        from: ComparativeStatus?, to: ComparativeStatus?, version: Int, remarks: String? = null,
    ) {
        val id = remote.newId(HISTORY)
        offline.save(
            HISTORY, id,
            ComparativeReportHistory(
                id = id, reportId = reportId, congregationId = congregationId, action = action, fromStatus = from?.name, toStatus = to?.name, version = version,
                userId = actor.personId, userName = actor.name, userRole = actor.role, at = nowMillis(), remarks = remarks,
            ),
        )
    }

    /** Creates a new draft for these periods, unless they are invalid or the congregation already has a report for them. [year] numbers the report. */
    suspend fun createDraft(
        congregationId: String, periods: ComparativePeriodsInput, snapshot: ComparativeSnapshot, sourceIds: List<String>, roundingMode: String,
        actor: SubmissionActor, year: Int, keepNumber: String? = null,
    ): ComparativeSaveResult {
        ComparativePeriods.validate(periods.aStart, periods.aEnd, periods.bStart, periods.bEnd, MonthBounds.of(nowMillis()).startInclusive)
            ?.let { return ComparativeSaveResult.Refused(it) }
        val id = idFor(congregationId, periods)
        observeReports().first().firstOrNull { it.id == id }?.let { return ComparativeSaveResult.Duplicate(it) }
        val now = nowMillis()
        val report = ComparativeReport(
            id = id, congregationId = congregationId, reportNumber = keepNumber ?: nextNumber(congregationId, year),
            periodAStart = periods.aStart, periodAEnd = periods.aEnd, periodBStart = periods.bStart, periodBEnd = periods.bEnd,
            status = ComparativeStatus.DRAFT, version = 1, reportSnapshot = snapshot.toJson(), sourceSnapshotIds = sourceIds, attendanceRoundingMode = roundingMode,
            createdBy = actor.personId, createdByName = actor.name, createdAt = now, updatedAt = now,
        )
        offline.save(REPORTS, id, report)
        history(congregationId, id, "Draft created", actor, null, ComparativeStatus.DRAFT, 1)
        return ComparativeSaveResult.Saved(report)
    }

    /** Replaces the saved snapshot of a draft / returned report (an edit, or a regeneration from the latest historical snapshots). */
    suspend fun updateSnapshot(
        report: ComparativeReport, snapshot: ComparativeSnapshot, sourceIds: List<String>, roundingMode: String, actor: SubmissionActor, action: String,
    ): ComparativeSaveResult {
        report.refusalToChange()?.let { return ComparativeSaveResult.Refused(it) }
        val updated = report.copy(reportSnapshot = snapshot.toJson(), sourceSnapshotIds = sourceIds, attendanceRoundingMode = roundingMode, updatedAt = nowMillis())
        offline.save(REPORTS, report.id, updated)
        history(report.congregationId, report.id, action, actor, report.status, report.status, report.version)
        return ComparativeSaveResult.Saved(updated)
    }

    /** Edit Period of a draft: it moves to the new periods (its number is kept); refused when another report already uses them. */
    suspend fun changePeriods(
        report: ComparativeReport, periods: ComparativePeriodsInput, snapshot: ComparativeSnapshot, sourceIds: List<String>, roundingMode: String,
        actor: SubmissionActor, year: Int,
    ): ComparativeSaveResult {
        if (report.status != ComparativeStatus.DRAFT) return ComparativeSaveResult.Refused(report.refusalToChange() ?: "Only a draft's periods can be changed.")
        if (idFor(report.congregationId, periods) == report.id) return updateSnapshot(report, snapshot, sourceIds, roundingMode, actor, "Edited")
        val created = createDraft(report.congregationId, periods, snapshot, sourceIds, roundingMode, actor, year, keepNumber = report.reportNumber)
        if (created is ComparativeSaveResult.Saved) {
            offline.delete(REPORTS, report.id)
            history(report.congregationId, report.id, "Periods changed (moved to ${created.report.id})", actor, ComparativeStatus.DRAFT, null, report.version)
        }
        return created
    }

    /** Only a draft can be deleted; its history stays. */
    suspend fun deleteDraft(report: ComparativeReport, actor: SubmissionActor): ComparativeSaveResult {
        if (report.status != ComparativeStatus.DRAFT) return ComparativeSaveResult.Refused(report.refusalToChange() ?: "Only a draft can be deleted.")
        history(report.congregationId, report.id, "Draft deleted", actor, ComparativeStatus.DRAFT, null, report.version)
        offline.delete(REPORTS, report.id)
        return ComparativeSaveResult.Saved(report)
    }

    suspend fun logExport(report: ComparativeReport, kind: String, actor: SubmissionActor) =
        history(report.congregationId, report.id, "Exported ($kind)", actor, report.status, report.status, report.version)

    fun startSyncFor(congregationId: String): Flow<Unit> = merge(
        remote.mirror(REPORTS, ComparativeReport::class, equalTo = "congregationId" to congregationId) { it.id },
        remote.mirror(HISTORY, ComparativeReportHistory::class, equalTo = "congregationId" to congregationId) { it.id },
        remote.mirror(REMARKS, ComparativeReportRemark::class, equalTo = "congregationId" to congregationId) { it.id },
    )

    fun startSyncAll(): Flow<Unit> = merge(
        remote.mirror(REPORTS, ComparativeReport::class) { it.id },
        remote.mirror(HISTORY, ComparativeReportHistory::class) { it.id },
        remote.mirror(REMARKS, ComparativeReportRemark::class) { it.id },
    )

    companion object {
        const val REPORTS = "congregationComparativeReports"
        const val HISTORY = "comparativeReportHistory"
        const val REMARKS = "comparativeReportRemarks"
    }
}

/** The moves that need the server: each is one transaction that re-reads the report and checks the move is legal from its current state. */
interface ComparativeReportService {
    /** DRAFT / RETURNED → SUBMITTED (creating the document first when the draft never reached the server). */
    suspend fun submit(report: ComparativeReport, actor: SubmissionActor): CircuitResult

    /** Circuit Overseer: SUBMITTED → RECEIVED (permanently locked). */
    suspend fun receive(reportId: String, actor: SubmissionActor): CircuitResult

    /** Circuit Overseer: SUBMITTED → RETURNED, [reason] required. */
    suspend fun returnForCorrection(reportId: String, reason: String, actor: SubmissionActor): CircuitResult

    /** Circuit Overseer: add a remark (the history is kept). */
    suspend fun addRemark(reportId: String, remark: String, actor: SubmissionActor): CircuitResult
}
