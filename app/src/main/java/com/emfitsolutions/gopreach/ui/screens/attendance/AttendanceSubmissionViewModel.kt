package com.emfitsolutions.gopreach.ui.screens.attendance

import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.model.CoAttendanceRow
import com.emfitsolutions.gopreach.data.model.CoAttendanceSnapshot
import com.emfitsolutions.gopreach.data.model.CoMonthStatus
import com.emfitsolutions.gopreach.data.model.CoReportStatus
import com.emfitsolutions.gopreach.data.model.MeetingAttendance
import com.emfitsolutions.gopreach.data.model.coReportId
import com.emfitsolutions.gopreach.data.model.isFutureServiceMonth
import com.emfitsolutions.gopreach.data.remote.ServerClock
import com.emfitsolutions.gopreach.data.repository.CircuitCodeRepository
import com.emfitsolutions.gopreach.data.repository.CircuitResult
import com.emfitsolutions.gopreach.data.repository.CoAttendanceRepository
import com.emfitsolutions.gopreach.data.repository.CoAttendanceService
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.MeetingAttendanceRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.SubmissionActor
import com.emfitsolutions.gopreach.data.repository.messageOrNull
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.MonthCheck
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.MonthSendResult
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.MonthSendState
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.MonthValidation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Sending a congregation's Meeting Attendance months to the Circuit Overseer: the same snapshot process as the Field Service Report.
 * Each month is validated on its own, then sent as its own frozen, versioned snapshot; the live records are never copied or changed.
 * While a snapshot only awaits receipt the live month stays editable; the Circuit Overseer receiving it locks the month's attendance.
 */
class AttendanceSubmissionViewModel(
    private val attendance: MeetingAttendanceRepository,
    private val workflow: CoAttendanceRepository,
    private val service: CoAttendanceService,
    private val congregations: CongregationRepository,
    private val people: PersonRepository,
    private val circuitCodes: CircuitCodeRepository,
    private val serverClock: ServerClock,
) : ViewModel() {

    /** Every month's workflow status of [congregationId] (a month with no entry has not been sent). */
    fun statusesFor(congregationId: String?): Flow<List<CoMonthStatus>> =
        workflow.observeStatuses().map { list -> if (congregationId == null) emptyList() else list.filter { it.congregationId == congregationId } }

    fun assignedOverseerName(congregationId: String?): Flow<String?> =
        combine(circuitCodes.observeLinks(), people.observeAll()) { links, all ->
            val id = congregationId?.let { links[it]?.circuitOverseerPersonId }?.takeIf { it.isNotBlank() }
            id?.let { all.firstOrNull { p -> p.id == it }?.fullName }
        }

    /** The SERVER's date (never just the device's), so a future month cannot be sent by changing the phone's clock. */
    suspend fun serverNow(): ServerClock.Reading = serverClock.read()

    private fun rowsOf(records: List<MeetingAttendance>, month: Long): List<CoAttendanceRow> =
        records.filter { it.serviceMonth == month && !it.deleted }.sortedBy { it.meetingDate }.map { r ->
            CoAttendanceRow(
                meetingType = r.meetingType, meetingDate = r.meetingDate, parts = r.parts, calculatedAverage = r.calculatedAverage,
                officialAttendance = r.officialAttendance, roundingMode = r.roundingMode, remarks = r.remarks.orEmpty(),
            )
        }

    private suspend fun snapshotOf(congregationId: String, month: Long, role: String): CoAttendanceSnapshot? {
        val congregation = congregations.observeAll().first().firstOrNull { it.id == congregationId } ?: return null
        val rows = rowsOf(attendance.observeAll().first().filter { it.congregationId == congregationId }, month)
        if (rows.isEmpty()) return null
        val assignedCo = circuitCodes.observeLinks().first()[congregationId]?.circuitOverseerPersonId?.takeIf { it.isNotBlank() }
        return CoAttendanceSnapshot(
            congregationId = congregationId, congregationName = congregation.name, periodMonth = month, rows = rows,
            assignedCoPersonId = assignedCo, preparedByRole = role,
        )
    }

    /** The months among [months] that are Sent (awaiting receipt) whose live attendance differs from the snapshot already sent. */
    fun changesSince(congregationId: String?, months: List<Long>): Flow<Set<Long>> =
        if (congregationId == null || months.isEmpty()) flowOf(emptySet())
        else combine(attendance.observeAll(), workflow.observeSnapshots(), workflow.observeStatuses()) { live, copies, states ->
            months.filter { m ->
                val st = states.firstOrNull { it.id == coReportId(congregationId, m) }
                if (st == null || st.status != CoReportStatus.SUBMITTED) return@filter false
                val sent = copies.firstOrNull { it.congregationId == congregationId && it.periodMonth == m && it.version == st.version } ?: return@filter false
                rowsOf(live.filter { it.congregationId == congregationId }, m) != sent.rows.sortedBy { it.meetingDate }
            }.toSet()
        }

    /** Checks each month on its own BEFORE anything is sent; one month failing never affects the others. */
    suspend fun validateMonths(congregationId: String, months: List<Long>): List<MonthValidation> {
        val now = serverNow().now
        val states = workflow.observeStatuses().first()
        val copies = workflow.observeSnapshots().first()
        val live = attendance.observeAll().first().filter { it.congregationId == congregationId }
        return months.distinct().sorted().map { month ->
            val st = states.firstOrNull { it.id == coReportId(congregationId, month) }
            val status = st?.status ?: CoReportStatus.NOT_SUBMITTED
            val version = st?.version ?: 0
            fun result(check: MonthCheck, message: String) = MonthValidation(month, check, status, version, message)
            if (isFutureServiceMonth(month, now)) return@map result(MonthCheck.FUTURE, "A future month cannot be sent.")
            if (status == CoReportStatus.RECEIVED) return@map result(MonthCheck.ALREADY_RECEIVED, "Already received (version $version). Only the Circuit Overseer can return it.")
            val rows = rowsOf(live, month)
            if (rows.isEmpty()) return@map result(MonthCheck.NO_DATA, "No attendance is recorded for this month.")
            val midweek = rows.count { it.meetingType == com.emfitsolutions.gopreach.data.model.MeetingType.MIDWEEK }
            val weekend = rows.size - midweek
            val note = " Midweek $midweek · Weekend $weekend recorded."
            when (status) {
                CoReportStatus.SUBMITTED -> {
                    val sent = copies.firstOrNull { it.congregationId == congregationId && it.periodMonth == month && it.version == version }
                    if (sent != null && sent.rows.sortedBy { it.meetingDate } == rows) result(MonthCheck.NO_CHANGES, "Version $version is awaiting receipt and nothing changed since.")
                    else result(MonthCheck.SEND_UPDATE, "An updated snapshot (version ${version + 1}) will be sent.$note")
                }
                CoReportStatus.RETURNED -> result(MonthCheck.RESEND_AFTER_RETURN, "Returned" + (st?.returnReason?.let { ": $it" } ?: "") + ". Sent again as version ${version + 1}.$note")
                CoReportStatus.CANCELLED -> result(MonthCheck.RESEND_AFTER_CANCEL, "Cancelled" + (st?.cancelReason?.let { ": $it" } ?: "") + ". Sent again as version ${version + 1}.$note")
                else -> result(MonthCheck.READY, "Ready to send as version 1.$note")
            }
        }
    }

    private suspend fun actorFor(personId: String): SubmissionActor {
        val person = people.get(personId)
        return SubmissionActor(personId, person?.fullName ?: personId, if (person?.isSuperAdmin == true) "SUPER_ADMIN" else person?.activeAdminRole.orEmpty())
    }

    /** Sends every month in [months] as its own snapshot, one after another; a month that moved on since it was validated is skipped, never sent twice. */
    suspend fun submitMonths(congregationId: String, months: List<Long>, actorPersonId: String, expectedVersions: Map<Long, Int>): List<MonthSendResult> {
        val actor = actorFor(actorPersonId)
        return months.map { month ->
            val id = coReportId(congregationId, month)
            val before = workflow.observeStatuses().first().firstOrNull { it.id == id }
            val expected = expectedVersions[month]
            if (expected != null && (before?.version ?: 0) != expected) {
                return@map MonthSendResult(month, MonthSendState.ALREADY_SENT, "Already sent as version ${before?.version ?: 0} - not sent again.", before?.version, before?.submittedAt)
            }
            val snapshot = snapshotOf(congregationId, month, actor.role)
            val problem = if (snapshot == null) "The attendance snapshot could not be prepared, so nothing was sent."
            else runCatching { service.submit(congregationId, month, actor, snapshot, expected).messageOrNull() }.getOrElse { it.message ?: "Couldn't send this month." }
            if (problem == null) {
                val after = workflow.observeStatuses().first().firstOrNull { it.id == id }
                MonthSendResult(month, MonthSendState.SENT, null, after?.version, after?.submittedAt)
            } else MonthSendResult(month, MonthSendState.FAILED, problem, before?.version, null)
        }
    }

    suspend fun deleteSubmission(congregationId: String, month: Long, actorPersonId: String): CircuitResult =
        service.deleteSubmission(congregationId, month, actorFor(actorPersonId))

    suspend fun undo(congregationId: String, month: Long, actorPersonId: String): CircuitResult =
        service.undoSubmission(congregationId, month, actorFor(actorPersonId))
}
