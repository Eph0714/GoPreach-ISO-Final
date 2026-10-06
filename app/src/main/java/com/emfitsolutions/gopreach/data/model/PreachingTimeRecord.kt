package com.emfitsolutions.gopreach.data.model

import com.google.firebase.firestore.DocumentId

/**
 * "Preaching Time Record Module" spec §12-§15 — a Pioneer's per-day preaching
 * time log, distinct from [MonthlyReport.hoursRendered] (a single
 * self-reported monthly total): this is the actual per-record source the
 * Pioneer Dashboard's "Preaching Hours" statistic sums (spec §11/§13:
 * "Total Preaching Hours = SUM(Hour Consumed)... do not hard-code").
 *
 * Firestore collection: `preachingTimeRecords/{recordId}`
 */
data class PreachingTimeRecord(
    @DocumentId val id: String = "",
    val publisherPersonId: String = "",
    val congregationId: String = "",
    val date: Long = 0L,
    /** Spec §13 — a real numeric type (hours, e.g. 2.0/3.5/5.0), never
     * formatted text; totals are computed by summing this field directly. */
    val hoursConsumed: Double = 0.0,
    /** Required (spec §12: "according to existing rules") — scoped to the
     * Pioneer's own congregation the same way every other Territory picker
     * in this app already is. */
    val territoryId: String = "",
    val remarks: String? = null,
    /** "Admin Record Deletion and Inactive Status" spec — see [Congregation.status]. */
    val status: RecordStatus = RecordStatus.ACTIVE,
    // Audit trail (spec §12: "Created By, Date Created, Updated By, Date Updated").
    val createdByPersonId: String = "",
    val createdAt: Long = 0L,
    val lastEditedByPersonId: String? = null,
    val lastEditedAt: Long? = null,
)
