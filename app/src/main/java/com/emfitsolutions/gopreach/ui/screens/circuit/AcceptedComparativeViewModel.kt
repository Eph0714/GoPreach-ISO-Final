package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.model.CoAttendanceSnapshot
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.CoReportStatus
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.repository.CoFieldServiceReportRepository
import com.emfitsolutions.gopreach.data.repository.CoReceivedReportRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.domain.AcceptedComparative.MonthInput
import com.emfitsolutions.gopreach.domain.AcceptedComparative.MonthState
import com.emfitsolutions.gopreach.domain.AttendanceComparative
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * What the Comparative Report may use for one congregation: for each service month, the exact frozen snapshot of the send that counts and
 * never the congregation's live records. For the Circuit Overseer a month counts once it was marked Received (ACCEPTED). For the congregation's
 * own admins ([includeSent]) a month counts as soon as it was sent to the Circuit Overseer (Submitted or Received), so they compare exactly
 * what the overseer has been given. Months that were returned, cancelled or never sent are listed with that state so the report can say why
 * they are missing.
 */
class AcceptedComparativeViewModel(
    private val reports: CoReceivedReportRepository,
    private val statuses: CoFieldServiceReportRepository,
    private val attendance: com.emfitsolutions.gopreach.data.repository.CoAttendanceRepository,
    private val congregations: CongregationRepository,
) : ViewModel() {

    /** The congregations an admin may compare: their own only ([onlyId]), or every active one (the Super-Admin, [onlyId] = null). */
    fun congregationsFor(onlyId: String?): Flow<List<Congregation>> = congregations.observeAll().map { list ->
        list.filter { it.status == RecordStatus.ACTIVE && (onlyId == null || it.id == onlyId) }.sortedBy { it.name }
    }

    private fun counts(status: CoReportStatus, includeSent: Boolean) = status == CoReportStatus.RECEIVED || (includeSent && status == CoReportStatus.SUBMITTED)

    /** The Circuit Attendance comparison's months: which have a counting attendance snapshot, and that exact frozen send. */
    fun attendanceMonths(congregationId: String?, includeSent: Boolean = false): Flow<List<AttendanceComparative.MonthInput>> =
        combine(attendance.observeSnapshots(), attendance.observeStatuses()) { copies, states ->
            if (congregationId == null) return@combine emptyList()
            val sends = copies.filter { it.congregationId == congregationId }
            states.filter { it.congregationId == congregationId }.map { s ->
                val input = { state: MonthState, copy: CoAttendanceSnapshot? -> AttendanceComparative.MonthInput(s.periodMonth, state, copy) }
                when {
                    counts(s.status, includeSent) -> {
                        val copy = sends.firstOrNull { it.periodMonth == s.periodMonth && it.version == s.version }
                        if (copy != null) input(MonthState.ACCEPTED, copy) else input(MonthState.NO_COPY, null)
                    }
                    s.status == CoReportStatus.SUBMITTED -> input(MonthState.AWAITING, null)
                    s.status == CoReportStatus.RETURNED -> input(MonthState.RETURNED, null)
                    s.status == CoReportStatus.CANCELLED -> input(MonthState.CANCELLED, null)
                    else -> input(MonthState.NOT_SENT, null)
                }
            }
        }

    fun months(congregationId: String?, includeSent: Boolean = false): Flow<List<MonthInput>> =
        combine(reports.observeReports(), statuses.observeStatuses()) { copies, states ->
            if (congregationId == null) return@combine emptyList()
            val sends = copies.filter { it.congregationId == congregationId }
            states.filter { it.congregationId == congregationId }.map { s ->
                when {
                    counts(s.status, includeSent) -> {
                        val copy = sends.firstOrNull { it.periodMonth == s.periodMonth && it.version == s.version }
                        if (copy != null) MonthInput(s.periodMonth, MonthState.ACCEPTED, copy) else MonthInput(s.periodMonth, MonthState.NO_COPY)
                    }
                    s.status == CoReportStatus.SUBMITTED -> MonthInput(s.periodMonth, MonthState.AWAITING)
                    s.status == CoReportStatus.RETURNED -> MonthInput(s.periodMonth, MonthState.RETURNED)
                    s.status == CoReportStatus.CANCELLED -> MonthInput(s.periodMonth, MonthState.CANCELLED)
                    else -> MonthInput(s.periodMonth, MonthState.NOT_SENT)
                }
            }
        }
}
