package com.emfitsolutions.gopreach.data.model

import com.emfitsolutions.gopreach.platform.DocumentId

/**
 * Monthly Report Snapshot: a permanent copy of an FS Group's Field Service Report for one month, saved by the Overseer, Servant or Assistant of that group. It holds the
 * values AS THEY WERE when it was saved - later edits to the report never reach it, and the server refuses to change or remove a saved snapshot.
 *
 * Firestore collection: `fsGroupReportSnapshots/{groupId}_{yyyyMM}` - the id is the group and the month, so a second snapshot for the same period cannot exist.
 * The website writes and reads the same documents.
 */
@kotlinx.serialization.Serializable
data class FsGroupReportSnapshot(
    @field:DocumentId val id: String = "",
    val congregationId: String = "",
    val congregationName: String = "",
    val groupId: String = "",
    val groupName: String = "",
    val year: Int = 0,
    val month: Int = 0,
    val periodMonth: Long = 0L,
    val savedByPersonId: String = "",
    val savedByName: String = "",
    val savedByRole: String = "",
    val savedAt: Long = 0L,
    /** How many monthly reports of the congregation the month had, and when the last was edited: a reference to the source at the time of saving. */
    val sourceReports: Int = 0,
    val sourceVersion: Long = 0L,
    val report: SnapshotReport = SnapshotReport(),
) {
    companion object {
        fun idFor(groupId: String, year: Int, month: Int) = "${groupId}_$year${month.toString().padStart(2, '0')}"
    }
}

@kotlinx.serialization.Serializable
data class SnapshotReport(
    val sheet: SnapshotSheet = SnapshotSheet(),
    /** [label, value] pairs: Total Records, Reports Submitted, ... */
    val summary: List<List<String>> = emptyList(),
    val totals: SnapshotTotals = SnapshotTotals(),
)

@kotlinx.serialization.Serializable
data class SnapshotSheet(
    val name: String = "",
    val title: String = "",
    /** [label, value] pairs under the title: Group Overseer / Servant / Assistant. */
    val officers: List<List<String>> = emptyList(),
    /** Status keys (UP, PUB, AP, RP, SP, IRREGULAR, INACTIVE, REPROOF) of the report columns, and of the Hours block (the pioneer kinds). */
    val columns: List<String> = emptyList(),
    val hourColumns: List<String> = emptyList(),
    val lines: List<SnapshotLine> = emptyList(),
    val participated: Int = 0,
)

@kotlinx.serialization.Serializable
data class SnapshotLine(
    val number: Int = 0,
    val status: String = "",
    val personId: String = "",
    val name: String = "",
    val reports: Int = 1,
    val hasReport: Boolean = true,
    val hours: Double? = null,
    val studies: Int? = null,
    val remarks: String = "",
)

@kotlinx.serialization.Serializable
data class SnapshotTotals(
    val publishers: Int = 0,
    val participated: Int = 0,
    val pending: Int = 0,
    val reports: Int = 0,
    val hours: Double = 0.0,
    val studies: Int = 0,
)
