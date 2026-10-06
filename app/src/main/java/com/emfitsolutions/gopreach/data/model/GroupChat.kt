package com.emfitsolutions.gopreach.data.model

import com.google.firebase.firestore.DocumentId

/**
 * "Group Chat Setting" module — replaces the old Chat Schedule (which was
 * never a real chat). A congregation-scoped chat: only Coordinator Elder/
 * Admin/Super-Admin may create one, and only people in the *same*
 * congregation may ever be added as [participantIds] (spec: "the
 * participants will be only under the congregation they belong").
 *
 * [participantIds] is denormalized directly onto this doc (not a separate
 * subcollection) — the same trick used elsewhere in this app to let
 * Firestore security rules check membership with a plain field-equality
 * check, no query needed (see firestore.rules). Removing someone just drops
 * their id from this array; their prior messages are untouched, so chat
 * history/audit is preserved even after removal.
 *
 * [lastMessage*]/[messageCount]/[readCounts] are denormalized read-model
 * fields so the Chat Box icon and the group-chat list can show a live
 * preview + unread badge without fetching every message for every group —
 * see [GroupChatRepository.sendMessage]/[GroupChatRepository.markRead].
 *
 * Firestore collection: `groupChats/{groupChatId}`
 */
data class GroupChat(
    @DocumentId val id: String = "",
    val congregationId: String = "",
    val groupName: String = "",
    val description: String = "",
    val participantIds: List<String> = emptyList(),
    val createdByPersonId: String = "",
    val createdAt: Long = 0L,
    val status: RecordStatus = RecordStatus.ACTIVE,

    val lastMessageText: String? = null,
    val lastMessageSenderName: String? = null,
    val lastMessageIsAttachment: Boolean = false,
    val lastMessageAt: Long? = null,

    /** Total messages ever sent — incremented atomically on every send (see
     * [GroupChatRepository.sendMessage]'s use of `FieldValue.increment`). */
    val messageCount: Long = 0L,
    /** Snapshot of [messageCount] as of each participant's last time opening
     * this chat, keyed by personId. Unread count = messageCount -
     * (readCounts[personId] ?: 0) — cheap, no per-message query needed. */
    val readCounts: Map<String, Long> = emptyMap(),
)

/** What kind of file a [GroupChatMessage]'s attachment is — drives the
 * Shared Documents folder grouping (spec §9: Images / PDF / Word / Excel). */
enum class GroupChatAttachmentType { IMAGE, PDF, WORD, EXCEL }

/**
 * One chat message, optionally carrying a file attachment (spec §8: image/
 * PDF/Word/Excel) — kept as fields on the message itself rather than a
 * separate attachments collection, since every attachment in this module is
 * always sent as part of exactly one message.
 *
 * Firestore collection: `groupChats/{groupChatId}/messages/{messageId}`
 */
data class GroupChatMessage(
    @DocumentId val id: String = "",
    val senderId: String = "",
    val senderName: String = "",
    /** [AdminRole.displayLabel]/"Publisher" at send time — a label, not a
     * live lookup, so history reads correctly even if the sender's role
     * later changes. */
    val senderRole: String = "",
    val text: String = "",
    val attachmentUrl: String? = null,
    val attachmentFileName: String? = null,
    val attachmentType: GroupChatAttachmentType? = null,
    val attachmentSize: Long = 0L,
    val createdAt: Long = 0L,

    /** Sender-only edit (text only — an attachment, once sent, is
     * immutable, same as every other chat app). [editedAt] drives the
     * "(edited)" label. */
    val isEdited: Boolean = false,
    val editedAt: Long? = null,
    /** "Delete for everyone" — a soft delete: the doc (and its place in
     * history/audit) stays, but [text]/attachment fields are cleared and
     * the UI renders a "This message was deleted" placeholder instead.
     * Sender-only, same as edit. */
    val isDeletedForEveryone: Boolean = false,
    /** "Delete for me" — personIds who chose to hide this one message from
     * their own view only; everyone else still sees it normally. Any
     * participant may add their own id here (see firestore.rules), not
     * just the sender. */
    val deletedForPersonIds: List<String> = emptyList(),
) {
    val hasAttachment: Boolean get() = attachmentUrl != null && !isDeletedForEveryone
}
