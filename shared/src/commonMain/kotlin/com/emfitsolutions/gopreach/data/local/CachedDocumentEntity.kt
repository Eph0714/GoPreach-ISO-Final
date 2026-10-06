package com.emfitsolutions.gopreach.data.local

import androidx.room.Entity

/**
 * Generic local cache row: one Firestore document, kept as JSON. Every domain
 * repository (Congregations, Publishers, Territories, ...) reads/writes through
 * this single table rather than needing its own Room entity+DAO, so the "offline-
 * first for all CRUD, app-wide" requirement (spec §6.5) doesn't multiply per feature.
 *
 * [collectionPath] is the Firestore collection (e.g. "people", "congregations",
 * or a subcollection path like "interestedPeople/abc123/visits").
 */
@Entity(tableName = "cached_documents", primaryKeys = ["collectionPath", "documentId"])
data class CachedDocumentEntity(
    val collectionPath: String,
    val documentId: String,
    val payloadJson: String,
    /** [com.emfitsolutions.gopreach.data.model.SyncState] name. */
    val syncState: String,
    val updatedAt: Long,
)
