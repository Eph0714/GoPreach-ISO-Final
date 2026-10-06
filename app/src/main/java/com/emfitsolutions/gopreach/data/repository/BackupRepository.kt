package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.local.dao.CacheDao
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject
import javax.inject.Singleton

data class BackupEntry(val collectionPath: String, val documentId: String, val payloadJson: String)
data class BackupFile(val exportedAt: Long, val exportedByPersonId: String, val entries: List<BackupEntry>)

/**
 * Spec §3/§5.1 — Backup & Restore, Super-Admin only. Firestore is already
 * durable server-side, so this isn't a disaster-recovery mechanism the way a
 * local-database backup would be — it's a portable snapshot of everything
 * currently in the offline cache (which mirrors Firestore whenever the app has
 * been online), exported to a JSON file the Super-Admin can save anywhere and
 * restore from later, e.g. before a risky bulk change.
 */
@Singleton
class BackupRepository @Inject constructor(
    private val cacheDao: CacheDao,
    private val offline: OfflineFirestoreRepository,
    private val auditLogRepository: AuditLogRepository,
    private val gson: Gson,
) {
    suspend fun exportAll(exportedByPersonId: String): String {
        val entries = cacheDao.getAll().map { BackupEntry(it.collectionPath, it.documentId, it.payloadJson) }
        val file = BackupFile(exportedAt = System.currentTimeMillis(), exportedByPersonId = exportedByPersonId, entries = entries)
        auditLogRepository.log(actorPersonId = exportedByPersonId, action = "EXPORT_BACKUP")
        return gson.toJson(file)
    }

    /** Re-queues every entry in [json] through the normal offline-sync write path
     * (spec §6.5), so a restore is just a burst of ordinary pending writes rather
     * than a special-cased bulk import. */
    suspend fun restoreFromJson(json: String, restoredByPersonId: String): Int {
        val file: BackupFile = gson.fromJson(json, object : TypeToken<BackupFile>() {}.type)
        file.entries.forEach { entry -> offline.saveRawJson(entry.collectionPath, entry.documentId, entry.payloadJson) }
        auditLogRepository.log(actorPersonId = restoredByPersonId, action = "RESTORE_BACKUP")
        return file.entries.size
    }
}
