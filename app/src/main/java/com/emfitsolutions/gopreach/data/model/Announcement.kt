package com.emfitsolutions.gopreach.data.model

import com.google.firebase.firestore.DocumentId

/**
 * "Announcement Module" — Super-Admin (any congregation), Admin/Coordinator
 * Elder (own congregation only) can create/edit/delete; every Publisher in
 * [congregationId] sees it as a notification in their own app.
 *
 * Firestore collection: `announcements/{announcementId}`
 */
data class Announcement(
    @DocumentId val id: String = "",
    val congregationId: String = "",
    val title: String = "",
    val details: String = "",
    /** Optional — uploaded to Firebase Storage at `announcements/{id}/image`;
     * null means no image (never uploaded, or cleared/removed since — spec:
     * "the image can be cleared or removed"). */
    val imageUrl: String? = null,
    /** Optional — a PDF/Word/Excel (or other) document attached alongside
     * (or instead of) [imageUrl], uploaded to Firebase Storage at
     * `announcements/{id}/attachment`. Independent of the image: an
     * announcement can have both, either, or neither. [attachmentFileName]
     * is the original picked file's display name (Storage paths don't
     * preserve it), shown in the UI and used as the suggested name if the
     * Publisher downloads/opens it. */
    val attachmentUrl: String? = null,
    val attachmentFileName: String? = null,
    val createdByPersonId: String = "",
    val createdAt: Long = 0L,
    val lastEditedByPersonId: String? = null,
    val lastEditedAt: Long? = null,
)
