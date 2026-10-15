package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.model.CoReportStatus
import com.emfitsolutions.gopreach.data.repository.CoFieldServiceReportRepository
import com.emfitsolutions.gopreach.data.repository.CoReceivedReportRepository
import com.emfitsolutions.gopreach.domain.AcceptedComparative.MonthInput
import com.emfitsolutions.gopreach.domain.AcceptedComparative.MonthState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * What the Circuit Overseer's Comparative Report may use for one congregation: for each service month, whether its snapshot has been
 * ACCEPTED (the overseer marked the month Received) and, if so, that exact frozen snapshot - the send the acceptance refers to, never
 * another send and never the congregation's live records. Months that are only Submitted, Returned, or never sent are listed with that
 * state so the report can say why they are missing.
 */
class AcceptedComparativeViewModel(
    private val reports: CoReceivedReportRepository,
    private val statuses: CoFieldServiceReportRepository,
) : ViewModel() {

    fun months(congregationId: String?): Flow<List<MonthInput>> =
        combine(reports.observeReports(), statuses.observeStatuses()) { copies, states ->
            if (congregationId == null) return@combine emptyList()
            val sends = copies.filter { it.congregationId == congregationId }
            states.filter { it.congregationId == congregationId }.map { s ->
                when (s.status) {
                    CoReportStatus.RECEIVED -> {
                        val copy = sends.firstOrNull { it.periodMonth == s.periodMonth && it.version == s.version }
                        if (copy != null) MonthInput(s.periodMonth, MonthState.ACCEPTED, copy) else MonthInput(s.periodMonth, MonthState.NO_COPY)
                    }
                    CoReportStatus.SUBMITTED -> MonthInput(s.periodMonth, MonthState.AWAITING)
                    CoReportStatus.RETURNED -> MonthInput(s.periodMonth, MonthState.RETURNED)
                    CoReportStatus.CANCELLED -> MonthInput(s.periodMonth, MonthState.CANCELLED)
                    CoReportStatus.NOT_SUBMITTED -> MonthInput(s.periodMonth, MonthState.NOT_SENT)
                }
            }
        }
}
