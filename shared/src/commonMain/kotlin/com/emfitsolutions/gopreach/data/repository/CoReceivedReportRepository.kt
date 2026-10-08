package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.CoReceivedReport
import com.emfitsolutions.gopreach.data.model.CoReportRead
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.platform.nowMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Circuit Overseer's received reports and which of them this overseer has already opened. Both live on the server, so the unread
 * count is the same after a sign-out, an app restart or on another phone, and a congregation moved to another overseer shows its
 * unopened reports as unread to the new overseer (read marks belong to a person, the report to the congregation).
 */
class CoReceivedReportRepository(private val offline: OfflineFirestoreRepository) {
    fun observeReports(): Flow<List<CoReceivedReport>> = offline.observeCollection(REPORTS)

    /** The ids of the received reports [coPersonId] has opened. */
    fun observeReadIds(coPersonId: String): Flow<Set<String>> =
        offline.observeCollection<CoReportRead>(READS).map { list -> list.filter { it.coPersonId == coPersonId }.map { it.receivedReportId }.toSet() }

    /** Marks [report] as read by [coPersonId] — idempotent, and synchronized to the server like any other change. */
    suspend fun markRead(coPersonId: String, report: CoReceivedReport) {
        val id = "${coPersonId}_${report.id}"
        offline.save(READS, id, CoReportRead(id = id, coPersonId = coPersonId, receivedReportId = report.id, congregationId = report.congregationId, periodMonth = report.periodMonth, readAt = nowMillis()))
    }

    companion object {
        const val REPORTS = "coReceivedReports"
        const val READS = "coReportReads"
    }
}
