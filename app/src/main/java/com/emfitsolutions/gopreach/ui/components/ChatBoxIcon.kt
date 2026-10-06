package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.R
import com.emfitsolutions.gopreach.data.model.GroupChat

/** One row of the Chat Box dropdown — a [GroupChat] plus whatever's already
 * been resolved about it (congregation name, this viewer's own unread
 * count) so the composable itself does no lookups. */
data class ChatBoxEntry(val chat: GroupChat, val congregationName: String?, val unreadCount: Long)

/**
 * "Every active member of a Group Chat must have access to a Chat Box icon
 * in the upper-right corner" (spec §6) — same bell-icon-with-badge shape as
 * [NotificationBell], deliberately a separate, visually distinct icon
 * (chat bubble, not a bell) so the two read as different things at a
 * glance. Reads from the signed-in Person's own [GroupChat.participantIds]
 * membership (see [com.emfitsolutions.gopreach.ui.screens.groupchat
 * .GroupChatViewModel.myGroupChats]), so it survives switching between a
 * Publisher account and a higher-rank account for the same authenticated
 * user with no extra plumbing — membership is keyed by personId, not role.
 */
@Composable
fun ChatBoxIcon(
    entries: List<ChatBoxEntry>,
    onOpenGroupChat: (String) -> Unit,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = Color.White,
) {
    var expanded by remember { mutableStateOf(false) }
    val totalUnread = entries.sumOf { it.unreadCount }
    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            BadgedBox(badge = {
                if (totalUnread > 0) Badge { Text(if (totalUnread > 99) "99+" else totalUnread.toString()) }
            }) {
                Icon(Icons.Rounded.ChatBubble, contentDescription = stringResource(R.string.chat_title_fallback), tint = iconTint)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 280.dp, max = 340.dp),
        ) {
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.chat_empty_member),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).imePadding()) {
                    entries.forEach { entry ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(
                                        Icons.Rounded.Groups,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 2.dp, end = 8.dp),
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            entry.chat.groupName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (entry.congregationName != null) {
                                            Text(entry.congregationName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        val preview = when {
                                            entry.chat.lastMessageIsAttachment -> stringResource(R.string.chat_attachment_preview, entry.chat.lastMessageSenderName ?: "")
                                            entry.chat.lastMessageText != null -> stringResource(R.string.chat_message_preview, entry.chat.lastMessageSenderName ?: "", entry.chat.lastMessageText ?: "")
                                            else -> stringResource(R.string.chat_no_messages_yet)
                                        }
                                        Text(preview, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (entry.unreadCount > 0) {
                                        Badge { Text(if (entry.unreadCount > 99) "99+" else entry.unreadCount.toString()) }
                                    }
                                }
                            },
                            onClick = { expanded = false; onOpenGroupChat(entry.chat.id) },
                        )
                    }
                    Divider()
                    TextButton(
                        onClick = { expanded = false; onViewAll() },
                        modifier = Modifier.padding(8.dp),
                    ) { Text(stringResource(R.string.chat_view_all_group_chats)) }
                }
            }
        }
    }
}

// "Fix the Notification Sound system" — the "Group Chat Message" notifier
// (GroupChatMessageNotifier) used to live here as a @Composable nested in
// the Home screens; it's now ported into NotificationSoundCoordinator
// (Application-scoped, running regardless of which screen is on top or
// whether the app is merely backgrounded) — see that class's own doc
// comment for why, and its `notifyOnChatArrivals` for the ported logic
// (unchanged: keyed on [ChatBoxEntry.unreadCount] rising for a given chat,
// which is what keeps this from ever notifying someone about their own
// message — a message this viewer sent themselves never increases their own
// unread count, the same signal [ChatBoxIcon]'s own badge already trusts).
