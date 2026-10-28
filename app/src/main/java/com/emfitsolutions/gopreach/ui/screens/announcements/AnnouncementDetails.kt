package com.emfitsolutions.gopreach.ui.screens.announcements

import com.emfitsolutions.gopreach.ui.components.ActionKind
import com.emfitsolutions.gopreach.ui.components.ActionIconButton
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.Announcement
import com.emfitsolutions.gopreach.data.model.AnnouncementAttachment
import com.emfitsolutions.gopreach.data.model.AnnouncementLifecycle
import com.emfitsolutions.gopreach.ui.components.co.CoCard
import com.emfitsolutions.gopreach.ui.components.co.CoKind
import com.emfitsolutions.gopreach.ui.components.co.CoStatusBadge
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFormat get() = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
internal fun announcementDateLabel(millis: Long): String = dateFormat.format(Date(millis))

internal fun lifecycleKind(l: AnnouncementLifecycle): CoKind = when (l) {
    AnnouncementLifecycle.DRAFT -> CoKind.Primary
    AnnouncementLifecycle.ACTIVE -> CoKind.Success
    AnnouncementLifecycle.EXPIRED -> CoKind.Warning
    AnnouncementLifecycle.ARCHIVED -> CoKind.Neutral
}

/**
 * An announcement as its recipients read it (also used for the sender's preview): title, who and when, the formatted text, and the attachment
 * list. [attachmentRow] decides what each file offers (nothing in a preview, Preview / Download once it is real).
 */
@Composable
fun AnnouncementBody(
    announcement: Announcement,
    congregationName: String,
    modifier: Modifier = Modifier,
    recipientLine: String? = null,
    attachmentRow: @Composable (AnnouncementAttachment) -> Unit,
) {
    val lifecycle = announcement.lifecycle(System.currentTimeMillis())
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CoStatusBadge(lifecycle.label, lifecycleKind(lifecycle))
            CoStatusBadge(if (announcement.recipientScope == Announcement.SCOPE_SELECTED_ELDERS) "Selected elders" else "Entire congregation", CoKind.Secondary)
        }
        Text(announcement.title.ifBlank { "(No title)" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(congregationName, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("From: Circuit Overseer" + announcement.createdByName.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(), style = MaterialTheme.typography.bodySmall)
            Text("Issued: " + announcementDateLabel(announcement.displayDate), style = MaterialTheme.typography.bodySmall)
            announcement.expiryDate?.let { Text("Active until: " + announcementDateLabel(it), style = MaterialTheme.typography.bodySmall) }
            recipientLine?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        HorizontalDivider()
        if (announcement.details.isBlank()) Text("(No details yet)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else AnnouncementText(announcement.details)
        if (announcement.attachments.isNotEmpty()) {
            HorizontalDivider()
            Text("Attachments (${announcement.attachments.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            announcement.attachments.forEach { attachmentRow(it) }
        }
    }
}

/** A full-screen window over the current screen (used instead of a new route). */
@Composable
fun FullScreenWindow(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize()) { content() }
    }
}

/**
 * The details of one announcement for a recipient or the sender: the body, and for each file a Preview (images in the window, PDFs and the rest in
 * the phone's own viewer) and a Download. Nothing is fetched until the person taps, and the server decides whether they may have it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnouncementDetailsDialog(
    announcement: Announcement,
    congregationName: String,
    viewModel: CoAnnouncementsViewModel,
    showReaders: Boolean,
    onDismiss: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    val names by viewModel.peopleNames.collectAsStateWithLifecycle()
    val reads by remember(announcement.id) { viewModel.readsOf(announcement.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val showToast = rememberActionToast()
    FullScreenWindow(onDismiss) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Announcement") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close") } },
                    actions = { actions() },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val recipients = if (announcement.recipientScope == Announcement.SCOPE_SELECTED_ELDERS && showReaders)
                    "To: " + announcement.recipientIds.joinToString(", ") { names[it] ?: "Unknown" } else null
                AnnouncementBody(announcement, congregationName, recipientLine = recipients) { att ->
                    AttachmentActionRow(announcement.id, att, viewModel, showToast)
                }
                if (showReaders && announcement.status == Announcement.STATUS_PUBLISHED) {
                    HorizontalDivider()
                    Text("Read by (${reads.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (reads.isEmpty()) Text("Nobody has opened it yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    reads.sortedByDescending { it.readAt }.forEach { r ->
                        Text((names[r.personId] ?: "Someone") + " · " + SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(r.readAt)), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** One attachment: icon, name, "type · size", and the Preview / Download actions. */
@Composable
fun AttachmentActionRow(announcementId: String, attachment: AnnouncementAttachment, viewModel: CoAnnouncementsViewModel, showToast: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var imageBytes by remember { mutableStateOf<ByteArray?>(null) }

    fun run(action: suspend (ByteArray) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                action(viewModel.download(announcementId, attachment))
            } catch (e: Exception) {
                showToast(e.message ?: "The file couldn't be downloaded. Check your connection and try again.")
            } finally {
                busy = false
            }
        }
    }

    CoCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(attachmentIcon(attachment), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(attachment.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text(attachmentTypeLabel(attachment) + " · " + attachment.sizeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else {
                if (attachment.isImage || attachment.isPdf) ActionIconButton(ActionKind.View, onClick = {
                    run { bytes -> if (attachment.isImage) imageBytes = bytes else viewModel.openExternally(context, attachment, bytes) }
                }, contentDescription = "Preview")
                ActionIconButton(ActionKind.Export, onClick = { run { bytes -> showToast(viewModel.saveToDownloads(attachment, bytes)) } }, contentDescription = "Download")
            }
        }
    }
    imageBytes?.let { bytes ->
        val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        FullScreenWindow(onDismiss = { imageBytes = null }) {
            Box(Modifier.fillMaxSize()) {
                if (bitmap != null) Image(bitmap, contentDescription = attachment.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                else Text("This image can't be shown here. Use Download.", Modifier.align(Alignment.Center).padding(24.dp))
                IconButton(onClick = { imageBytes = null }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
            }
        }
    }
}

internal fun attachmentIcon(a: AnnouncementAttachment) = when {
    a.isImage -> Icons.Rounded.Image
    a.isPdf -> Icons.Rounded.PictureAsPdf
    else -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

internal fun attachmentTypeLabel(a: AnnouncementAttachment): String = a.name.substringAfterLast('.', "").uppercase().ifBlank { "FILE" }

/** An announcement in a recipient's list: unread dot, title, who sent it and when, whether it is addressed to them personally, and its files. */
@Composable
fun RecipientAnnouncementCard(announcement: Announcement, unread: Boolean, congregationName: String, onClick: () -> Unit) {
    val lifecycle = announcement.lifecycle(System.currentTimeMillis())
    CoCard(Modifier.fillMaxWidth(), accent = if (unread) MaterialTheme.colorScheme.primary else null, onClick = onClick) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (unread) Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape))
                Text(announcement.title, style = MaterialTheme.typography.titleSmall, fontWeight = if (unread) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 2, modifier = Modifier.weight(1f))
                if (unread) CoStatusBadge("NEW", CoKind.Primary)
                if (lifecycle == AnnouncementLifecycle.EXPIRED) CoStatusBadge("Expired", CoKind.Warning)
            }
            Text(
                "From the Circuit Overseer · " + announcementDateLabel(announcement.displayDate) +
                    (if (announcement.recipientScope == Announcement.SCOPE_SELECTED_ELDERS) " · Sent to you" else "") +
                    (if (congregationName.isNotBlank()) " · $congregationName" else ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (announcement.attachments.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AttachFile, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${announcement.attachments.size} attachment${if (announcement.attachments.size == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
