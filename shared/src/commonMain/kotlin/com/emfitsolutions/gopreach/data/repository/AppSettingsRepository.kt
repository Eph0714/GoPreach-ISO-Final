package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.AppSettings
import com.emfitsolutions.gopreach.data.remote.RemoteFiles
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.platform.nowMillis
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val COLLECTION = "appSettings"

/**
 * Global app settings — currently just the Super-Admin-customizable logo (spec §1,
 * Control Panel module in spec §5.1/§3). One document ([AppSettings.GLOBAL_ID]),
 * modeled as a one-row "collection" so it reuses the same offline cache/sync path
 * as everything else (see [OfflineFirestoreRepository]).
 */
class AppSettingsRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
    private val files: RemoteFiles,
    private val auditLogRepository: AuditLogRepository,
) {
    fun observe(): Flow<AppSettings> =
        offline.observeCollection<AppSettings>(COLLECTION).map { list ->
            list.firstOrNull { it.id == AppSettings.GLOBAL_ID } ?: AppSettings()
        }

    /** Uploads [imageUri] to Storage and points [AppSettings.logoUrl] at it. Super-Admin
     * only — enforced by the Control Panel screen's visibility, mirrored server-side
     * by Firestore/Storage security rules on this path. */
    suspend fun uploadLogo(imageUri: String, updatedByPersonId: String) {
        val downloadUrl = files.upload("app-settings/logo.png", imageUri)
        // copy() of the current doc, not a fresh AppSettings(): this document
        // also carries the session-timeout settings, which must survive a logo change.
        val current = observe().first()
        offline.save(
            COLLECTION,
            AppSettings.GLOBAL_ID,
            current.copy(
                logoUrl = downloadUrl,
                updatedAt = nowMillis(),
                updatedByPersonId = updatedByPersonId,
            ),
        )
        auditLogRepository.log(actorPersonId = updatedByPersonId, action = "UPLOAD_LOGO")
    }

    /** "Session Timeout Setting" — turn the inactivity logout on/off and set
     * its limit. Access (Super-Admin/Admins/Elders) is enforced by the screen's
     * visibility and by firestore.rules on `appSettings`. */
    suspend fun saveSessionTimeout(enabled: Boolean, minutes: Int, updatedByPersonId: String) {
        val current = observe().first()
        val clamped = minutes.coerceIn(AppSettings.MIN_SESSION_TIMEOUT_MINUTES, AppSettings.MAX_SESSION_TIMEOUT_MINUTES)
        offline.save(
            COLLECTION,
            AppSettings.GLOBAL_ID,
            current.copy(
                sessionTimeoutEnabled = enabled,
                sessionTimeoutMinutes = clamped,
                updatedAt = nowMillis(),
                updatedByPersonId = updatedByPersonId,
            ),
        )
        auditLogRepository.log(
            actorPersonId = updatedByPersonId,
            action = "UPDATE_SESSION_TIMEOUT",
            details = "enabled=$enabled, minutes=$clamped",
        )
    }

    /** "Automatically Permanently Delete Deleted Records" — on/off and the retention period in days. */
    suspend fun saveTrashRetention(enabled: Boolean, days: Int, updatedByPersonId: String) {
        val current = observe().first()
        val safeDays = if (days in AppSettings.TRASH_RETENTION_OPTIONS) days else AppSettings.DEFAULT_TRASH_RETENTION_DAYS
        offline.save(
            COLLECTION,
            AppSettings.GLOBAL_ID,
            current.copy(
                trashAutoDeleteEnabled = enabled,
                trashRetentionDays = safeDays,
                updatedAt = nowMillis(),
                updatedByPersonId = updatedByPersonId,
            ),
        )
        auditLogRepository.log(
            actorPersonId = updatedByPersonId,
            action = "UPDATE_DELETED_RECORDS_RETENTION",
            details = "autoDelete=$enabled, days=$safeDays",
        )
    }

    /** Whether sending the consolidated congregation report to the Circuit Overseer is blocked while some publishers have not submitted. */
    suspend fun saveRequireAllPublishersSubmitted(required: Boolean, updatedByPersonId: String) {
        val current = observe().first()
        offline.save(COLLECTION, AppSettings.GLOBAL_ID, current.copy(requireAllPublishersSubmitted = required, updatedAt = nowMillis(), updatedByPersonId = updatedByPersonId))
        auditLogRepository.log(actorPersonId = updatedByPersonId, action = "UPDATE_CONSOLIDATED_SUBMISSION_REQUIREMENT", details = "requireAllPublishersSubmitted=$required")
    }

    fun startRemoteSync(): Flow<Unit> =
        remote.mirror(COLLECTION, AppSettings::class) { it.id }
}
