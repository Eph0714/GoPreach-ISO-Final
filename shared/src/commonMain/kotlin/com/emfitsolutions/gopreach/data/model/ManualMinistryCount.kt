package com.emfitsolutions.gopreach.data.model

import com.emfitsolutions.gopreach.platform.DocumentId

/**
 * A Return Visit / Bible Study QUANTITY typed in by hand in My Planner (no record needed). It is added to the people counted from the
 * Ministry module's own records, so the period total = unique people from records + the quantities typed here. A record added under the
 * Ministry module still appears in My Planner by itself; nothing here replaces it.
 *
 * [stage] is a [PipelineStage] name: `RETURN_VISIT` or `BIBLE_STUDY`. A submitted month is locked exactly like Credit Hours.
 *
 * Firestore collection: `manualMinistryCounts/{id}`
 */
@kotlinx.serialization.Serializable
data class ManualMinistryCount(
    @field:DocumentId val id: String = "",
    val publisherPersonId: String = "",
    /** Start-of-day millis of the date the quantity counts toward. */
    val dayStart: Long = 0L,
    val stage: String = "",
    val quantity: Int = 0,
    val note: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    companion object {
        const val MAX_QUANTITY = 999
    }
}
