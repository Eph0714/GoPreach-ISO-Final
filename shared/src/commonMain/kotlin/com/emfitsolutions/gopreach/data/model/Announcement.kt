package com.emfitsolutions.gopreach.data.model

import com.emfitsolutions.gopreach.platform.DocumentId

/**
 * "Announcement Module" — Super-Admin (any congregation), Admin/Coordinator
 * Elder (own congregation only) can create/edit/delete; every Publisher in
 * [congregationId] sees it as a notification in their own app.
 *
 * Firestore collection: `announcements/{announcementId}`
 */
@kotlinx.serialization.Serializable
data class Announcement(
    @field:DocumentId val id: String = "",
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

    // ---- Circuit Overseer announcements (the fields below are null / empty on the original admin announcements) ----
    /** [STATUS_DRAFT], [STATUS_PUBLISHED] or [STATUS_ARCHIVED]; "expired" is never stored - it is [lifecycle] once [expiryDate] has passed. */
    val status: String? = null,
    /** [SCOPE_CONGREGATION] or [SCOPE_SELECTED_ELDERS]. Non-null marks a Circuit Overseer announcement. */
    val recipientScope: String? = null,
    /** The elders addressed when [recipientScope] is [SCOPE_SELECTED_ELDERS]; the server checks each is an elder of [congregationId]. */
    val recipientIds: List<String> = emptyList(),
    /** The date the announcement is issued (the sender's choice, shown to recipients). */
    val announcementDate: Long? = null,
    /** After this day the announcement no longer counts as active. */
    val expiryDate: Long? = null,
    val publishedAt: Long? = null,
    /** Metadata only; the files themselves are kept on the server and only downloaded through a permission-checked request. */
    val attachments: List<AnnouncementAttachment> = emptyList(),
    val createdByName: String = "",
) {
    /** True for an announcement written through the Circuit Overseer module. */
    val isCircuitOverseerStyle: Boolean get() = recipientScope != null

    /** What the sender and the recipients are told about its state right now. */
    fun lifecycle(nowMillis: Long): AnnouncementLifecycle = when {
        !isCircuitOverseerStyle -> AnnouncementLifecycle.ACTIVE
        status == STATUS_DRAFT -> AnnouncementLifecycle.DRAFT
        status == STATUS_ARCHIVED -> AnnouncementLifecycle.ARCHIVED
        expiryDate != null && nowMillis > expiryDate + DAY_MS - 1 -> AnnouncementLifecycle.EXPIRED
        else -> AnnouncementLifecycle.ACTIVE
    }

    /** The date used to order and show it: the issued date, else when it was created. */
    val displayDate: Long get() = announcementDate ?: createdAt

    companion object {
        const val STATUS_DRAFT = "DRAFT"
        const val STATUS_PUBLISHED = "PUBLISHED"
        const val STATUS_ARCHIVED = "ARCHIVED"
        const val SCOPE_CONGREGATION = "CONGREGATION"
        const val SCOPE_SELECTED_ELDERS = "SELECTED_ELDERS"
        private const val DAY_MS = 86_400_000L
    }
}

enum class AnnouncementLifecycle(val label: String) { DRAFT("Draft"), ACTIVE("Active"), EXPIRED("Expired"), ARCHIVED("Archived") }

/** One file on a Circuit Overseer announcement: just what the lists show (name, type, size). Its bytes live on the server. */
@kotlinx.serialization.Serializable
data class AnnouncementAttachment(
    val id: String = "",
    val name: String = "",
    val mime: String = "",
    val size: Long = 0L,
) {
    val isImage: Boolean get() = mime.startsWith("image/")
    val isPdf: Boolean get() = mime == "application/pdf"
    /** "245 KB" / "1.2 MB". */
    val sizeLabel: String get() = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        else -> "${(size * 10 / (1024 * 1024)) / 10.0} MB"
    }
}

/** "This person has opened this announcement" (`announcementReads/{personId}_{announcementId}`): read status is kept per recipient on the server. */
@kotlinx.serialization.Serializable
data class AnnouncementRead(
    @field:DocumentId val id: String = "",
    val announcementId: String = "",
    val personId: String = "",
    val readAt: Long = 0L,
)
