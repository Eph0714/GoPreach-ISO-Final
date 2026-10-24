package com.emfitsolutions.gopreach.data.model

import com.emfitsolutions.gopreach.platform.DocumentId

/**
 * A private bookmark on a Searching / Return Visit / Bible Study record. It only points at the record: it never copies it, never
 * changes it and never gives its owner access to it (the list still shows only records the person may already see).
 *
 * Firestore collection: `favorites/{publisherPersonId}_{recordId}` - the id makes a duplicate impossible, and the server refuses
 * any other id.
 */
@kotlinx.serialization.Serializable
data class Favorite(
    @field:DocumentId val id: String = "",
    val publisherPersonId: String = "",
    val recordId: String = "",
    val stage: String = "",
    val createdAt: Long = 0L,
)
