package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.Announcement
import com.emfitsolutions.gopreach.data.model.AnnouncementAttachment
import com.emfitsolutions.gopreach.data.model.AnnouncementRead
import com.emfitsolutions.gopreach.data.remote.SyncApi
import com.emfitsolutions.gopreach.platform.nowMillis
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import com.emfitsolutions.gopreach.data.remote.RemoteFiles
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import kotlinx.coroutines.flow.Flow

private const val COLLECTION = "announcements"
private const val READS = "announcementReads"

/** "Announcement Module" — CRUD for Super-Admin/Admin/Coordinator Elder,
 * read-only for every Publisher in the same congregation. */
class AnnouncementRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
    private val files: RemoteFiles,
    private val api: SyncApi,
) {
    fun observeAll(): Flow<List<Announcement>> = offline.observeCollection(COLLECTION)

    /** A fresh id, so a draft being composed can have files uploaded to it before its first save. */
    fun newId(): String = remote.newId(COLLECTION)

    suspend fun save(announcement: Announcement): Announcement {
        val id = announcement.id.ifBlank { RecentCreates.idFor(COLLECTION, announcement) { remote.newId(COLLECTION) } }
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

    // ---- Circuit Overseer announcements: per-recipient read marks and permission-checked attachments ----

    /** The ids of the announcements [personId] has opened (read status is kept per recipient and synchronized to the server). */
    fun observeReadIds(personId: String): Flow<Set<String>> =
        offline.observeCollection<AnnouncementRead>(READS).map { list -> list.filter { it.personId == personId }.map { it.announcementId }.toSet() }

    /** Marks the announcement read by [personId] - idempotent. */
    suspend fun markRead(personId: String, announcementId: String) {
        val id = "${personId}_$announcementId"
        offline.save(READS, id, AnnouncementRead(id = id, announcementId = announcementId, personId = personId, readAt = nowMillis()))
    }

    /** Who has opened this announcement (the sender's view): person ids with their time. */
    fun observeReads(announcementId: String): Flow<List<AnnouncementRead>> =
        offline.observeCollection<AnnouncementRead>(READS).map { list -> list.filter { it.announcementId == announcementId } }

    /** Uploads one attachment; the server checks the type, size and content and answers with the stored file's description. */
    suspend fun uploadSecureAttachment(announcementId: String, name: String, bytes: ByteArray, mime: String): AnnouncementAttachment {
        val reply = api.uploadAnnouncementFile(announcementId, name, bytes, mime)
        fun text(key: String) = (reply[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        return AnnouncementAttachment(id = text("id"), name = text("name"), mime = text("mime"), size = reply["size"]?.jsonPrimitive?.long ?: bytes.size.toLong())
    }

    suspend fun downloadSecureAttachment(announcementId: String, fileId: String): ByteArray = api.downloadAnnouncementFile(announcementId, fileId)

    suspend fun deleteSecureAttachment(announcementId: String, fileId: String) {
        runCatching { api.deleteAnnouncementFile(announcementId, fileId) }
    }

    private fun imagePath(announcementId: String) = "announcements/$announcementId/image"
    private fun attachmentPath(announcementId: String) = "announcements/$announcementId/attachment"

    fun startRemoteSync(): Flow<Unit> =
        remote.mirror(COLLECTION, Announcement::class) { it.id }
}
