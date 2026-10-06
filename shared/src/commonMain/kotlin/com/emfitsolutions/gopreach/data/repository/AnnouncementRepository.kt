package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.Announcement
import com.emfitsolutions.gopreach.data.remote.RemoteFiles
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import kotlinx.coroutines.flow.Flow

private const val COLLECTION = "announcements"

/** "Announcement Module" — CRUD for Super-Admin/Admin/Coordinator Elder,
 * read-only for every Publisher in the same congregation. */
class AnnouncementRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
    private val files: RemoteFiles,
) {
    fun observeAll(): Flow<List<Announcement>> = offline.observeCollection(COLLECTION)

    suspend fun save(announcement: Announcement): Announcement {
        val id = announcement.id.ifBlank { remote.newId(COLLECTION) }
        val withId = announcement.copy(id = id)
        offline.save(COLLECTION, id, withId)
        return withId
    }

    /** Deletes the announcement doc and best-effort cleans up its uploaded
     * image/attachment, if any — a missing/already-deleted Storage object is
     * not an error worth surfacing here. */
    suspend fun delete(announcementId: String) {
        files.delete(imagePath(announcementId))
        files.delete(attachmentPath(announcementId))
        offline.delete(COLLECTION, announcementId)
    }

    /** Uploads [imageUri] to this announcement's fixed Storage path (one
     * image per announcement — a re-upload simply overwrites it) and returns
     * the resulting download URL; the caller is responsible for saving that
     * onto the [Announcement.imageUrl] field. */
    suspend fun uploadImage(announcementId: String, imageUri: String): String =
        files.upload(imagePath(announcementId), imageUri)

    /** Removes the uploaded image from Storage — spec: "the image can be
     * cleared or removed." The caller separately clears [Announcement.imageUrl]. */
    suspend fun deleteImage(announcementId: String) {
        files.delete(imagePath(announcementId))
    }

    /** "Allow to add files like pdf, word and excel" — same one-attachment-
     * per-announcement, re-upload-overwrites shape as [uploadImage]; the
     * caller saves the returned URL onto [Announcement.attachmentUrl]. */
    suspend fun uploadAttachment(announcementId: String, fileUri: String): String =
        files.upload(attachmentPath(announcementId), fileUri)

    /** Removes the uploaded attachment from Storage. The caller separately
     * clears [Announcement.attachmentUrl]/[Announcement.attachmentFileName]. */
    suspend fun deleteAttachment(announcementId: String) {
        files.delete(attachmentPath(announcementId))
    }

    private fun imagePath(announcementId: String) = "announcements/$announcementId/image"
    private fun attachmentPath(announcementId: String) = "announcements/$announcementId/attachment"

    fun startRemoteSync(): Flow<Unit> =
        remote.mirror(COLLECTION, Announcement::class) { it.id }
}
