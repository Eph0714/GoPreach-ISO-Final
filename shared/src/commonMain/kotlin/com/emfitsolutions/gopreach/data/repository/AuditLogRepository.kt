package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.AuditLogEntry
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.platform.nowMillis
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import kotlinx.coroutines.flow.Flow

private const val COLLECTION = "auditLog"

/** Spec §3 — "user logs": view/export for Super-Admin (all) and Admin/Coordinator
 * Elder (own congregation, via [AuditLogEntry.congregationId]); delete is
 * Super-Admin only, enforced by the calling screen's visibility.
 *
 * "Do not include user logs in server synchronization" — [log] writes stay
 * local-only (see [OfflineFirestoreRepository.saveLocalOnly]'s own doc
 * comment): a fresh entry this device creates is never enqueued for upload,
 * so it can't be picked up by either the manual "Sync to Server" button or
 * the app's automatic background sync. [observeAll]/[startRemoteSync] are
 * unchanged and keep *downloading* other devices' already-synced entries —
 * this is specifically about this device never pushing its own new log
 * activity up, not about hiding what other admins have already logged. */
class AuditLogRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    fun observeAll(): Flow<List<AuditLogEntry>> = offline.observeCollection(COLLECTION)

    suspend fun log(
        actorPersonId: String,
        action: String,
        targetType: String? = null,
        targetId: String? = null,
        congregationId: String? = null,
        details: String? = null,
    ) {
        val id = remote.newId(COLLECTION)
        offline.saveLocalOnly(
            COLLECTION,
            id,
            AuditLogEntry(
                id = id,
                actorPersonId = actorPersonId,
                action = action,
                targetType = targetType,
                targetId = targetId,
                congregationId = congregationId,
                timestamp = nowMillis(),
                details = details,
            ),
        )
    }

    suspend fun delete(entryId: String) = offline.delete(COLLECTION, entryId)

    /** "Select all user logs and delete it permanently" — Super-Admin only
     * (enforced by the calling screen's visibility, same as [delete]). Loops
     * [delete] rather than a Firestore batch write since every write here is
     * already local-first/instant (see [OfflineFirestoreRepository]'s own
     * doc comments) — there's no latency win a batch would buy back, and this
     * keeps the same single code path (and its own error handling) that a
     * single-entry delete already goes through. */
    suspend fun deleteAll(entryIds: Collection<String>) {
        entryIds.forEach { delete(it) }
    }

    fun startRemoteSync(): Flow<Unit> =
        remote.mirror(COLLECTION, AuditLogEntry::class) { it.id }
}
