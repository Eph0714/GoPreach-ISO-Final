package com.emfitsolutions.gopreach.ui.screens.groupchat

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.GroupChat
import com.emfitsolutions.gopreach.data.model.GroupChatAttachmentType
import com.emfitsolutions.gopreach.data.model.GroupChatMessage
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.model.displayLabel
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.GroupChatRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "GroupChatViewModel"

/** One selectable participant in the "add participants" picker — a person
 * with an active role (Publisher or Admin-track) in the target congregation
 * (spec: "the participants will be only under the congregation they
 * belong"). */
data class ParticipantCandidate(
    val personId: String,
    val name: String,
    val roleLabel: String,
    val publisherName: String,
)

/**
 * "Group Chat Setting" module — backs the group-chat list (participant's own
 * chats, plus a Coordinator Elder/Admin/Super-Admin's management view),
 * create/settings dialog, chat screen, and Shared Documents view. One
 * ViewModel for all of it, same "one ViewModel per feature area" shape as
 * [com.emfitsolutions.gopreach.ui.screens.announcements.ManageAnnouncementsViewModel].
 */
@HiltViewModel
class GroupChatViewModel @Inject constructor(
    private val groupChatRepository: GroupChatRepository,
    private val personRepository: PersonRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val auditLogRepository: AuditLogRepository,
    congregationRepository: CongregationRepository,
) : ViewModel() {

    /** Super-Admin's own congregation dropdown when creating a group chat —
     * every other role's congregation is fixed (see [ParticipantCandidate]
     * doc/GroupChatListScreen's own read-only congregation display). */
    val congregations: StateFlow<List<Congregation>> =
        congregationRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun myGroupChats(personId: String): Flow<List<GroupChat>> =
        groupChatRepository.observeGroupChatsForParticipant(personId)

    /** Feeds [com.emfitsolutions.gopreach.ui.components.ChatBoxIcon] — this
     * person's own group chats, each paired with its congregation name and
     * this viewer's own unread count, newest activity first. */
    fun chatBoxEntriesFor(personId: String): Flow<List<com.emfitsolutions.gopreach.ui.components.ChatBoxEntry>> = combine(
        myGroupChats(personId),
        congregations,
    ) { chats, congregationList ->
        val congregationNameById = congregationList.associateBy({ it.id }, { it.name })
        chats
            .map { chat ->
                com.emfitsolutions.gopreach.ui.components.ChatBoxEntry(
                    chat = chat,
                    congregationName = congregationNameById[chat.congregationId],
                    unreadCount = (chat.messageCount - (chat.readCounts[personId] ?: 0L)).coerceAtLeast(0L),
                )
            }
            .sortedByDescending { it.chat.lastMessageAt ?: it.chat.createdAt }
    }

    /** The Coordinator Elder/Admin/Super-Admin management list — every group
     * chat in [congregationId], or every group chat at all if null
     * (Super-Admin's "All Congregations" scope, same convention every other
     * Manage screen in this app uses). */
    fun managedGroupChats(congregationId: String?): Flow<List<GroupChat>> =
        if (congregationId == null) groupChatRepository.observeAllGroupChats()
        else groupChatRepository.observeGroupChatsForCongregation(congregationId)

    /** Congregation-scoped candidate list for the participant picker — same
     * "every active Publisher/Admin-track role, one row per real person"
     * shape as [com.emfitsolutions.gopreach.ui.screens.contactrecord
     * .ContactRecordViewModel.rowsFor], simplified to just what the picker
     * needs (name, one role label, search text). */
    fun candidatesFor(congregationId: String?): Flow<List<ParticipantCandidate>> = combine(
        personRepository.observeAll(),
        roleAssignmentRepository.observeAll(),
    ) { people, assignments ->
        if (congregationId == null) return@combine emptyList()
        val peopleById = people.associateBy { it.id }
        assignments
            .filter { it.status == RoleAssignmentStatus.ACTIVE && it.congregationId == congregationId && it.personId in peopleById }
            .mapNotNull { assignment ->
                val person = peopleById[assignment.personId] ?: return@mapNotNull null
                val roleLabel = when (val roleType = assignment.resolvedRoleTypeOrNull()) {
                    is RoleType.Admin -> roleType.role.displayLabel()
                    is RoleType.Publisher -> roleType.category.displayLabel() ?: return@mapNotNull null
                    null -> return@mapNotNull null
                }
                ParticipantCandidate(
                    personId = person.id,
                    name = person.fullName,
                    roleLabel = roleLabel,
                    publisherName = person.username,
                )
            }
            .distinctBy { it.personId }
            .sortedBy { it.name }
    }

    fun createGroupChat(
        congregationId: String,
        groupName: String,
        description: String,
        participantIds: List<String>,
        createdByPersonId: String,
        onCreated: (GroupChat) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                val chat = groupChatRepository.createGroupChat(congregationId, groupName, description, participantIds, createdByPersonId)
                auditLogRepository.log(
                    actorPersonId = createdByPersonId,
                    action = "CREATE_GROUP_CHAT",
                    targetType = "GroupChat",
                    targetId = chat.id,
                    congregationId = congregationId,
                )
                onCreated(chat)
            } catch (e: Exception) {
                Log.e(TAG, "createGroupChat failed", e)
            }
        }
    }

    fun updateGroupChat(groupChatId: String, groupName: String, description: String) {
        viewModelScope.launch { runCatching { groupChatRepository.updateGroupChat(groupChatId, groupName, description) } }
    }

    fun updateParticipants(groupChatId: String, congregationId: String, participantIds: List<String>, actorPersonId: String) {
        viewModelScope.launch {
            runCatching { groupChatRepository.updateParticipants(groupChatId, participantIds) }
                .onSuccess {
                    auditLogRepository.log(
                        actorPersonId = actorPersonId,
                        action = "UPDATE_GROUP_CHAT_PARTICIPANTS",
                        targetType = "GroupChat",
                        targetId = groupChatId,
                        congregationId = congregationId,
                    )
                }
        }
    }

    fun deleteGroupChat(chat: GroupChat, actorPersonId: String, onDeleted: () -> Unit) {
        viewModelScope.launch {
            runCatching { groupChatRepository.deleteGroupChat(chat.id) }
                .onSuccess {
                    auditLogRepository.log(
                        actorPersonId = actorPersonId,
                        action = "DELETE_GROUP_CHAT",
                        targetType = "GroupChat",
                        targetId = chat.id,
                        congregationId = chat.congregationId,
                    )
                    onDeleted()
                }
        }
    }

    fun groupChat(groupChatId: String): Flow<GroupChat?> = groupChatRepository.observeGroupChat(groupChatId)

    /** [viewerPersonId]'s own message list — excludes whatever they've
     * individually "deleted for me" (see [GroupChatMessage
     * .deletedForPersonIds]); everyone else still sees those messages
     * normally, since deletion-for-me never touches the shared doc's real
     * content. */
    fun messages(groupChatId: String, viewerPersonId: String): Flow<List<GroupChatMessage>> =
        groupChatRepository.observeMessages(groupChatId).map { list -> list.filterNot { viewerPersonId in it.deletedForPersonIds } }

    fun sendText(groupChatId: String, senderId: String, senderName: String, senderRole: String, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            runCatching { groupChatRepository.sendMessage(groupChatId = groupChatId, senderId = senderId, senderName = senderName, senderRole = senderRole, text = text.trim()) }
                .onFailure { Log.e(TAG, "sendText failed", it) }
        }
    }

    /** Uploads [fileUri] then sends it as a message (spec §8: image/PDF/
     * Word/Excel attachments) — [text] may be blank (an attachment-only
     * message, same as a photo message in any other chat app). */
    fun sendAttachment(
        groupChatId: String,
        senderId: String,
        senderName: String,
        senderRole: String,
        text: String,
        fileUri: Uri,
        fileName: String,
        fileSize: Long,
        attachmentType: GroupChatAttachmentType,
        onFailed: () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                val messageId = groupChatRepository.newMessageId(groupChatId)
                val url = groupChatRepository.uploadAttachment(groupChatId, messageId, fileUri, fileName)
                groupChatRepository.sendMessage(
                    groupChatId = groupChatId,
                    messageId = messageId,
                    senderId = senderId,
                    senderName = senderName,
                    senderRole = senderRole,
                    text = text.trim(),
                    attachmentUrl = url,
                    attachmentFileName = fileName,
                    attachmentType = attachmentType,
                    attachmentSize = fileSize,
                )
            } catch (e: Exception) {
                Log.e(TAG, "sendAttachment failed", e)
                onFailed()
            }
        }
    }

    fun editMessage(groupChatId: String, messageId: String, newText: String) {
        if (newText.isBlank()) return
        viewModelScope.launch {
            runCatching { groupChatRepository.editMessage(groupChatId, messageId, newText.trim()) }
                .onFailure { Log.e(TAG, "editMessage failed", it) }
        }
    }

    /** Sender-only — enforced again server-side (see firestore.rules), this
     * is UI-side gating so the option isn't even offered on someone else's
     * message in the first place. */
    fun deleteForEveryone(groupChatId: String, message: GroupChatMessage) {
        viewModelScope.launch {
            runCatching { groupChatRepository.deleteForEveryone(groupChatId, message.id, message.attachmentFileName) }
                .onFailure { Log.e(TAG, "deleteForEveryone failed", it) }
        }
    }

    fun deleteForMe(groupChatId: String, messageId: String, personId: String) {
        viewModelScope.launch {
            runCatching { groupChatRepository.deleteForMe(groupChatId, messageId, personId) }
                .onFailure { Log.e(TAG, "deleteForMe failed", it) }
        }
    }

    fun markRead(groupChatId: String, personId: String) {
        viewModelScope.launch { runCatching { groupChatRepository.markRead(groupChatId, personId) } }
    }
}

/** Removed Publisher is excluded (null) — they can no longer sign in, so
 * they can't be a usable Group Chat participant regardless of any stale
 * RoleAssignment left behind. */
private fun com.emfitsolutions.gopreach.data.model.PublisherCategory.displayLabel(): String? = when (this) {
    com.emfitsolutions.gopreach.data.model.PublisherCategory.REGULAR_PIONEER -> "Regular Pioneer"
    com.emfitsolutions.gopreach.data.model.PublisherCategory.SPECIAL_PIONEER -> "Special Pioneer"
    com.emfitsolutions.gopreach.data.model.PublisherCategory.AUXILIARY_PIONEER -> "Auxiliary Pioneer"
    com.emfitsolutions.gopreach.data.model.PublisherCategory.REGULAR_PUBLISHER -> "Publisher"
    com.emfitsolutions.gopreach.data.model.PublisherCategory.UNBAPTIZED_PUBLISHER -> "Unbaptized Publisher"
    com.emfitsolutions.gopreach.data.model.PublisherCategory.IRREGULAR_PUBLISHER -> "Irregular Publisher"
    com.emfitsolutions.gopreach.data.model.PublisherCategory.INACTIVE_PUBLISHER -> "Inactive Publisher"
    com.emfitsolutions.gopreach.data.model.PublisherCategory.REPROOF_PUBLISHER -> "Reproof Publisher"
    com.emfitsolutions.gopreach.data.model.PublisherCategory.REMOVED_PUBLISHER -> null
}
