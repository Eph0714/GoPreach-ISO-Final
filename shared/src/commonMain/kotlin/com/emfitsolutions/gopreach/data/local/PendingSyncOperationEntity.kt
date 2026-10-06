package com.emfitsolutions.gopreach.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One outstanding write against Firestore, queued while offline (or while a prior
 * attempt failed) and flushed by SyncWorker once connectivity is back. Ordered by
 * [createdAt] so operations against the same document replay in the order the user
 * made them.
 */
@Entity(tableName = "pending_sync_operations")
data class PendingSyncOperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val collectionPath: String,
    val documentId: String,
    /** [com.emfitsolutions.gopreach.data.model.SyncOperationType] name. */
    val operationType: String,
    /** Full document JSON for CREATE/UPDATE; null for DELETE. */
    val payloadJson: String?,
    val createdAt: Long,
    val retryCount: Int = 0,
    val lastError: String? = null,
    /** Bug fix ("Sync Failed" never clearing even long after connectivity
     * returns) — a genuinely permanent failure (bad data, a Firestore rules
     * rejection, an invalid path — see [com.emfitsolutions.gopreach.data
     * .sync.SyncWorker.classifyFailure]) used to be recorded exactly like a
     * transient network failure: retried forever, on every single sync
     * attempt, alongside everything else still pending. One such row was
     * enough to keep [com.emfitsolutions.gopreach.data.sync.SyncStatusCenter]
     * reporting "Sync Failed" *indefinitely*, even once every other change —
     * and the network itself — was completely fine, which is exactly what
     * read as "reconnected but sync never recovers." `true` here excludes
     * this row from [com.emfitsolutions.gopreach.data.local.dao.SyncQueueDao
     * .getAllPending]'s retry loop entirely (see that query) — it is kept,
     * not deleted, for investigation/manual recovery (spec: "do not silently
     * delete the pending operation"), just no longer retried in a loop it
     * can never win. */
    val isPermanentFailure: Boolean = false,
)
