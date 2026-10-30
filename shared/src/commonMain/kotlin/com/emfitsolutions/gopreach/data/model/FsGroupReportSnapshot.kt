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
    /** 1 for a snapshot that was never replaced; each replacement is the next version. */
    val version: Int = 1,
    /** The older copies, kept unchanged when the snapshot was replaced (oldest first). Only ever grows. */
    val versions: List<SnapshotVersion> = emptyList(),
    val replaceReason: String = "",
    val replacedAt: Long = 0L,
    val replacedByPersonId: String = "",
    val replacedByName: String = "",
) {
    /** The snapshot as it was in version [v]: this one when [v] is the current version, else the older copy kept in its history (null if there is none). */
    fun atVersion(v: Int): FsGroupReportSnapshot? =
        if (v >= version) this
        else versions.firstOrNull { it.version == v }?.let { copy(version = it.version, savedAt = it.savedAt, savedByPersonId = it.savedByPersonId, savedByName = it.savedByName, savedByRole = it.savedByRole, report = it.report) }

    /** What this snapshot becomes when it is replaced by [report], saved by [byPersonId]: the next version, with this copy appended, unchanged, to its history. */
    fun replacedBy(report: SnapshotReport, reason: String, byPersonId: String, byName: String, byRole: String, nowMillis: Long, sourceReports: Int, sourceVersion: Long) = copy(
        savedByPersonId = byPersonId, savedByName = byName, savedByRole = byRole, savedAt = nowMillis, sourceReports = sourceReports, sourceVersion = sourceVersion, report = report,
        version = version + 1, versions = versions + SnapshotVersion(version, savedAt, savedByPersonId, savedByName, savedByRole, this.report),
        replaceReason = reason.trim(), replacedAt = nowMillis, replacedByPersonId = byPersonId, replacedByName = byName,
    )

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

/** An older copy of a snapshot, exactly as it was when it was replaced. */
@kotlinx.serialization.Serializable
data class SnapshotVersion(
    val version: Int = 1,
    val savedAt: Long = 0L,
    val savedByPersonId: String = "",
    val savedByName: String = "",
    val savedByRole: String = "",
    val report: SnapshotReport = SnapshotReport(),
)
