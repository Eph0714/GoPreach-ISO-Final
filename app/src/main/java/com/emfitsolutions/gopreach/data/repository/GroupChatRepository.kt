package com.emfitsolutions.gopreach.data.repository

import android.net.Uri
import android.util.Log
import com.emfitsolutions.gopreach.data.model.GroupChat
import com.emfitsolutions.gopreach.data.model.GroupChatAttachmentType
import com.emfitsolutions.gopreach.data.model.GroupChatMessage
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GroupChatRepository"
private const val COLLECTION = "groupChats"
private const val MESSAGES_SUBCOLLECTION = "messages"

/**
 * "Group Chat Setting" module. Unlike almost every other repository in this
 * app, this one talks to Firestore **directly** — no [com.emfitsolutions
 * .gopreach.data.sync.OfflineFirestoreRepository] queue, no waiting for a
 * manual "Sync to Server" tap. A chat that only delivers messages once the
 * user remembers to sync isn't a chat; this deliberately breaks from the
 * app's offline-first convention the same way the one existing "push
 * straight to Firestore now" exception ([PersonRepository.saveNow]) already
 * does, just for every write, not just one.
 */
@Singleton
class GroupChatRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
) {
    private fun chats() = firestore.collection(COLLECTION)
    private fun messages(groupChatId: String) = chats().document(groupChatId).collection(MESSAGES_SUBCOLLECTION)

    /** Every group chat the given person is currently a participant of —
     * feeds both the Chat Box icon and a Publisher/Elder's own "My Group
     * Chats" list. Removed participants stop matching this query the
     * instant [updateParticipants] drops their id, which is what actually
     * makes a removed participant lose access (spec §12). */
    fun observeGroupChatsForParticipant(personId: String): Flow<List<GroupChat>> =
        observeQuery(chats().whereArrayContains("participantIds", personId))

    /** Every group chat in one congregation — the Coordinator Elder/Admin
     * management view (spec §2-4: they may manage every group chat in their
     * own congregation, not just ones they personally created/joined). */
    fun observeGroupChatsForCongregation(congregationId: String): Flow<List<GroupChat>> =
        observeQuery(chats().whereEqualTo("congregationId", congregationId))

    /** Every group chat, any congregation — Super-Admin only (spec §5). */
    fun observeAllGroupChats(): Flow<List<GroupChat>> = observeQuery(chats())

    fun observeGroupChat(groupChatId: String): Flow<GroupChat?> = callbackFlow {
        val registration = chats().document(groupChatId).addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "observeGroupChat($groupChatId) failed: ${error.message}")
                return@addSnapshotListener
            }
            trySend(snapshot?.toObject(GroupChat::class.java))
        }
        awaitClose { registration.remove() }
    }

    fun observeMessages(groupChatId: String): Flow<List<GroupChatMessage>> = callbackFlow {
        val registration = messages(groupChatId)
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "observeMessages($groupChatId) failed: ${error.message}")
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents?.mapNotNull { it.toObject(GroupChatMessage::class.java) } ?: emptyList())
            }
        awaitClose { registration.remove() }
    }

    private fun observeQuery(query: Query): Flow<List<GroupChat>> = callbackFlow {
        val registration = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "Group chat query failed: ${error.message}")
                return@addSnapshotListener
            }
            trySend(snapshot?.documents?.mapNotNull { it.toObject(GroupChat::class.java) } ?: emptyList())
        }
        awaitClose { registration.remove() }
    }

    suspend fun createGroupChat(
        congregationId: String,
        groupName: String,
        description: String,
        participantIds: List<String>,
        createdByPersonId: String,
    ): GroupChat {
        val id = chats().document().id
        // The creator (a Coordinator Elder/Admin/Super-Admin) is always a
        // participant of their own group, even if they forgot to tick their
        // own name in the picker — otherwise they'd immediately lose the
        // ability to open the chat they just made.
        val chat = GroupChat(
            id = id,
            congregationId = congregationId,
            groupName = groupName,
            description = description,
            participantIds = (participantIds + createdByPersonId).distinct(),
            createdByPersonId = createdByPersonId,
            createdAt = System.currentTimeMillis(),
        )
        chats().document(id).set(chat).await()
        return chat
    }

    suspend fun updateGroupChat(groupChatId: String, groupName: String, description: String) {
        chats().document(groupChatId).update(
            mapOf("groupName" to groupName, "description" to description),
        ).await()
    }

    /** "Add a participant / Remove a participant" — replaces the whole
     * array. Message history is untouched (spec §12: preserve prior
     * messages for chat history and audit even after removal). */
    suspend fun updateParticipants(groupChatId: String, participantIds: List<String>) {
        chats().document(groupChatId).update("participantIds", participantIds.distinct()).await()
    }

    suspend fun deleteGroupChat(groupChatId: String) {
        // Best-effort: message docs (and whatever they attached in Storage)
        // are left behind rather than paginating/batch-deleting a
        // potentially large subcollection from the client — acceptable for
        // a Super-Admin-only, rarely-used action; nothing else can still
        // reach them once the parent doc (and therefore every rule check
        // above) is gone.
        chats().document(groupChatId).delete().await()
    }

    /** Sends [text] and/or an attachment, and rolls the parent [GroupChat]'s
     * preview/[GroupChat.messageCount] forward in the same call — this is
     * what a group chat list row and the Chat Box's unread badge actually
     * read, so it must never drift from what [observeMessages] shows. */
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
        val messageRef = if (messageId != null) messages(groupChatId).document(messageId) else messages(groupChatId).document()
        firestore.runBatch { batch ->
            batch.set(messageRef, message)
            batch.update(
                chats().document(groupChatId),
                mapOf(
                    "lastMessageText" to (text.ifBlank { null }),
                    "lastMessageSenderName" to senderName,
                    "lastMessageIsAttachment" to (attachmentUrl != null),
                    "lastMessageAt" to now,
                    "messageCount" to FieldValue.increment(1),
                ),
            )
        }.await()
    }

    /** Marks every message in [groupChatId] read for [personId] — snapshots
     * the chat's current [GroupChat.messageCount] into
     * [GroupChat.readCounts] for that one participant (see [GroupChat]'s
     * doc comment for why this avoids a per-message unread query). Call
     * when a participant opens the chat screen and whenever a new message
     * arrives while it's still open. */
    suspend fun markRead(groupChatId: String, personId: String) {
        val chatDoc = chats().document(groupChatId).get().await()
        val chat = chatDoc.toObject(GroupChat::class.java) ?: return
        chats().document(groupChatId).update("readCounts.$personId", chat.messageCount).await()
    }

    /** Uploads an attachment to a fixed per-message Storage path and
     * returns its download URL — same "upload, then read back the URL"
     * shape as [AnnouncementRepository.uploadAttachment]. [messageId] is a
     * freshly-generated id the caller then passes into [sendMessage]'s
     * message doc, so the Storage path and the Firestore doc line up. */
    suspend fun uploadAttachment(groupChatId: String, messageId: String, fileUri: Uri, fileName: String): String {
        val ref = storage.reference.child("groupChats/$groupChatId/attachments/$messageId/$fileName")
        ref.putFile(fileUri).await()
        return ref.downloadUrl.await().toString()
    }

    fun newMessageId(groupChatId: String): String = messages(groupChatId).document().id

    /** Sender-only text edit — an attachment, once sent, is immutable (see
     * [GroupChatMessage.isEdited]'s doc comment). */
    suspend fun editMessage(groupChatId: String, messageId: String, newText: String) {
        messages(groupChatId).document(messageId).update(
            mapOf("text" to newText, "isEdited" to true, "editedAt" to System.currentTimeMillis()),
        ).await()
    }

    /** "Delete for everyone" — sender-only soft delete: clears content, the
     * doc itself (and its place in chat history/audit) stays. Best-effort
     * cleans up the attachment's Storage object, if any, same "not worth
     * failing the whole operation over" trade-off [deleteGroupChat] already
     * accepts. */
    suspend fun deleteForEveryone(groupChatId: String, messageId: String, attachmentFileName: String?) {
        if (attachmentFileName != null) {
            runCatching { storage.reference.child("groupChats/$groupChatId/attachments/$messageId/$attachmentFileName").delete().await() }
        }
        messages(groupChatId).document(messageId).update(
            mapOf(
                "text" to "",
                "attachmentUrl" to null,
                "attachmentFileName" to null,
                "attachmentType" to null,
                "attachmentSize" to 0L,
                "isDeletedForEveryone" to true,
            ),
        ).await()
    }

    /** "Delete for me" — hides this one message from [personId]'s own view
     * only (see [GroupChatMessage.deletedForPersonIds]); any participant
     * may call this on any message, not just their own. */
    suspend fun deleteForMe(groupChatId: String, messageId: String, personId: String) {
        messages(groupChatId).document(messageId).update("deletedForPersonIds", FieldValue.arrayUnion(personId)).await()
    }
}
