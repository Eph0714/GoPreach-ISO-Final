package com.emfitsolutions.gopreach.ui.screens.groupchat

import androidx.compose.material.icons.rounded.Delete
import com.emfitsolutions.gopreach.shared.resources.action_cancel
import com.emfitsolutions.gopreach.shared.resources.chat_delete_group_chat_title
import com.emfitsolutions.gopreach.shared.resources.chat_delete_group_chat_message
import com.emfitsolutions.gopreach.shared.resources.chat_delete_group_chat
import com.emfitsolutions.gopreach.shared.resources.chat_delete
import com.emfitsolutions.gopreach.shared.resources.Res
import com.emfitsolutions.gopreach.shared.resources.chat_attachment_preview
import com.emfitsolutions.gopreach.shared.resources.chat_empty_manage
import com.emfitsolutions.gopreach.shared.resources.chat_empty_member
import com.emfitsolutions.gopreach.shared.resources.chat_message_preview
import com.emfitsolutions.gopreach.shared.resources.chat_new_group_chat
import com.emfitsolutions.gopreach.shared.resources.chat_no_messages_yet
import com.emfitsolutions.gopreach.shared.resources.chat_participants_count
import com.emfitsolutions.gopreach.shared.resources.chat_setting_title
import androidx.compose.foundation.layout.Arrangement
import com.emfitsolutions.gopreach.ui.components.RecordFound
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.GroupChat
import com.emfitsolutions.gopreach.ui.components.CongregationFilterDropdown
import com.emfitsolutions.gopreach.ui.components.SelectCongregationPrompt
import com.emfitsolutions.gopreach.ui.components.rememberCongregationContext
import com.emfitsolutions.gopreach.ui.components.formatRecordTimestamp

/**
 * "Group Chat Setting" — list entry point. [canManage] (Coordinator Elder,
 * Admin, or Super-Admin) shows every group chat in [fixedCongregationId]
 * (null only for Super-Admin — "All Congregations", same convention every
 * other Manage screen uses) plus a "+ New Group Chat" FAB; everyone else
 * sees only the group chats they're actually a participant of (spec §6:
 * "provide access only to Group Chats where the logged-in user is an active
 * participant, unless the user has administrative permissions").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatListScreen(
    currentPersonId: String,
    canManage: Boolean,
    fixedCongregationId: String?,
    onBack: () -> Unit,
    onOpenGroupChat: (String) -> Unit,
    viewModel: GroupChatViewModel = koinViewModel(),
) {
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()
    // "Add a filter for Congregation" (Super-Admin only) — only meaningful
    // for the manage view; a plain participant already only ever sees their
    // own chats via [GroupChatViewModel.myGroupChats], not a congregation-wide
    // list.
    var congregationFilter by rememberCongregationContext("group_chats")
    val effectiveCongregationId = fixedCongregationId ?: congregationFilter
    val needsCongregation = canManage && fixedCongregationId == null && congregationFilter == null
    val chatsFlow = remember(canManage, effectiveCongregationId, currentPersonId, needsCongregation) {
        if (needsCongregation) return@remember kotlinx.coroutines.flow.flowOf(emptyList())
        if (canManage) viewModel.managedGroupChats(effectiveCongregationId) else viewModel.myGroupChats(currentPersonId)
    }
    val chats by chatsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var showCreateDialog by remember { mutableStateOf(false) }
    // The Overseer, Servant and Assistant of an FS Group: its own chat, opened (made the first time) from here.
    val mySlotGroup by remember(currentPersonId) { viewModel.fsGroupOf(currentPersonId) }.collectAsStateWithLifecycle(initialValue = null)
    // A group chat can be deleted right from this list by whoever manages group chats (the same people who could already
    // delete it from the chat's Settings; the server still limits them to their own congregation, Super-Admin to all).
    var pendingDelete by remember { mutableStateOf<GroupChat?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.chat_setting_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            if (canManage) {
                androidx.compose.material3.ExtendedFloatingActionButton(onClick = { showCreateDialog = true }, icon = { Icon(Icons.Rounded.Add, contentDescription = null) }, text = { Text(stringResource(Res.string.chat_new_group_chat)) })
            }
        },
    ) { padding ->
      Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        if (canManage && fixedCongregationId == null) {
            CongregationFilterDropdown(
                congregations = congregations,
                selectedCongregationId = congregationFilter,
                onSelected = { congregationFilter = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        mySlotGroup?.takeIf { !canManage }?.let { g ->
            androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("FS Group chat", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(g.name, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("You are the Overseer, Servant or Assistant of this group, so you manage its chat and who is in it.", style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.Button(onClick = { viewModel.openFsGroupChat(g, currentPersonId, onOpenGroupChat) }, modifier = Modifier.fillMaxWidth()) { Text("Open the chat and manage participants") }
                }
            }
        }
        if (needsCongregation) {
            SelectCongregationPrompt()
        } else if (chats.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RecordFound(0)
                Text(
                    if (canManage) stringResource(Res.string.chat_empty_manage) else stringResource(Res.string.chat_empty_member),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { RecordFound(chats.size) }
                items(chats, key = { it.id }) { chat ->
                    val congregationName = congregations.firstOrNull { it.id == chat.congregationId }?.name
                    GroupChatRow(
                        chat = chat,
                        congregationName = congregationName,
                        unreadCount = (chat.messageCount - (chat.readCounts[currentPersonId] ?: 0L)).coerceAtLeast(0L),
                        onClick = { onOpenGroupChat(chat.id) },
                        onDelete = if (canManage) ({ pendingDelete = chat }) else null,
                    )
                }
            }
        }
      }
    }

    pendingDelete?.let { chat ->
        com.emfitsolutions.gopreach.ui.components.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(Res.string.chat_delete_group_chat_title)) },
            text = { Text(stringResource(Res.string.chat_delete_group_chat_message, chat.groupName)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pendingDelete = null
                    viewModel.deleteGroupChat(chat, currentPersonId) {}
                }) { Text(stringResource(Res.string.chat_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }

    if (showCreateDialog) {
        CreateGroupChatDialog(
            fixedCongregationId = fixedCongregationId,
            congregations = congregations,
            currentPersonId = currentPersonId,
            viewModel = viewModel,
            onDismiss = { showCreateDialog = false },
            onCreated = { chat -> showCreateDialog = false; onOpenGroupChat(chat.id) },
        )
    }
}

@Composable
private fun GroupChatRow(chat: GroupChat, congregationName: String?, unreadCount: Long, onClick: () -> Unit, onDelete: (() -> Unit)? = null) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(chat.groupName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(congregationName, stringResource(Res.string.chat_participants_count, chat.participantIds.size)).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val preview = when {
                    chat.lastMessageIsAttachment -> stringResource(Res.string.chat_attachment_preview, chat.lastMessageSenderName ?: "")
                    chat.lastMessageText != null -> stringResource(Res.string.chat_message_preview, chat.lastMessageSenderName ?: "", chat.lastMessageText ?: "")
                    else -> stringResource(Res.string.chat_no_messages_yet)
                }
                Text(preview, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                if (chat.lastMessageAt != null) {
                    Text(formatRecordTimestamp(chat.lastMessageAt!!), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (unreadCount > 0) {
                    Box(modifier = Modifier.padding(top = 4.dp)) {
                        Badge { Text(if (unreadCount > 99) "99+" else unreadCount.toString()) }
                    }
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(Res.string.chat_delete_group_chat), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
