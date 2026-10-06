package com.emfitsolutions.gopreach.ui.screens.announcements

import android.net.Uri
import com.emfitsolutions.gopreach.ui.components.RecordFound
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.emfitsolutions.gopreach.data.export.CsvExporter
import com.emfitsolutions.gopreach.data.model.Announcement
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.ui.components.CongregationFilterDropdown
import com.emfitsolutions.gopreach.ui.components.SelectCongregationPrompt
import com.emfitsolutions.gopreach.ui.components.rememberCongregationContext
import com.emfitsolutions.gopreach.ui.components.FormDialog
import com.emfitsolutions.gopreach.ui.components.formatRecordTimestamp
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.components.requiredFieldsMessage
import androidx.compose.ui.window.DialogProperties

/**
 * "Announcement Module" — one screen for both sides:
 * [readOnly] false is the Super-Admin/Admin/Coordinator Elder management
 * view (Add/Edit/Delete, FAB to create); true is a Publisher's own
 * notification list (view-only, opening it marks every announcement in
 * scope "seen" — see [ManageAnnouncementsViewModel.markSeen]).
 * [fixedCongregationId] is the security boundary, resolved by the caller
 * (null means Super-Admin — every congregation).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnouncementsScreen(
    currentPersonId: String,
    fixedCongregationId: String?,
    readOnly: Boolean = false,
    onBack: () -> Unit,
    viewModel: ManageAnnouncementsViewModel = koinViewModel(),
) {
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()
    // "Add a filter for Congregation" (Super-Admin only).
    var congregationFilter by rememberCongregationContext("announcements")
    val effectiveCongregationId = fixedCongregationId ?: congregationFilter
    val needsCongregation = fixedCongregationId == null && congregationFilter == null
    val rowsFlow = remember(effectiveCongregationId, needsCongregation) { if (needsCongregation) kotlinx.coroutines.flow.flowOf(emptyList()) else viewModel.rowsFor(effectiveCongregationId) }
    val rows by rowsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingEdit by remember { mutableStateOf<Announcement?>(null) }
    var pendingDelete by remember { mutableStateOf<Announcement?>(null) }
    var viewingDetail by remember { mutableStateOf<Announcement?>(null) }
    val showToast = rememberActionToast()

    // A Publisher opening their own notification list — mark everything
    // currently in scope as seen, clearing the Main Form's badge.
    LaunchedEffect(readOnly, currentPersonId) {
        if (readOnly) viewModel.markSeen(currentPersonId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Announcements") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            if (!readOnly) {
                FloatingActionButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = "New Announcement")
                }
            }
        },
    ) { padding ->
      Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        if (fixedCongregationId == null) {
            CongregationFilterDropdown(
                congregations = congregations,
                selectedCongregationId = congregationFilter,
                onSelected = { congregationFilter = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (needsCongregation) {
            SelectCongregationPrompt()
        } else if (rows.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RecordFound(0)
                Text(
                    if (readOnly) "No announcements yet." else "No announcements yet. Tap + to add one.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { RecordFound(rows.size) }
                items(rows, key = { it.id }) { announcement ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(announcement.title, style = MaterialTheme.typography.titleMedium)
                                if (!readOnly) {
                                    Row {
                                        IconButton(onClick = { pendingEdit = announcement }) {
                                            Icon(Icons.Rounded.Edit, contentDescription = "Edit announcement")
                                        }
                                        IconButton(onClick = { pendingDelete = announcement }) {
                                            Icon(Icons.Rounded.Delete, contentDescription = "Delete announcement")
                                        }
                                    }
                                }
                            }
                            if (announcement.imageUrl != null) {
                                AsyncImage(
                                    model = announcement.imageUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().height(140.dp),
                                )
                            }
                            Text(
                                announcement.details,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                            )
                            if (announcement.attachmentUrl != null) {
                                AttachmentRow(url = announcement.attachmentUrl!!, fileName = announcement.attachmentFileName)
                            }
                            Text(
                                formatRecordTimestamp(announcement.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = { viewingDetail = announcement },
                                modifier = Modifier.align(Alignment.End),
                            ) { Text("Open") }
                        }
                    }
                }
            }
        }
      }
    }

    if (showCreateDialog) {
        AnnouncementDialog(
            existing = null,
            fixedCongregationId = fixedCongregationId,
            congregations = congregations,
            currentPersonId = currentPersonId,
            viewModel = viewModel,
            onDismiss = { showCreateDialog = false },
        )
    }
    val toEdit = pendingEdit
    if (toEdit != null) {
        AnnouncementDialog(
            existing = toEdit,
            fixedCongregationId = fixedCongregationId,
            congregations = congregations,
            currentPersonId = currentPersonId,
            viewModel = viewModel,
            onDismiss = { pendingEdit = null },
        )
    }
    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete Announcement?") },
            text = { Text("\"${toDelete.title}\" will be permanently deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(toDelete, currentPersonId); showToast("\"${toDelete.title}\" deleted."); pendingDelete = null }) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
    val toView = viewingDetail
    if (toView != null) {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { viewingDetail = null },
            title = { Text(toView.title) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).imePadding(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (toView.imageUrl != null) {
                        AsyncImage(
                            model = toView.imageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(toView.details, style = MaterialTheme.typography.bodyMedium)
                    if (toView.attachmentUrl != null) {
                        AttachmentRow(url = toView.attachmentUrl!!, fileName = toView.attachmentFileName)
                    }
                    Text(
                        formatRecordTimestamp(toView.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { viewingDetail = null }) { Text("Close") } },
        )
    }
}

/** Create/edit dialog — [existing] null means creating new. Image upload
 * always happens as part of the same Save (see
 * [ManageAnnouncementsViewModel.saveWithImage]'s doc comment for why a
 * brand-new announcement can't upload an image before its first save). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnnouncementDialog(
    existing: Announcement?,
    fixedCongregationId: String?,
    congregations: List<Congregation>,
    currentPersonId: String,
    viewModel: ManageAnnouncementsViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var details by remember { mutableStateOf(existing?.details ?: "") }
    // Bug fix — same "Congregation/Group is required" even after picking one,
    // confirmed root-caused (and fixed) in ManageGroupsScreen's GroupDialog:
    // `rememberSaveable` (not plain `remember`, immune to a config change
    // wiping it) instead of a whole-object `remember`, AND submit() below
    // re-derives the resolved id fresh at call time rather than trusting
    // this file's own `congregationId` val — a real, confirmed-on-device
    // Compose staleness where that kind of pre-computed val can go stale
    // inside a local closure even though the live picked-id state stays correct.
    var pickedCongregationId by rememberSaveable { mutableStateOf(existing?.congregationId ?: fixedCongregationId ?: com.emfitsolutions.gopreach.ui.components.CongregationContextStore.get("announcements")) }
    var pickedImageUri by remember { mutableStateOf<Uri?>(null) }
    var removeImage by remember { mutableStateOf(false) }
    // "Allow to add files like pdf, word and excel" — a document attachment,
    // independent of the image above.
    var pickedAttachmentUri by remember { mutableStateOf<Uri?>(null) }
    var pickedAttachmentFileName by remember { mutableStateOf<String?>(null) }
    var removeAttachment by remember { mutableStateOf(false) }
    val showToast = rememberActionToast()

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pickedImageUri = uri
            removeImage = false
        }
    }
    // OpenDocument, not GetContent — GetContent only takes one mime type;
    // OpenDocument's contract accepts an array so PDF/Word/Excel can all be
    // offered in the same picker.
    val pickAttachment = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedAttachmentUri = uri
            pickedAttachmentFileName = queryFileName(context, uri)
            removeAttachment = false
        }
    }

    val hasImageToShow = pickedImageUri != null || (existing?.imageUrl != null && !removeImage)
    val attachmentNameToShow = pickedAttachmentFileName
        ?: (existing?.attachmentFileName.takeIf { existing?.attachmentUrl != null && !removeAttachment })
    val congregationId = fixedCongregationId ?: pickedCongregationId
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val resolvedCongregationId = fixedCongregationId ?: pickedCongregationId
        val message = requiredFieldsMessage(
            "Announcement Title" to title.isNotBlank(),
            "Announcement Details" to details.isNotBlank(),
            "Congregation" to (resolvedCongregationId != null),
        )
        if (message != null) {
            errorMessage = message
            return
        }
        viewModel.saveWithImage(
            announcement = Announcement(
                id = existing?.id ?: "",
                congregationId = resolvedCongregationId!!,
                title = title.trim(),
                details = details.trim(),
                imageUrl = existing?.imageUrl,
                attachmentUrl = existing?.attachmentUrl,
                attachmentFileName = existing?.attachmentFileName,
                createdByPersonId = existing?.createdByPersonId ?: currentPersonId,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            ),
            pickedImageUri = pickedImageUri,
            removeImage = removeImage,
            pickedAttachmentUri = pickedAttachmentUri,
            pickedAttachmentFileName = pickedAttachmentFileName,
            removeAttachment = removeAttachment,
            actorPersonId = currentPersonId,
            onImageUploadFailed = { showToast("Saved, but the image failed to upload. Try attaching it again.") },
            onAttachmentUploadFailed = { showToast("Saved, but the file failed to upload. Try attaching it again.") },
        )
        showToast(if (existing == null) "Announcement posted." else "Announcement saved.")
        onDismiss()
    }

    FormDialog(
        onDismissRequest = onDismiss,
        title = if (existing == null) "New Announcement" else "Edit Announcement",
        onConfirm = ::submit,
        hasUnsavedChanges = title != (existing?.title ?: "") || details != (existing?.details ?: "") ||
            pickedCongregationId != (existing?.congregationId ?: fixedCongregationId) ||
            pickedImageUri != null || removeImage || pickedAttachmentUri != null || removeAttachment,
        confirmLabel = if (existing == null) "Create" else "Save",
        errorMessage = errorMessage,
        maxContentHeight = 520.dp,
    ) {
        if (fixedCongregationId == null) {
                    CongregationPickerDropdown(
                        congregations = congregations,
                        selectedId = pickedCongregationId,
                        onSelected = { pickedCongregationId = it },
                    )
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Announcement Title") },
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it },
                    label = { Text("Announcement Details") },
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                )

                Text("Image (optional)", style = MaterialTheme.typography.labelLarge)
                if (hasImageToShow) {
                    AsyncImage(
                        model = pickedImageUri ?: existing?.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickImage.launch("image/*") }, modifier = Modifier.weight(1f)) {
                        Text(if (hasImageToShow) "Replace Image" else "Add Image")
                    }
                    if (hasImageToShow) {
                        OutlinedButton(
                            onClick = { pickedImageUri = null; removeImage = true },
                            modifier = Modifier.weight(1f),
                        ) { Text("Remove Image") }
                    }
                }

                Text("Attachment (optional) — PDF, Word, or Excel", style = MaterialTheme.typography.labelLarge)
                if (attachmentNameToShow != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AttachFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(attachmentNameToShow, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { pickAttachment.launch(ATTACHMENT_MIME_TYPES) },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (attachmentNameToShow != null) "Replace File" else "Attach File") }
                    if (attachmentNameToShow != null) {
                        OutlinedButton(
                            onClick = { pickedAttachmentUri = null; pickedAttachmentFileName = null; removeAttachment = true },
                            modifier = Modifier.weight(1f),
                        ) { Text("Remove File") }
                    }
                }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CongregationPickerDropdown(congregations: List<Congregation>, selectedId: String?, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = congregations.firstOrNull { it.id == selectedId }?.name ?: ""
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Congregation") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            congregations.forEach { congregation ->
                DropdownMenuItem(
                    text = { Text(congregation.name) },
                    onClick = { onSelected(congregation.id); expanded = false },
                )
            }
        }
    }
}

/** "Allow to add files like pdf, word and excel" — offered together in one
 * document picker (the modern and the legacy .doc/.xls MIME types, since
 * both still turn up in the wild). */
private val ATTACHMENT_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
)

/** A `content://` picker Uri carries no filename of its own — this is the
 * standard `OpenableColumns.DISPLAY_NAME` query every Storage Access
 * Framework picker result supports, used so the Publisher sees the actual
 * file name ("Congregation Schedule.pdf") instead of an opaque Storage
 * download URL. Falls back to the raw Uri's last path segment if the query
 * fails or the provider doesn't return one — still better than nothing. */
private fun queryFileName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment

/** The attached PDF/Word/Excel file, shown as a tappable row that opens it
 * (via whatever app the device has for that file type, same
 * [CsvExporter.openWithChooser] pattern the Reports export already uses)
 * rather than an inline preview the way [AsyncImage] handles the image. */
@Composable
private fun AttachmentRow(url: String, fileName: String?) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable { CsvExporter.openWithChooser(context, Uri.parse(url), "*/*") },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.AttachFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            fileName ?: "Attachment",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
