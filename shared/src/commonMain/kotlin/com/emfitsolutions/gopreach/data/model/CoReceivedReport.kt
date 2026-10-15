package com.emfitsolutions.gopreach.data.model

import com.emfitsolutions.gopreach.platform.DocumentId

/** One publisher's line of a received report, frozen exactly as the table showed it when the congregation sent the month. */
@kotlinx.serialization.Serializable
data class CoReceivedRow(
    val number: Int = 0,
    /** The status column: "RP", "SP", "AP", "UP", "Pub", ... */
    val status: String = "",
    val name: String = "",
    val reportsCount: Int = 0,
    val hours: Double? = null,
    val bibleStudies: Int? = null,
    val remarks: String = "",
    /** The publisher this line is about (so two sends of the same month can be compared line by line). Empty on copies sent before this existed. */
    val personId: String = "",
    /** The service category [PublisherCategory] name that applied to this publisher IN this month (empty on older copies). */
    val category: String = "",
    /** How that category was determined: a [CategorySource] name (empty on older copies). */
    val categorySource: String = "",
    /** The Admin who confirmed the category for this month when [categorySource] is ADMIN_CONFIRMED. */
    val categoryConfirmedByPersonId: String? = null,
)

/** A publisher whose service category in this snapshot's month differs from the month before (what changed, for the Circuit Overseer). */
@kotlinx.serialization.Serializable
data class CoCategoryChange(
    val personId: String = "",
    val name: String = "",
    /** The status label of the previous month ("Pub", "AP", "RP", ...). */
    val from: String = "",
    val to: String = "",
)

/**
 * The Circuit Overseer's own copy of one sent Field Service Report (`coReceivedReports/{congregationId}_{periodMonth}_{send number}`).
 * It is written once, in the same step as the send, and never edited: a month that is returned and sent again becomes a new send number
 * (a new copy, a new notification) and the earlier copy stays as history. The Circuit Overseer reads only these copies, never the
 * congregation's working records.
 */
@kotlinx.serialization.Serializable
data class CoReceivedReport(
    @field:DocumentId val id: String = "",
    val congregationId: String = "",
    val congregationName: String = "",
    /** First-of-month epoch millis of the service month. */
    val periodMonth: Long = 0L,
    /** The send number: 1 for the first send, 2 after a return and a corrected send, ... */
    val version: Int = 1,
    val submittedAt: Long = 0L,
    val submittedByPersonId: String = "",
    val submittedByName: String = "",
    val rows: List<CoReceivedRow> = emptyList(),
    /** Publishers whose category in this month differs from the previous month. */
    val categoryChanges: List<CoCategoryChange> = emptyList(),
    /** The congregation's own report this snapshot was taken from (congregation id + month), so every send of the month is traceable to it. */
    val originalReportId: String = "",
    /** The Circuit Overseer the congregation was assigned to when it was sent. */
    val assignedCoPersonId: String? = null,
    /** Which layout / calculation rules the snapshot was produced under, kept so an old snapshot is always read the way it was made. */
    val schemaVersion: Int = 1,
    /** Who prepared the send and in which role (the same person as [submittedByPersonId]; kept with the frozen copy for the history). */
    val preparedByRole: String = "",
) {
    /** How many lines carry each status label ("Pub", "AP", "RP", ...), in the order of first appearance. */
    val countByStatus: Map<String, Int> get() = rows.groupingBy { it.status }.eachCount()
    val categoriesConfirmedByAdmin: Int get() = rows.count { it.categorySource == "ADMIN_CONFIRMED" }

    val publisherCount: Int get() = rows.size
    val participatedCount: Int get() = rows.count { it.bibleStudies != null }
    val totalHours: Double get() = rows.sumOf { it.hours ?: 0.0 }
    val totalBibleStudies: Int get() = rows.sumOf { it.bibleStudies ?: 0 }
}

/** "This Circuit Overseer has opened this received report" (`coReportReads/{coPersonId}_{receivedReportId}`) — what turns a notification from unread to read. */
@kotlinx.serialization.Serializable
data class CoReportRead(
    @field:DocumentId val id: String = "",
    val coPersonId: String = "",
    val receivedReportId: String = "",
    val congregationId: String = "",
    val periodMonth: Long = 0L,
    val readAt: Long = 0L,
)
