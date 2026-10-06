package com.emfitsolutions.gopreach.ui.screens.announcements

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.Announcement
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.repository.AnnouncementRepository
import com.emfitsolutions.gopreach.data.repository.AnnouncementSeenStore
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ManageAnnouncementsVM"

/**
 * "Announcement Module" — Super-Admin/Admin/Coordinator Elder manage
 * (create/edit/delete, own congregation for Admin/Coordinator Elder), every
 * Publisher in that congregation reads. This one ViewModel/screen serves
 * both sides — see [com.emfitsolutions.gopreach.ui.screens.announcements
 * .AnnouncementsScreen]'s `readOnly` parameter.
 */
@HiltViewModel
class ManageAnnouncementsViewModel @Inject constructor(
    private val announcementRepository: AnnouncementRepository,
    private val auditLogRepository: AuditLogRepository,
    private val announcementSeenStore: AnnouncementSeenStore,
    congregationRepository: CongregationRepository,
) : ViewModel() {

    val congregations: StateFlow<List<Congregation>> =
        congregationRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun rowsFor(congregationId: String?): Flow<List<Announcement>> =
        announcementRepository.observeAll().map { list ->
            list.filter { congregationId == null || it.congregationId == congregationId }
                .sortedByDescending { it.createdAt }
        }

    /** Live unseen count for the notification balloon — every announcement
     * in [congregationId] created after this Publisher last opened the
     * announcements screen (see [markSeen]). */
    fun unseenCountFor(congregationId: String?, personId: String): Flow<Int> =
        combine(rowsFor(congregationId), announcementSeenStore.lastSeenAtByPerson) { rows, seenMap ->
            val lastSeenAt = seenMap[personId] ?: announcementSeenStore.lastSeenAt(personId)
            rows.count { it.createdAt > lastSeenAt }
        }

    fun markSeen(personId: String) = announcementSeenStore.markSeenNow(personId)

    /** Saves the announcement's text fields, then applies the image *and*
     * attachment changes, if any, in the same coroutine — a brand-new
     * announcement has no id (and therefore no Storage path) until the
     * first save completes, so a pick has to wait for that regardless of
     * which field changed. [removeImage]/[removeAttachment] clears the
     * existing one (spec: "the image can be cleared or removed"); a fresh
     * pick ([pickedImageUri]/[pickedAttachmentUri]) takes priority if both
     * are somehow set (replaces whatever was there, removal or not). */
    fun saveWithImage(
        announcement: Announcement,
        pickedImageUri: Uri?,
        removeImage: Boolean,
        pickedAttachmentUri: Uri? = null,
        pickedAttachmentFileName: String? = null,
        removeAttachment: Boolean = false,
        actorPersonId: String,
        onImageUploadFailed: (() -> Unit)? = null,
        onAttachmentUploadFailed: (() -> Unit)? = null,
    ) {
        val isNew = announcement.id.isBlank()
        viewModelScope.launch {
            // Bug fix ("the form closes if you attach an image on saving"):
            // uploadImage()/deleteImage()/uploadAttachment()/deleteAttachment()
            // all hit Firebase Storage over the network (unlike save(),
            // which is local-first and effectively never throws — see
            // OfflineFirestoreRepository's own doc comments) — a flaky
            // connection, an expired auth token, or a revoked content://
            // read grant on the picked uri all throw here. Left uncaught,
            // that exception propagated out of this coroutine with nothing
            // downstream to catch it, which crashes the whole app process —
            // not just this dialog — exactly the "form closes"/"problem
            // attaching" symptoms reported. The text fields were already
            // saved successfully by this point (the dialog itself dismisses
            // immediately on tapping Save, independent of this coroutine —
            // see AnnouncementDialog), so a failure on either upload keeps
            // that save and just lets the caller know which one didn't
            // attach, instead of taking the whole app down with it. Image
            // and attachment are handled as two independent try blocks —
            // one failing (e.g. the image) must not also swallow/skip the
            // other (e.g. a perfectly good attachment upload).
            var saved = try {
                announcementRepository.save(
                    announcement.copy(lastEditedByPersonId = actorPersonId, lastEditedAt = System.currentTimeMillis()),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Announcement save failed", e)
                return@launch
            }
            try {
                if (pickedImageUri != null) {
                    val url = announcementRepository.uploadImage(saved.id, pickedImageUri)
                    saved = announcementRepository.save(saved.copy(imageUrl = url))
                } else if (removeImage && saved.imageUrl != null) {
                    announcementRepository.deleteImage(saved.id)
                    saved = announcementRepository.save(saved.copy(imageUrl = null))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Announcement image upload/removal failed", e)
                onImageUploadFailed?.invoke()
            }
            try {
                if (pickedAttachmentUri != null) {
                    val url = announcementRepository.uploadAttachment(saved.id, pickedAttachmentUri)
                    saved = announcementRepository.save(saved.copy(attachmentUrl = url, attachmentFileName = pickedAttachmentFileName))
                } else if (removeAttachment && saved.attachmentUrl != null) {
                    announcementRepository.deleteAttachment(saved.id)
                    saved = announcementRepository.save(saved.copy(attachmentUrl = null, attachmentFileName = null))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Announcement attachment upload/removal failed", e)
                onAttachmentUploadFailed?.invoke()
            }
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = if (isNew) "CREATE_ANNOUNCEMENT" else "EDIT_ANNOUNCEMENT",
                targetType = "Announcement",
                targetId = saved.id,
                congregationId = saved.congregationId,
            )
        }
    }

    fun delete(announcement: Announcement, actorPersonId: String) {
        viewModelScope.launch {
            announcementRepository.delete(announcement.id)
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "DELETE_ANNOUNCEMENT",
                targetType = "Announcement",
                targetId = announcement.id,
                congregationId = announcement.congregationId,
            )
        }
    }
}
