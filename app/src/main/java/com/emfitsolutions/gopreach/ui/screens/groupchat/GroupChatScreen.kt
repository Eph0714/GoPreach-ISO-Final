package com.emfitsolutions.gopreach.ui.screens.groupchat

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.emfitsolutions.gopreach.R
import com.emfitsolutions.gopreach.data.model.GroupChatAttachmentType
import com.emfitsolutions.gopreach.data.model.GroupChatMessage
import com.emfitsolutions.gopreach.ui.components.formatRecordTimestamp
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import androidx.compose.ui.window.DialogProperties

/** A picked-but-not-yet-sent attachment — shown as its own row above the
 * input box (spec §8: "show file name, file type/icon, file size, upload
 * progress, cancel upload option"). */
private data class PendingAttachment(val uri: Uri, val fileName: String, val fileSize: Long, val type: GroupChatAttachmentType)

/**
 * "Group Chat Interface" (spec §7-8) — header, chronological message
 * bubbles, and a text+attach+send input row, plus Settings (participants,
 * gated to [canManageSettings]) and Shared Documents entry points in the top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    groupChatId: String,
    currentPersonId: String,
    currentPersonName: String,
    currentPersonRoleLabel: String,
    canManageSettings: Boolean,
    onBack: () -> Unit,
    viewModel: GroupChatViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val showToast = rememberActionToast()
    val chatFlow = remember(groupChatId) { viewModel.groupChat(groupChatId) }
    val chat by chatFlow.collectAsStateWithLifecycle(initialValue = null)
    val messagesFlow = remember(groupChatId, currentPersonId) { viewModel.messages(groupChatId, currentPersonId) }
    val messages by messagesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // "Allow the user only to view the chat he/she belongs to" — Firestore
    // rules already refuse the read server-side for anyone who isn't a
    // participant (or, per spec, a Coordinator Elder/Admin/Super-Admin
    // managing their own congregation — see [canManageSettings]); this is
    // the UI half of that, since a denied listener just never emits rather
    // than throwing, so without this an unauthorized open would otherwise
    // sit on a blank screen forever instead of a clear system message. Once
    // [chat] has actually loaded, membership is checked directly against
    // its own participant list — covers a participant who gets removed
    // while the chat is still open, not just someone opening a link they
    // were never sent. [showNotAvailable] only flips after a short grace
    // window so the very first (still-loading) frame doesn't flash it.
    val isParticipant = chat?.participantIds?.contains(currentPersonId) == true
    val isAuthorized = chat != null && (isParticipant || canManageSettings)
    var showNotAvailable by remember(groupChatId) { mutableStateOf(false) }
    LaunchedEffect(groupChatId, chat) {
        if (chat == null) {
            kotlinx.coroutines.delay(4000)
            if (chat == null) showNotAvailable = true
        } else {
            showNotAvailable = false
        }
    }
    // Deliberately NOT an early `return` here — every remember/LaunchedEffect
    // below this point must still run on every recomposition regardless of
    // [isAuthorized]/[showNotAvailable] (Compose requires the same
    // composable calls in the same order every time); only the actual
    // rendered content branches on them, at the very bottom of this function.

    var showSettings by remember { mutableStateOf(false) }
    var showDocuments by remember { mutableStateOf(false) }
    var messageText by remember { mutableStateOf("") }
    var pendingAttachment by remember { mutableStateOf<PendingAttachment?>(null) }
    var isUploading by remember { mutableStateOf(false) }
    // "Allow the sender to edit and delete (to everyone, delete for me) the
    // message he sent" — editingMessage drives an edit dialog;
    // pendingDeleteForEveryone a confirmation (an irreversible, everyone-
    // sees-it action, unlike "delete for me").
    var editingMessage by remember { mutableStateOf<GroupChatMessage?>(null) }
    var pendingDeleteForEveryone by remember { mutableStateOf<GroupChatMessage?>(null) }

    // Opening the chat (and every time a new message arrives while it's
    // open) marks it read — this is what clears the Chat Box/list unread
    // badge for this participant (spec §14: "real-time unread-message count
    // updates").
    LaunchedEffect(groupChatId, currentPersonId, messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
        viewModel.markRead(groupChatId, currentPersonId)
    }

    val unsupportedFileTypeMessage = stringResource(R.string.chat_unsupported_file_type)
    val uploadFailedMessage = stringResource(R.string.chat_upload_failed)
    val pickAttachment = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = queryFileName(context, uri) ?: uri.lastPathSegment ?: "file"
            val type = inferAttachmentType(context, uri, name)
            if (type == null) {
                showToast(unsupportedFileTypeMessage)
            } else {
                val size = queryFileSize(context, uri)
                pendingAttachment = PendingAttachment(uri, name, size, type)
            }
        }
    }

    fun send() {
        val text = messageText
        val attachment = pendingAttachment
        if (text.isBlank() && attachment == null) return
        if (attachment != null) {
            isUploading = true
            viewModel.sendAttachment(
                groupChatId = groupChatId,
                senderId = currentPersonId,
                senderName = currentPersonName,
                senderRole = currentPersonRoleLabel,
                text = text,
                fileUri = attachment.uri,
                fileName = attachment.fileName,
                fileSize = attachment.fileSize,
                attachmentType = attachment.type,
                onFailed = { isUploading = false; showToast(uploadFailedMessage) },
            )
            isUploading = false
            pendingAttachment = null
        } else {
            viewModel.sendText(groupChatId, currentPersonId, currentPersonName, currentPersonRoleLabel, text)
        }
        messageText = ""
    }

    if (chat != null && !isAuthorized) {
        UnauthorizedGroupChatScreen(onBack = onBack)
        return
    }
    if (showNotAvailable) {
        UnauthorizedGroupChatScreen(
            message = stringResource(R.string.chat_not_available_message),
            onBack = onBack,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(chat?.groupName ?: stringResource(R.string.chat_title_fallback), style = MaterialTheme.typography.titleMedium)
                        val congregationName = congregations.firstOrNull { it.id == chat?.congregationId }?.name
                        Text(
                            listOfNotNull(congregationName, chat?.let { stringResource(R.string.chat_participants_header, it.participantIds.size) }).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { showDocuments = true }) {
                        Icon(Icons.Rounded.Folder, contentDescription = stringResource(R.string.chat_shared_documents_cd))
                    }
                    if (canManageSettings) {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.chat_group_settings_cd))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        isOwnMessage = message.senderId == currentPersonId,
                        onEdit = { editingMessage = message },
                        onDeleteForEveryone = { pendingDeleteForEveryone = message },
                        onDeleteForMe = { viewModel.deleteForMe(groupChatId, message.id, currentPersonId) },
                    )
                }
            }

            pendingAttachment?.let { attachment ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(attachment.type.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(attachment.fileName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        Text(formatFileSize(attachment.fileSize), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (isUploading) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp).widthIn(max = 20.dp))
                    } else {
                        IconButton(onClick = { pendingAttachment = null }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.chat_cancel_attachment_cd))
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { pickAttachment.launch(GROUP_CHAT_ATTACHMENT_MIME_TYPES) }) {
                    Icon(Icons.Rounded.AttachFile, contentDescription = stringResource(R.string.chat_attach_file_cd))
                }
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    placeholder = { Text(stringResource(R.string.chat_type_message_placeholder)) },
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = ::send, enabled = !isUploading) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = stringResource(R.string.chat_send_cd), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    val currentChat = chat
    if (showSettings && currentChat != null) {
        ManageParticipantsDialog(
            chat = currentChat,
            currentPersonId = currentPersonId,
            viewModel = viewModel,
            onDismiss = { showSettings = false },
            onDeleted = { showSettings = false; onBack() },
        )
    }
    if (showDocuments && currentChat != null) {
        SharedDocumentsDialog(groupName = currentChat.groupName, messages = messages, onDismiss = { showDocuments = false })
    }

    val toEdit = editingMessage
    if (toEdit != null) {
        EditMessageDialog(
            message = toEdit,
            onDismiss = { editingMessage = null },
            onSave = { newText -> viewModel.editMessage(groupChatId, toEdit.id, newText); editingMessage = null },
        )
    }
    val toDeleteForEveryone = pendingDeleteForEveryone
    if (toDeleteForEveryone != null) {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { pendingDeleteForEveryone = null },
            title = { Text(stringResource(R.string.chat_delete_for_everyone_title)) },
            text = { Text(stringResource(R.string.chat_delete_for_everyone_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteForEveryone(groupChatId, toDeleteForEveryone)
                    pendingDeleteForEveryone = null
                }) { Text(stringResource(R.string.chat_delete_for_everyone_confirm)) }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteForEveryone = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** "Allow the user only to view the chat he/she belong[s to]" — shown in
 * place of the chat itself when [GroupChatScreen] determines the current
 * viewer isn't (or is no longer) authorized to see it: opened a stale/
 * shared link to a chat they were never added to, or were removed from
 * participants while it was still open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnauthorizedGroupChatScreen(
    message: String = stringResource(R.string.chat_unauthorized_message),
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chat_title_fallback)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("❌ $message", style = MaterialTheme.typography.bodyLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** Sender-only text edit (spec: "allow the sender... to edit... the message
 * he sent") — an attachment, once sent, stays as-is; only [GroupChatMessage
 * .text] is editable. */
@Composable
private fun EditMessageDialog(message: GroupChatMessage, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(message.id) { mutableStateOf(message.text) }
    AlertDialog(
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_edit_message_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onSave(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun MessageBubble(
    message: GroupChatMessage,
    isOwnMessage: Boolean,
    onEdit: () -> Unit,
    onDeleteForEveryone: () -> Unit,
    onDeleteForMe: () -> Unit,
) {
    if (message.isDeletedForEveryone) {
        DeletedMessagePlaceholder(isOwnMessage)
        return
    }
    val bubbleColor = if (isOwnMessage) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    // "Check if the background color is suited to the text" — the bubble's
    // background is a theme color (primaryContainer/surfaceVariant, which on
    // this app's violet theme skews dark and saturated for the sent side),
    // so its text can't just default to whatever LocalContentColor happens
    // to be from the surrounding screen; it needs the actual "on" color
    // Material3 defines *for that specific container color* — the same
    // pairing the design system itself guarantees is readable (e.g.
    // onPrimaryContainer for a primaryContainer/violet background). Providing
    // it once here, instead of hard-coding a color on every Text/Icon below,
    // is also what makes every child inside this bubble correct automatically.
    val contentColor = MaterialTheme.colorScheme.contentColorFor(bubbleColor)
    val mutedContentColor = contentColor.copy(alpha = 0.75f)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isOwnMessage) Arrangement.End else Arrangement.Start) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(bubbleColor, RoundedCornerShape(14.dp))
                    .padding(10.dp),
            ) {
                if (!isOwnMessage) {
                    Text(message.senderName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = contentColor)
                    Text(message.senderRole, style = MaterialTheme.typography.labelSmall, color = mutedContentColor)
                } else {
                    // "Allow the sender... to edit and delete (to everyone,
                    // delete for me) the chat he sent" — own-message-only
                    // menu, right-aligned above the bubble's own content.
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        MessageMenuButton(contentColor = contentColor, onEdit = onEdit, onDeleteForEveryone = onDeleteForEveryone, onDeleteForMe = onDeleteForMe)
                    }
                }
                if (message.attachmentUrl != null) {
                    val context = LocalContext.current
                    val attachmentFallback = stringResource(R.string.chat_attachment_fallback)
                    if (message.attachmentType == GroupChatAttachmentType.IMAGE) {
                        AsyncImage(
                            model = message.attachmentUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(160.dp).padding(top = 4.dp, bottom = 4.dp)
                                .clickable { com.emfitsolutions.gopreach.data.export.CsvExporter.openWithChooser(context, Uri.parse(message.attachmentUrl), "image/*") },
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                .clickable { com.emfitsolutions.gopreach.data.export.CsvExporter.openWithChooser(context, Uri.parse(message.attachmentUrl), "*/*") },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // A plain "primary" tint here would fight the
                            // bubble's own contrast pairing (that's the exact
                            // bug being fixed) — this stays legible on
                            // primaryContainer or surfaceVariant either way.
                            Icon(message.attachmentType.icon(), contentDescription = null, tint = contentColor)
                            Text(
                                message.attachmentFileName ?: attachmentFallback,
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
                if (message.text.isNotBlank()) {
                    Text(message.text, style = MaterialTheme.typography.bodyMedium, color = contentColor)
                }
                Text(
                    formatRecordTimestamp(message.createdAt) + if (message.isEdited) stringResource(R.string.chat_edited_suffix) else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedContentColor,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** The sender-only "⋮" menu on their own bubble — Edit / Delete for
 * Everyone / Delete for Me. [contentColor] matches the bubble's own text
 * color (same reasoning as the rest of [MessageBubble]) so the icon reads
 * correctly against either bubble background. */
@Composable
private fun MessageMenuButton(contentColor: Color, onEdit: () -> Unit, onDeleteForEveryone: () -> Unit, onDeleteForMe: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.chat_message_options_cd), tint = contentColor, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.chat_edit)) }, onClick = { expanded = false; onEdit() })
            DropdownMenuItem(text = { Text(stringResource(R.string.chat_delete_for_everyone_confirm)) }, onClick = { expanded = false; onDeleteForEveryone() })
            DropdownMenuItem(text = { Text(stringResource(R.string.chat_delete_for_me)) }, onClick = { expanded = false; onDeleteForMe() })
        }
    }
}

/** What replaces a bubble once its sender chose "Delete for Everyone" —
 * every participant, sender included, sees this same placeholder from then
 * on (spec: "to everyone"); the message doc itself, and its place in chat
 * history, is untouched (see [GroupChatRepository.deleteForEveryone]). */
@Composable
private fun DeletedMessagePlaceholder(isOwnMessage: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isOwnMessage) Arrangement.End else Arrangement.Start) {
        Text(
            stringResource(R.string.chat_message_deleted),
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

private fun GroupChatAttachmentType?.icon() = when (this) {
    GroupChatAttachmentType.IMAGE -> Icons.Rounded.AttachFile
    GroupChatAttachmentType.PDF, GroupChatAttachmentType.WORD, GroupChatAttachmentType.EXCEL, null -> Icons.Rounded.InsertDriveFile
}

/** "Supported attachment types" spec §8 — image/PDF/Word/Excel, offered
 * together in one picker (same [ActivityResultContracts.OpenDocument]
 * multi-mime shape [com.emfitsolutions.gopreach.ui.screens.announcements
 * .AnnouncementsScreen]'s ATTACHMENT_MIME_TYPES already uses for
 * documents, with image types added). */
private val GROUP_CHAT_ATTACHMENT_MIME_TYPES = arrayOf(
    "image/jpeg",
    "image/png",
    "image/webp",
    "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
)

private fun inferAttachmentType(context: android.content.Context, uri: Uri, fileName: String): GroupChatAttachmentType? {
    val mimeType = context.contentResolver.getType(uri)?.lowercase() ?: ""
    val lowerName = fileName.lowercase()
    return when {
        mimeType.startsWith("image/") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png") || lowerName.endsWith(".webp") -> GroupChatAttachmentType.IMAGE
        mimeType == "application/pdf" || lowerName.endsWith(".pdf") -> GroupChatAttachmentType.PDF
        mimeType.contains("word") || lowerName.endsWith(".doc") || lowerName.endsWith(".docx") -> GroupChatAttachmentType.WORD
        mimeType.contains("excel") || mimeType.contains("spreadsheet") || lowerName.endsWith(".xls") || lowerName.endsWith(".xlsx") -> GroupChatAttachmentType.EXCEL
        else -> null
    }
}

/** Same [OpenableColumns.DISPLAY_NAME] query [AnnouncementsScreen]'s own
 * `queryFileName` uses — duplicated rather than shared since it's a
 * three-line, file-private helper there too. */
private fun queryFileName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment

private fun querySizeColumn(context: android.content.Context, uri: Uri): Long = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else 0L
    }
}.getOrNull() ?: 0L

private fun queryFileSize(context: android.content.Context, uri: Uri): Long = querySizeColumn(context, uri)

fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
