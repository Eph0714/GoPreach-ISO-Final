package com.emfitsolutions.gopreach.data.repository

import android.net.Uri
import com.emfitsolutions.gopreach.data.model.Announcement
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.mirrorFirestoreCollection
import com.emfitsolutions.gopreach.di.ApplicationScope
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val COLLECTION = "announcements"

/** "Announcement Module" — CRUD for Super-Admin/Admin/Coordinator Elder,
 * read-only for every Publisher in the same congregation. */
@Singleton
class AnnouncementRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    fun observeAll(): Flow<List<Announcement>> = offline.observeCollection(COLLECTION)

    suspend fun save(announcement: Announcement): Announcement {
        val id = announcement.id.ifBlank { firestore.collection(COLLECTION).document().id }
        val withId = announcement.copy(id = id)
        offline.save(COLLECTION, id, withId)
        return withId
    }

    /** Deletes the announcement doc and best-effort cleans up its uploaded
     * image/attachment, if any — a missing/already-deleted Storage object is
     * not an error worth surfacing here. */
    suspend fun delete(announcementId: String) {
        runCatching { storage.reference.child(imagePath(announcementId)).delete().await() }
        runCatching { storage.reference.child(attachmentPath(announcementId)).delete().await() }
        offline.delete(COLLECTION, announcementId)
    }

    /** Uploads [imageUri] to this announcement's fixed Storage path (one
     * image per announcement — a re-upload simply overwrites it) and returns
     * the resulting download URL; the caller is responsible for saving that
     * onto the [Announcement.imageUrl] field. */
    suspend fun uploadImage(announcementId: String, imageUri: Uri): String {
        val ref = storage.reference.child(imagePath(announcementId))
        ref.putFile(imageUri).await()
        return ref.downloadUrl.await().toString()
    }

    /** Removes the uploaded image from Storage — spec: "the image can be
     * cleared or removed." The caller separately clears [Announcement.imageUrl]. */
    suspend fun deleteImage(announcementId: String) {
        runCatching { storage.reference.child(imagePath(announcementId)).delete().await() }
    }

    /** "Allow to add files like pdf, word and excel" — same one-attachment-
     * per-announcement, re-upload-overwrites shape as [uploadImage]; the
     * caller saves the returned URL onto [Announcement.attachmentUrl]. */
    suspend fun uploadAttachment(announcementId: String, fileUri: Uri): String {
        val ref = storage.reference.child(attachmentPath(announcementId))
        ref.putFile(fileUri).await()
        return ref.downloadUrl.await().toString()
    }

    /** Removes the uploaded attachment from Storage. The caller separately
     * clears [Announcement.attachmentUrl]/[Announcement.attachmentFileName]. */
    suspend fun deleteAttachment(announcementId: String) {
        runCatching { storage.reference.child(attachmentPath(announcementId)).delete().await() }
    }

    private fun imagePath(announcementId: String) = "announcements/$announcementId/image"
    private fun attachmentPath(announcementId: String) = "announcements/$announcementId/attachment"

    fun startRemoteSync(): Flow<Unit> =
        mirrorFirestoreCollection(firestore, offline, appScope, COLLECTION, Announcement::class.java) { it.id }
}
