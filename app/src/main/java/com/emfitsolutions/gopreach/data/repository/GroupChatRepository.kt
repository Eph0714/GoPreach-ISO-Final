package com.emfitsolutions.gopreach.data.repository

import android.net.Uri
import com.emfitsolutions.gopreach.data.model.GroupChat
import com.emfitsolutions.gopreach.data.model.GroupChatAttachmentType
import com.emfitsolutions.gopreach.data.model.GroupChatMessage
import com.emfitsolutions.gopreach.data.remote.RemoteFiles
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.SyncEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val COLLECTION = "groupChats"
private const val MESSAGES_SUBCOLLECTION = "messages"

/**
 * "Group Chat Setting" module. Chats and messages are ordinary synchronized documents: every write lands in the local copy at once and
 * is then pushed to the server straight away (a chat that only delivers messages after the user remembers to sync is not a chat), and
 * the foreground poller brings in what other people sent within seconds. Attachments are uploaded to the server's file store.
 */
class GroupChatRepository(
    private val offline: OfflineFirestoreRepository,
    private val syncEngine: SyncEngine,
    private val files: RemoteFiles,
) {
    private fun localChats(): Flow<List<GroupChat>> = offline.observeCollection<GroupChat>(COLLECTION)
    private fun localMessages(groupChatId: String): Flow<List<GroupChatMessage>> =
        offline.observeCollection<GroupChatMessage>("$COLLECTION/$groupChatId/$MESSAGES_SUBCOLLECTION").map { l -> l.sortedBy { it.createdAt } }
    private suspend fun saveChat(chat: GroupChat) { offline.save(COLLECTION, chat.id, chat); runCatching { syncEngine.syncOnce() } }
    private suspend fun saveMessage(groupChatId: String, message: GroupChatMessage) {
        offline.save("$COLLECTION/$groupChatId/$MESSAGES_SUBCOLLECTION", message.id, message)
    }
    private fun newLocalId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(20)

    /** Every group chat the given person is currently a participant of — feeds both the Chat Box icon and a Publisher/Elder's own
     * "My Group Chats" list. A removed participant stops matching the instant [updateParticipants] drops their id, which is what
     * actually makes them lose access (spec §12). */
    fun observeGroupChatsForParticipant(personId: String): Flow<List<GroupChat>> =
        localChats().map { l -> l.filter { personId in it.participantIds } }

    /** Every group chat in one congregation — the Coordinator Elder/Admin management view. */
    fun observeGroupChatsForCongregation(congregationId: String): Flow<List<GroupChat>> =
        localChats().map { l -> l.filter { it.congregationId == congregationId } }

    /** Every group chat, any congregation — Super-Admin only (spec §5). */
    fun observeAllGroupChats(): Flow<List<GroupChat>> = localChats()

    fun observeGroupChat(groupChatId: String): Flow<GroupChat?> = localChats().map { l -> l.firstOrNull { it.id == groupChatId } }

    fun observeMessages(groupChatId: String): Flow<List<GroupChatMessage>> = localMessages(groupChatId)

    suspend fun createGroupChat(
        congregationId: String,
        groupName: String,
        description: String,
        participantIds: List<String>,
        createdByPersonId: String,
    ): GroupChat {
        // The creator (a Coordinator Elder/Admin/Super-Admin) is always a participant of their own group, even if they forgot to tick
        // their own name in the picker — otherwise they would immediately lose the ability to open the chat they just made.
        val chat = GroupChat(
            id = newLocalId(),
            congregationId = congregationId,
            groupName = groupName,
            description = description,
            participantIds = (participantIds + createdByPersonId).distinct(),
            createdByPersonId = createdByPersonId,
            createdAt = System.currentTimeMillis(),
        )
        saveChat(chat)
        return chat
    }

    suspend fun updateGroupChat(groupChatId: String, groupName: String, description: String) {
        offline.get<GroupChat>(COLLECTION, groupChatId)?.let { saveChat(it.copy(groupName = groupName, description = description)) }
    }

    /** "Add a participant / Remove a participant" — replaces the whole list. Message history is untouched (spec §12: preserve prior
     * messages for chat history and audit even after removal). */
    suspend fun updateParticipants(groupChatId: String, participantIds: List<String>) {
        offline.get<GroupChat>(COLLECTION, groupChatId)?.let { saveChat(it.copy(participantIds = participantIds.distinct())) }
    }

    suspend fun deleteGroupChat(groupChatId: String) {
        offline.delete(COLLECTION, groupChatId)
        runCatching { syncEngine.syncOnce() }
    }

    /** Sends [text] and/or an attachment, and rolls the parent [GroupChat]'s preview / [GroupChat.messageCount] forward in the same call —
     * what a group chat list row and the Chat Box's unread badge read, so it must never drift from what [observeMessages] shows. */
    suspend fun sendMessage(
        groupChatId: String,
        messageId: String? = null,
        senderId: String,
        senderName: String,
        senderRole: String,
        text: String,
        attachmentUrl: String? = null,
        attachmentFileName: String? = null,
        attachmentType: GroupChatAttachmentType? = null,
        attachmentSize: Long = 0L,
    ) {
        val now = System.currentTimeMillis()
        val message = GroupChatMessage(
            id = messageId ?: newLocalId(),
            senderId = senderId,
            senderName = senderName,
            senderRole = senderRole,
            text = text,
            attachmentUrl = attachmentUrl,
            attachmentFileName = attachmentFileName,
            attachmentType = attachmentType,
            attachmentSize = attachmentSize,
            createdAt = now,
        )
        saveMessage(groupChatId, message)
        offline.get<GroupChat>(COLLECTION, groupChatId)?.let { chat ->
            saveChat(
                chat.copy(
                    lastMessageText = text.ifBlank { null }, lastMessageSenderName = senderName, lastMessageIsAttachment = attachmentUrl != null,
                    lastMessageAt = now, messageCount = chat.messageCount + 1,
                ),
            )
        }
        runCatching { syncEngine.syncOnce() }
    }

    /** Marks every message in [groupChatId] read for [personId] — snapshots the chat's current [GroupChat.messageCount] into
     * [GroupChat.readCounts] for that one participant (see [GroupChat]'s doc comment for why this avoids a per-message unread query).
     * Call when a participant opens the chat screen and whenever a new message arrives while it is still open. */
    suspend fun markRead(groupChatId: String, personId: String) {
        val chat = offline.get<GroupChat>(COLLECTION, groupChatId) ?: return
        if (chat.readCounts[personId] != chat.messageCount) saveChat(chat.copy(readCounts = chat.readCounts + (personId to chat.messageCount)))
    }

    /** Uploads an attachment under a fixed per-message path and returns its download URL. [messageId] is a freshly generated id the
     * caller then passes into [sendMessage], so the stored file and the message line up. */
    suspend fun uploadAttachment(groupChatId: String, messageId: String, fileUri: Uri, fileName: String): String =
        files.upload("groupChats/$groupChatId/attachments/$messageId/$fileName", fileUri.toString())

    fun newMessageId(groupChatId: String): String = newLocalId()

    /** Sender-only text edit — an attachment, once sent, is immutable (see [GroupChatMessage.isEdited]'s doc comment). */
    suspend fun editMessage(groupChatId: String, messageId: String, newText: String) {
        offline.get<GroupChatMessage>("$COLLECTION/$groupChatId/$MESSAGES_SUBCOLLECTION", messageId)
            ?.let { saveMessage(groupChatId, it.copy(text = newText, isEdited = true, editedAt = System.currentTimeMillis())) }
        runCatching { syncEngine.syncOnce() }
    }

    /** "Delete for everyone" — sender-only soft delete: clears the content and removes the stored attachment (best effort); the message
     * line itself, and its place in the chat history, stays. */
    suspend fun deleteForEveryone(groupChatId: String, messageId: String, attachmentFileName: String?) {
        if (attachmentFileName != null) files.delete("groupChats/$groupChatId/attachments/$messageId/$attachmentFileName")
        offline.get<GroupChatMessage>("$COLLECTION/$groupChatId/$MESSAGES_SUBCOLLECTION", messageId)?.let {
            saveMessage(groupChatId, it.copy(text = "", attachmentUrl = null, attachmentFileName = null, attachmentType = null, attachmentSize = 0L, isDeletedForEveryone = true))
        }
        runCatching { syncEngine.syncOnce() }
    }

    /** "Delete for me" — hides this one message from [personId]'s own view only (see [GroupChatMessage.deletedForPersonIds]); any
     * participant may call this on any message, not just their own. */
    suspend fun deleteForMe(groupChatId: String, messageId: String, personId: String) {
        offline.get<GroupChatMessage>("$COLLECTION/$groupChatId/$MESSAGES_SUBCOLLECTION", messageId)
            ?.let { saveMessage(groupChatId, it.copy(deletedForPersonIds = (it.deletedForPersonIds + personId).distinct())) }
        runCatching { syncEngine.syncOnce() }
    }
}
