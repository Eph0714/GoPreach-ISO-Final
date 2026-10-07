package com.emfitsolutions.gopreach.data.model

import com.emfitsolutions.gopreach.platform.DocumentId

/** Where a Comparative Report is in its formal submission to the Circuit Overseer. */
@kotlinx.serialization.Serializable
enum class ComparativeStatus {
    DRAFT, SUBMITTED, RETURNED, RECEIVED;

    val label: String
        get() = when (this) {
            DRAFT -> "Draft"
            SUBMITTED -> "Submitted"
            RETURNED -> "Returned for Correction"
            RECEIVED -> "Received"
        }

    /** The congregation may edit, regenerate and (re)submit it. */
    val congregationCanEdit: Boolean get() = this == DRAFT || this == RETURNED

    /** Read-only for the congregation: waiting for the Circuit Overseer, or an official historical record. */
    val locked: Boolean get() = this == SUBMITTED || this == RECEIVED

    /** The message that explains the state to the congregation. */
    val notice: String
        get() = when (this) {
            DRAFT -> "Draft — not yet submitted."
            SUBMITTED -> "Submitted – Awaiting CO Review"
            RETURNED -> "Comparative Report Returned for Correction"
            RECEIVED -> "Comparative Report Received by Circuit Overseer"
        }
}

/**
 * A formal Comparative Report of one congregation (`congregationComparativeReports/{id}`): two month ranges compared, saved as a
 * snapshot of exactly what was prepared. The document id is congregation + the four period months, so an identical report cannot
 * exist twice; [reportNumber] ("CR-2026-0001") is the friendly number. Once Submitted the congregation cannot change or delete it;
 * once Received it is an official historical record, locked server-side.
 */
@kotlinx.serialization.Serializable
data class ComparativeReport(
    @field:DocumentId val id: String = "",
    val congregationId: String = "",
    val reportNumber: String = "",
    // First-of-month epoch millis of each period's first and last month.
    val periodAStart: Long = 0L,
    val periodAEnd: Long = 0L,
    val periodBStart: Long = 0L,
    val periodBEnd: Long = 0L,
    val status: ComparativeStatus = ComparativeStatus.DRAFT,
    /** 1 for the first submission; +1 each time a returned report is sent again. */
    val version: Int = 1,
    /** The report exactly as prepared (JSON of [ComparativeSnapshot]); what the Circuit Overseer reviews and every export prints. */
    val reportSnapshot: String = "",
    /** The monthly statistics snapshots the report was built from. */
    val sourceSnapshotIds: List<String> = emptyList(),
    val attendanceRoundingMode: String = "ROUNDED",
    val createdBy: String = "",
    val createdByName: String = "",
    val createdAt: Long = 0L,
    val submittedBy: String? = null,
    val submittedByName: String? = null,
    val submittedByRole: String? = null,
    val submittedAt: Long? = null,
    val receivedBy: String? = null,
    val receivedByName: String? = null,
    val receivedAt: Long? = null,
    val returnedBy: String? = null,
    val returnedByName: String? = null,
    val returnedAt: Long? = null,
    val returnReason: String? = null,
    /** The Circuit Overseer's latest remark (the full history is in [ComparativeReportRemark]). */
    val currentCoRemarks: String? = null,
    val updatedAt: Long = 0L,
)

/** One permanent history line (`comparativeReportHistory/{id}`): created, edited, regenerated, submitted, returned, received, remark, exported, deleted draft. */
@kotlinx.serialization.Serializable
data class ComparativeReportHistory(
    @field:DocumentId val id: String = "",
    val reportId: String = "",
    val congregationId: String = "",
    val action: String = "",
    val fromStatus: String? = null,
    val toStatus: String? = null,
    val version: Int = 1,
    val userId: String = "",
    val userName: String = "",
    val userRole: String = "",
    val at: Long = 0L,
    val remarks: String? = null,
)

/** A Circuit Overseer remark (`comparativeReportRemarks/{id}`) — only ever added, never overwritten. */
@kotlinx.serialization.Serializable
data class ComparativeReportRemark(
    @field:DocumentId val id: String = "",
    val comparativeReportId: String = "",
    val congregationId: String = "",
    val authorUserId: String = "",
    val authorName: String = "",
    val authorRole: String = "",
    val remark: String = "",
    val createdAt: Long = 0L,
)

/** The id that makes a second identical report impossible: congregation + both periods. */
fun comparativeReportId(congregationId: String, aStart: Long, aEnd: Long, bStart: Long, bEnd: Long) =
    "${congregationId}_${aStart}_${aEnd}_${bStart}_${bEnd}"

const val COMPARATIVE_RECEIVED_MESSAGE =
    "This Comparative Report has already been received by the Circuit Overseer and can no longer be modified."
const val COMPARATIVE_SUBMITTED_MESSAGE =
    "This Comparative Report has been submitted to the Circuit Overseer. It cannot be modified or deleted unless the Circuit Overseer returns it for correction."
const val COMPARATIVE_DUPLICATE_MESSAGE = "A Comparative Report using these same periods already exists for this congregation."

/**
 * The saved content of a Comparative Report — pre-formatted text so the screen, print, PDF and Excel all show exactly the same
 * thing, however the underlying data changes later. [comparison] and [monthly] have their headings as the first row.
 */
@kotlinx.serialization.Serializable
data class ComparativeSnapshot(
    val congregationName: String = "",
    val periodA: String = "",
    val periodB: String = "",
    val generatedBy: String = "",
    val generatedAt: String = "",
    val calculationMode: String = "",
    val comparison: List<List<String>> = emptyList(),
    val monthly: List<List<String>> = emptyList(),
    val notes: List<String> = emptyList(),
    /** Months of the two periods that had no historical snapshot. */
    val missingMonths: List<String> = emptyList(),
    /** The mandatory end-of-report summary (Metric | Value), saved with the report so it never changes with later data. */
    val summary: List<List<String>> = emptyList(),
)

private val snapshotJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun ComparativeSnapshot.toJson(): String = snapshotJson.encodeToString(ComparativeSnapshot.serializer(), this)

/** The saved report content, or null when it is missing or unreadable. */
fun ComparativeReport.snapshot(): ComparativeSnapshot? =
    if (reportSnapshot.isBlank()) null else runCatching { snapshotJson.decodeFromString(ComparativeSnapshot.serializer(), reportSnapshot) }.getOrNull()
