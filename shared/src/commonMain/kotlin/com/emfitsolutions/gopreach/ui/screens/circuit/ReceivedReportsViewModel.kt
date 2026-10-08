package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.model.CoMonthStatus
import com.emfitsolutions.gopreach.data.model.CoReceivedReport
import com.emfitsolutions.gopreach.data.repository.CoFieldServiceReportRepository
import com.emfitsolutions.gopreach.data.repository.CoReceivedReportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** One service month of the selected congregation in the Received Reports list. */
data class ReceivedMonth(
    val periodMonth: Long,
    val congregationId: String,
    /** The month's workflow status (Submitted / Received / Returned). */
    val status: CoMonthStatus?,
    /** The newest frozen copy; null for a month sent before copies existed. */
    val report: CoReceivedReport?,
    /** How many sends of this month the overseer has not opened yet. */
    val unread: Int,
)

/** A notification: one received report the overseer has not opened. */
data class ReceivedNotification(val report: CoReceivedReport)

/**
 * Received Reports for the Circuit Overseer. Everything here comes from the frozen copies the congregations sent and from the
 * overseer's own read marks; a copy is unread until this overseer opens it, and every corrected re-send is a new unread copy.
 */
class ReceivedReportsViewModel(
    private val reports: CoReceivedReportRepository,
    private val statuses: CoFieldServiceReportRepository,
) : ViewModel() {
    /** Every unopened received report of the congregations on this device (the overseer's current assignment), newest first. */
    fun unread(coPersonId: String): Flow<List<CoReceivedReport>> =
        combine(reports.observeReports(), reports.observeReadIds(coPersonId)) { all, read ->
            all.filter { it.id !in read }.sortedByDescending { it.submittedAt }
        }

    fun unreadCount(coPersonId: String): Flow<Int> = unread(coPersonId).map { it.size }

    /** The months of [congregationId] this overseer may see, newest first, with their unread counts. */
    fun months(coPersonId: String, congregationId: String): Flow<List<ReceivedMonth>> =
        combine(reports.observeReports(), reports.observeReadIds(coPersonId), statuses.observeStatuses()) { all, read, st ->
            val copies = all.filter { it.congregationId == congregationId }.groupBy { it.periodMonth }
            val states = st.filter { it.congregationId == congregationId && it.status.visibleToCircuitOverseer }.associateBy { it.periodMonth }
            (copies.keys + states.keys).sortedDescending().map { month ->
                val list = copies[month].orEmpty()
                ReceivedMonth(month, congregationId, states[month], list.maxByOrNull { it.version }, list.count { it.id !in read })
            }
        }

    /** Every send of [month] of [congregationId] (the newest first): the copy now and its earlier versions. */
    fun versions(congregationId: String, month: Long): Flow<List<CoReceivedReport>> =
        reports.observeReports().map { all -> all.filter { it.congregationId == congregationId && it.periodMonth == month }.sortedByDescending { it.version } }

    fun readIds(coPersonId: String): Flow<Set<String>> = reports.observeReadIds(coPersonId)

    suspend fun markRead(coPersonId: String, report: CoReceivedReport) = reports.markRead(coPersonId, report)
}
