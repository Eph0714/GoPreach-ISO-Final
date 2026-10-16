package com.emfitsolutions.gopreach.ui.screens.announcements

import android.app.DatePickerDialog
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import com.emfitsolutions.gopreach.ui.components.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.Announcement
import com.emfitsolutions.gopreach.data.model.AnnouncementAttachment
import com.emfitsolutions.gopreach.data.model.AnnouncementLifecycle
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.ui.components.co.CoCard
import com.emfitsolutions.gopreach.ui.components.co.CoKind
import com.emfitsolutions.gopreach.ui.components.co.CoStatusBadge
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.screens.circuit.CircuitPeopleViewModel
import com.emfitsolutions.gopreach.ui.screens.circuit.CircuitScopeStore
import com.emfitsolutions.gopreach.ui.screens.circuit.SelectCongregationPrompt
import com.emfitsolutions.gopreach.ui.screens.circuit.SelectedCongregationBar
import com.emfitsolutions.gopreach.ui.screens.circuit.validCongregation
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.SimpleDropdown
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import java.util.Calendar

private enum class Tab(val label: String) { PUBLISHED("Published"), DRAFT("Drafts"), ARCHIVED("Archived") }

private class EditorRequest(val template: Announcement?, val duplicate: Boolean)

private fun tabOf(a: Announcement) = when (a.status) {
    Announcement.STATUS_DRAFT -> Tab.DRAFT
    Announcement.STATUS_ARCHIVED -> Tab.ARCHIVED
    else -> Tab.PUBLISHED
}

/**
 * Circuit Overseer -> Announcements. The congregation is chosen first (from those assigned to this overseer); only then can announcements of that
 * congregation be listed or written. The list is managed by status (Published / Drafts / Archived), with search and filters; every announcement can
 * be opened, edited, duplicated, archived or (drafts and archived ones) deleted.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CoAnnouncementsScreen(
    circuitScope: String,
    actorPersonId: String,
    onBack: () -> Unit,
    viewModel: CoAnnouncementsViewModel = koinViewModel(),
    people: CircuitPeopleViewModel = koinViewModel(),
) {
    val scope = rememberCoroutineScope()
    val showToast = rememberActionToast()
    val congregations by remember(circuitScope) { people.congregations(circuitScope) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val selected = validCongregation(congregations, CircuitScopeStore.congregation)

    var tab by rememberSaveable { mutableStateOf(Tab.PUBLISHED.name) }
    var query by rememberSaveable { mutableStateOf("") }
    var scopeFilter by rememberSaveable { mutableStateOf("ALL") }
    var period by rememberSaveable { mutableStateOf("ANY") }
    var editor by remember { mutableStateOf<EditorRequest?>(null) }
    var viewing by remember { mutableStateOf<Announcement?>(null) }
    var confirmArchive by remember { mutableStateOf<Announcement?>(null) }
    var confirmDelete by remember { mutableStateOf<Announcement?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Announcements") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            if (selected != null) ExtendedFloatingActionButton(
                onClick = { editor = EditorRequest(null, false) },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("New Announcement") },
            )
        },
    ) { padding ->
        if (selected == null) {
            SelectCongregationPrompt(
                congregations = congregations, onSelect = { CircuitScopeStore.selectCongregation(it) }, modifier = Modifier.padding(padding),
                hint = "Choose the congregation this announcement is for. You can only write to a congregation assigned to you.",
            )
            return@Scaffold
        }
        val all by remember(selected.id) { viewModel.announcementsFor(selected.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
        val now = System.currentTimeMillis()
        val counts = Tab.entries.associateWith { t -> all.count { tabOf(it) == t } }
        val shown = all.filter { a ->
            tabOf(a).name == tab &&
                (query.isBlank() || a.title.contains(query.trim(), ignoreCase = true)) &&
                (scopeFilter == "ALL" || a.recipientScope == scopeFilter) &&
                (period == "ANY" || a.displayDate >= now - (if (period == "MONTH") 30L else 90L) * 86_400_000L)
        }

        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "bar") { SelectedCongregationBar(selected.name, onChange = { CircuitScopeStore.selectCongregation(null) }) }
            item(key = "search") {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search by title") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") } },
                )
            }
            item(key = "tabs") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tab.entries.forEach { t -> FilterChip(selected = tab == t.name, onClick = { tab = t.name }, label = { Text("${t.label} (${counts[t]})") }) }
                }
            }
            item(key = "filters") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = scopeFilter == "ALL", onClick = { scopeFilter = "ALL" }, label = { Text("All recipients") })
                    FilterChip(selected = scopeFilter == Announcement.SCOPE_CONGREGATION, onClick = { scopeFilter = Announcement.SCOPE_CONGREGATION }, label = { Text("Entire congregation") })
                    FilterChip(selected = scopeFilter == Announcement.SCOPE_SELECTED_ELDERS, onClick = { scopeFilter = Announcement.SCOPE_SELECTED_ELDERS }, label = { Text("Selected elders") })
                    FilterChip(selected = period == "ANY", onClick = { period = "ANY" }, label = { Text("Any date") })
                    FilterChip(selected = period == "MONTH", onClick = { period = "MONTH" }, label = { Text("Last 30 days") })
                    FilterChip(selected = period == "QUARTER", onClick = { period = "QUARTER" }, label = { Text("Last 90 days") })
                }
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.Campaign, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (all.isEmpty()) "No announcements for ${selected.name} yet" else "Nothing matches these filters",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (all.isEmpty()) "Tap “New Announcement” to write the first one." else "Try another tab, or clear the search.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(shown, key = { it.id }) { a ->
                AnnouncementManageCard(
                    a, selected.name, viewModel,
                    onOpen = { viewing = a },
                    onEdit = { editor = EditorRequest(a, false) },
                    onDuplicate = { editor = EditorRequest(a, true) },
                    onArchive = { confirmArchive = a },
                    onDelete = { confirmDelete = a },
                )
            }
        }

        editor?.let { req ->
            CoAnnouncementEditor(
                request = req, congregations = congregations, defaultCongregation = selected, actorPersonId = actorPersonId, viewModel = viewModel,
                onClose = { editor = null }, onDone = { message -> editor = null; showToast(message) },
            )
        }
        viewing?.let { a ->
            val current = all.firstOrNull { it.id == a.id } ?: a
            AnnouncementDetailsDialog(
                announcement = current, congregationName = selected.name, viewModel = viewModel, showReaders = true, onDismiss = { viewing = null },
                actions = {
                    if (current.status != Announcement.STATUS_ARCHIVED) IconButton(onClick = { viewing = null; editor = EditorRequest(current, false) }) { Icon(Icons.Rounded.Edit, contentDescription = "Edit") }
                    IconButton(onClick = { viewing = null; editor = EditorRequest(current, true) }) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Duplicate") }
                },
            )
        }
        confirmArchive?.let { a ->
            AlertDialog(
                onDismissRequest = { confirmArchive = null },
                title = { Text("Archive this announcement?") },
                text = { Text("\"${a.title}\" stops being shown to its recipients. It stays here under Archived, with its files.") },
                confirmButton = { TextButton(onClick = { confirmArchive = null; scope.launch { viewModel.archive(a, actorPersonId); showToast("Announcement archived.") } }) { Text("Archive") } },
                dismissButton = { TextButton(onClick = { confirmArchive = null }) { Text("Cancel") } },
            )
        }
        confirmDelete?.let { a ->
            AlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = { Text("Delete this announcement?") },
                text = { Text("\"${a.title}\" and its ${a.attachments.size} file(s) will be permanently deleted. This can't be undone.") },
                confirmButton = { TextButton(onClick = { confirmDelete = null; scope.launch { viewModel.delete(a, actorPersonId); showToast("Announcement deleted.") } }) { Text("Delete") } },
                dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnnouncementManageCard(
    a: Announcement,
    congregationName: String,
    viewModel: CoAnnouncementsViewModel,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val lifecycle = a.lifecycle(System.currentTimeMillis())
    val reads by remember(a.id) { viewModel.readsOf(a.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var menu by remember { mutableStateOf(false) }
    CoCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(a.title.ifBlank { "(No title)" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, modifier = Modifier.weight(1f))
                CoStatusBadge(lifecycle.label, lifecycleKind(lifecycle))
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) { Icon(Icons.Rounded.MoreVert, contentDescription = "Actions") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("View") }, leadingIcon = { Icon(Icons.Rounded.Visibility, null) }, onClick = { menu = false; onOpen() })
                        if (a.status != Announcement.STATUS_ARCHIVED) DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, onClick = { menu = false; onDuplicate() })
                        if (a.status == Announcement.STATUS_PUBLISHED) DropdownMenuItem(text = { Text("Archive") }, leadingIcon = { Icon(Icons.Rounded.Archive, null) }, onClick = { menu = false; onArchive() })
                        if (a.status != Announcement.STATUS_PUBLISHED) DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            Text(congregationName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                MetaItem(Icons.Rounded.CalendarToday, announcementDateLabel(a.displayDate))
                if (a.recipientScope == Announcement.SCOPE_SELECTED_ELDERS) MetaItem(Icons.Rounded.Person, "Selected elders (${a.recipientIds.size})")
                else MetaItem(Icons.Rounded.Group, "Entire congregation")
                if (a.attachments.isNotEmpty()) MetaItem(Icons.Rounded.AttachFile, "${a.attachments.size} file${if (a.attachments.size == 1) "" else "s"}")
                if (a.status == Announcement.STATUS_PUBLISHED) MetaItem(Icons.Rounded.Visibility, "Read by ${reads.size}")
            }
        }
    }
}

@Composable
private fun MetaItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ====================================================================================================================
// The editor

private enum class UploadState { UPLOADING, DONE, FAILED }

private class AttachmentUi(val key: String, val name: String, val uri: Uri?, state: UploadState, meta: AnnouncementAttachment?, error: String? = null) {
    var state by mutableStateOf(state)
    var meta by mutableStateOf(meta)
    var error by mutableStateOf(error)
}

/**
 * The announcement form, opened only once a congregation is known. New, Edit and Duplicate share it. A duplicate keeps the title and text but starts
 * with no recipients and no files, and must have its congregation and recipients confirmed again before it can be published.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CoAnnouncementEditor(
    request: EditorRequest,
    congregations: List<Congregation>,
    defaultCongregation: Congregation,
    actorPersonId: String,
    viewModel: CoAnnouncementsViewModel,
    onClose: () -> Unit,
    onDone: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val showToast = rememberActionToast()
    val template = request.template
    val existing = template?.takeIf { !request.duplicate }
    val announcementId = remember { existing?.id ?: viewModel.newId() }
    val now = remember { System.currentTimeMillis() }

    var congregationId by remember { mutableStateOf(if (request.duplicate) null else existing?.congregationId ?: defaultCongregation.id) }
    var title by remember { mutableStateOf(template?.title.orEmpty()) }
    var details by remember { mutableStateOf(TextFieldValue(template?.details.orEmpty())) }
    var date by remember { mutableLongStateOf(existing?.announcementDate ?: now) }
    var expiry by remember { mutableStateOf(existing?.expiryDate) }
    var recipientScope by remember { mutableStateOf(template?.recipientScope ?: Announcement.SCOPE_CONGREGATION) }
    var scopeConfirmed by remember { mutableStateOf(!request.duplicate) }
    var recipients by remember { mutableStateOf(if (request.duplicate) emptySet() else template?.recipientIds.orEmpty().toSet()) }
    var elderQuery by remember { mutableStateOf("") }
    val files = remember { mutableStateListOf<AttachmentUi>().also { l -> existing?.attachments?.forEach { l += AttachmentUi(it.id, it.name, null, UploadState.DONE, it) } } }
    val savedFileIds = remember { existing?.attachments?.map { it.id }.orEmpty().toSet() }
    val removedSaved = remember { mutableStateListOf<String>() }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var previewing by remember { mutableStateOf(false) }
    var confirmPublish by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val congregation = congregations.firstOrNull { it.id == congregationId }
    val elders by remember(congregationId) { viewModel.eldersOf(congregationId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val isPublished = existing?.status == Announcement.STATUS_PUBLISHED
    val isArchived = existing?.status == Announcement.STATUS_ARCHIVED
    val dirty = title.isNotBlank() || details.text.isNotBlank() || files.isNotEmpty()

    fun pickDate(initial: Long, onPicked: (Long) -> Unit) {
        val c = Calendar.getInstance().apply { timeInMillis = initial }
        DatePickerDialog(context, { _, y, m, d -> onPicked(Calendar.getInstance().apply { clear(); set(y, m, d, 12, 0, 0) }.timeInMillis) }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    fun upload(item: AttachmentUi) {
        val uri = item.uri ?: return
        item.state = UploadState.UPLOADING; item.error = null
        scope.launch {
            try {
                item.meta = viewModel.attach(announcementId, uri)
                item.state = UploadState.DONE
            } catch (e: Exception) {
                item.state = UploadState.FAILED
                item.error = e.message ?: "The upload failed."
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            val item = AttachmentUi(uri.toString(), uri.lastPathSegment?.substringAfterLast('/') ?: "file", uri, UploadState.UPLOADING, null)
            files += item
            upload(item)
        }
    }

    fun build(status: String): Announcement = Announcement(
        id = announcementId, congregationId = congregationId.orEmpty(), title = title.trim(), details = details.text.trim(),
        status = status, recipientScope = recipientScope,
        recipientIds = if (recipientScope == Announcement.SCOPE_SELECTED_ELDERS) recipients.toList() else emptyList(),
        announcementDate = date, expiryDate = expiry,
        attachments = files.mapNotNull { it.meta }.filter { it.id.isNotBlank() },
        publishedAt = existing?.publishedAt,
    )

    /** Why this cannot be saved / published yet, in words for the person. */
    fun problem(publishing: Boolean): String? = when {
        congregationId == null -> "Choose the congregation first."
        title.isBlank() -> "Enter a title."
        files.any { it.state == UploadState.UPLOADING } -> "Wait for the files to finish uploading."
        publishing && details.text.isBlank() -> "Enter the announcement details."
        publishing && !scopeConfirmed -> "Choose who receives this announcement."
        publishing && recipientScope == Announcement.SCOPE_SELECTED_ELDERS && recipients.isEmpty() -> "Choose at least one elder."
        publishing && recipientScope == Announcement.SCOPE_SELECTED_ELDERS && !recipients.all { id -> elders.any { it.personId == id } } -> "A selected elder is no longer listed. Review the recipients."
        publishing && files.any { it.state == UploadState.FAILED } -> "Retry or remove the file that failed to upload."
        publishing && expiry != null && expiry!! < date -> "The expiry date can't be before the announcement date."
        else -> null
    }

    fun commit(status: String, message: String) {
        error = problem(status == Announcement.STATUS_PUBLISHED)
        if (error != null || saving) return
        saving = true
        scope.launch {
            try {
                viewModel.save(build(status), actorPersonId, previous = existing)
                removedSaved.forEach { viewModel.removeAttachment(announcementId, it) }
                onDone(message)
            } catch (e: Exception) {
                saving = false
                error = e.message ?: "It couldn't be saved. Check your connection and try again."
            }
        }
    }

    fun discard() {
        // Files added in this session that were never saved to the announcement are removed from the server again.
        scope.launch { files.filter { it.meta != null && it.meta!!.id !in savedFileIds }.forEach { viewModel.removeAttachment(announcementId, it.meta!!.id) } }
        onClose()
    }

    FullScreenWindow(onDismiss = { if (dirty) confirmDiscard = true else onClose() }) {
        Scaffold(
            modifier = Modifier.imePadding(),
            topBar = {
                TopAppBar(
                    title = { Text(if (request.duplicate) "Duplicate Announcement" else if (existing != null) "Edit Announcement" else "New Announcement") },
                    navigationIcon = { IconButton(onClick = { if (dirty) confirmDiscard = true else onClose() }) { Icon(Icons.Rounded.Close, contentDescription = "Close") } },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // ---- congregation, prominently ----
                CoCard(Modifier.fillMaxWidth(), accent = MaterialTheme.colorScheme.primary) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("CONGREGATION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (request.duplicate) {
                            SimpleDropdown(
                                label = "Confirm the congregation", selectedLabel = congregation?.name ?: "Choose a congregation",
                                options = congregations.map { it.id to it.name },
                                onSelected = { if (it != congregationId) { congregationId = it; recipients = emptySet(); elderQuery = "" } },
                            )
                            Text("Recipients and files are not copied. Choose them again before publishing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(congregation?.name ?: defaultCongregation.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                OutlinedTextField(value = title, onValueChange = { title = it.take(200) }, label = { Text("Announcement title *") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                // ---- details with basic formatting ----
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Details *", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { details = wrapSelection(details, "**") }) { Icon(Icons.Rounded.FormatBold, contentDescription = "Bold") }
                        IconButton(onClick = { details = wrapSelection(details, "_") }) { Icon(Icons.Rounded.FormatItalic, contentDescription = "Italic") }
                        IconButton(onClick = { details = bulletLine(details) }) { Icon(Icons.Rounded.FormatListBulleted, contentDescription = "Bullet") }
                    }
                    OutlinedTextField(
                        value = details, onValueChange = { details = it.copy(text = it.text.take(20000)) }, minLines = 5, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Write the announcement. Use the buttons for **bold**, _italic_ and bullet lines.") },
                    )
                }

                // ---- dates ----
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    DateField("Announcement date *", announcementDateLabel(date), Modifier.weight(1f)) { pickDate(date) { date = it } }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        DateField("Display until (optional)", expiry?.let { announcementDateLabel(it) } ?: "No expiry", Modifier.fillMaxWidth()) { pickDate(expiry ?: date) { expiry = it } }
                        if (expiry != null) TextButton(onClick = { expiry = null }) { Text("Clear expiry") }
                    }
                }

                // ---- recipients ----
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Recipients *", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = scopeConfirmed && recipientScope == Announcement.SCOPE_CONGREGATION, onClick = { recipientScope = Announcement.SCOPE_CONGREGATION; scopeConfirmed = true }, label = { Text("Entire Congregation") }, leadingIcon = { Icon(Icons.Rounded.Group, null, Modifier.size(18.dp)) })
                        FilterChip(selected = scopeConfirmed && recipientScope == Announcement.SCOPE_SELECTED_ELDERS, onClick = { recipientScope = Announcement.SCOPE_SELECTED_ELDERS; scopeConfirmed = true }, label = { Text("Specific Elder(s)") }, leadingIcon = { Icon(Icons.Rounded.Person, null, Modifier.size(18.dp)) })
                    }
                    if (scopeConfirmed && recipientScope == Announcement.SCOPE_CONGREGATION) {
                        Text("Every authorized user of ${congregation?.name ?: "the congregation"} can read it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AnimatedVisibility(visible = scopeConfirmed && recipientScope == Announcement.SCOPE_SELECTED_ELDERS, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        ElderPicker(
                            congregationChosen = congregationId != null, elders = elders, selected = recipients, query = elderQuery, onQuery = { elderQuery = it },
                            onToggle = { id -> recipients = if (id in recipients) recipients - id else recipients + id },
                        )
                    }
                }

                // ---- attachments ----
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Attachments", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        OutlinedButton(enabled = congregationId != null, onClick = {
                            picker.launch(arrayOf("application/pdf", "application/msword", "application/vnd.*", "text/*", "application/rtf", "image/*"))
                        }) { Icon(Icons.Rounded.AttachFile, null, Modifier.size(18.dp)); Text("  Add files") }
                    }
                    Text("PDF, Word, Excel, PowerPoint, text, CSV and images, up to 10 MB each.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    files.forEach { item ->
                        CoCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                val meta = item.meta
                                Icon(meta?.let { attachmentIcon(it) } ?: Icons.Rounded.AttachFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)) {
                                    Text(meta?.name ?: item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(
                                        when (item.state) {
                                            UploadState.UPLOADING -> "Uploading…"
                                            UploadState.DONE -> (meta?.let { attachmentTypeLabel(it) + " · " + it.sizeLabel } ?: "Uploaded")
                                            UploadState.FAILED -> item.error ?: "Upload failed"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (item.state == UploadState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                when (item.state) {
                                    UploadState.UPLOADING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    UploadState.DONE -> Icon(Icons.Rounded.CheckCircle, contentDescription = "Uploaded", tint = MaterialTheme.colorScheme.primary)
                                    UploadState.FAILED -> IconButton(onClick = { upload(item) }) { Icon(Icons.Rounded.Refresh, contentDescription = "Retry upload") }
                                }
                                IconButton(onClick = {
                                    val id = item.meta?.id
                                    files.remove(item)
                                    if (id != null) { if (id in savedFileIds) removedSaved += id else scope.launch { viewModel.removeAttachment(announcementId, id) } }
                                }) { Icon(Icons.Rounded.Close, contentDescription = "Remove file") }
                            }
                        }
                    }
                }

                error?.let {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { error = problem(false); if (error == null) previewing = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Visibility, null, Modifier.size(18.dp)); Text("  Preview") }
                    if (!isPublished) OutlinedButton(
                        enabled = !saving, modifier = Modifier.weight(1f),
                        onClick = { commit(if (isArchived) Announcement.STATUS_ARCHIVED else Announcement.STATUS_DRAFT, if (isArchived) "Changes saved." else "Draft saved.") },
                    ) { Text(if (isArchived) "Save Changes" else "Save Draft") }
                }
                Button(
                    enabled = !saving, modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (isPublished) commit(Announcement.STATUS_PUBLISHED, "Changes saved.")
                        else { error = problem(true); if (error == null) confirmPublish = true }
                    },
                ) {
                    if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Icon(Icons.AutoMirrored.Rounded.Send, null, Modifier.size(18.dp))
                    Text(if (isPublished) "  Save Changes" else "  Publish")
                }
            }
        }
    }

    if (previewing) {
        FullScreenWindow(onDismiss = { previewing = false }) {
            Scaffold(
                topBar = { TopAppBar(title = { Text("Preview") }, navigationIcon = { IconButton(onClick = { previewing = false }) { Icon(Icons.Rounded.Close, contentDescription = "Close preview") } }) },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This is how recipients will see it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val names = elders.associate { it.personId to it.name }
                    AnnouncementBody(
                        build(Announcement.STATUS_PUBLISHED).copy(createdByName = ""), congregation?.name.orEmpty(),
                        recipientLine = if (recipientScope == Announcement.SCOPE_SELECTED_ELDERS) "To: " + recipients.joinToString(", ") { names[it] ?: "Elder" } else null,
                    ) { att ->
                        CoCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(attachmentIcon(att), null, tint = MaterialTheme.colorScheme.primary)
                                Column { Text(att.name, fontWeight = FontWeight.SemiBold); Text(attachmentTypeLabel(att) + " · " + att.sizeLabel, style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmPublish) {
        AlertDialog(
            onDismissRequest = { confirmPublish = false },
            title = { Text("Publish this announcement?") },
            text = {
                Text(
                    "\"${title.trim()}\" will be sent to " +
                        (if (recipientScope == Announcement.SCOPE_SELECTED_ELDERS) "${recipients.size} selected elder${if (recipients.size == 1) "" else "s"} of ${congregation?.name.orEmpty()}" else "everyone in ${congregation?.name.orEmpty()}") +
                        ". They will see it in their Announcements.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmPublish = false; commit(Announcement.STATUS_PUBLISHED, "Announcement published.") }) { Text("Publish") } },
            dismissButton = { TextButton(onClick = { confirmPublish = false }) { Text("Back") } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard your changes?") },
            text = { Text("What you typed and any newly attached files will be lost. Use Save Draft to keep them.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; discard() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun DateField(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.CalendarToday, null, Modifier.size(16.dp)); Text("  $value", maxLines = 1)
        }
    }
}

/** Searchable multi-select of the chosen congregation's elders, with the selection shown as removable chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ElderPicker(
    congregationChosen: Boolean,
    elders: List<ElderOption>,
    selected: Set<String>,
    query: String,
    onQuery: (String) -> Unit,
    onToggle: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!congregationChosen) {
            Text("Choose the congregation first to see its elders.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        if (selected.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                elders.filter { it.personId in selected }.forEach { e ->
                    InputChip(selected = true, onClick = { onToggle(e.personId) }, label = { Text(e.name) }, trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = "Remove ${e.name}", modifier = Modifier.size(16.dp)) })
                }
            }
        }
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search elders") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") } },
        )
        val matching = elders.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) || it.roleLabel.contains(query.trim(), ignoreCase = true) }
        if (elders.isEmpty()) Text("No elder of this congregation has signed in yet, so none can be addressed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
            matching.forEach { e ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = e.personId in selected, onCheckedChange = { onToggle(e.personId) })
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(e.roleLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (matching.isEmpty()) Text("No elder matches “$query”.", style = MaterialTheme.typography.bodySmall)
        }
        Text("${selected.size} selected", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Wraps the selected text (or an empty pair at the cursor) in a formatting marker. */
private fun wrapSelection(value: TextFieldValue, marker: String): TextFieldValue {
    val s = value.selection.min
    val e = value.selection.max
    val text = value.text
    val replaced = text.substring(0, s) + marker + text.substring(s, e) + marker + text.substring(e)
    return value.copy(text = replaced, selection = if (s == e) TextRange(s + marker.length) else TextRange(e + 2 * marker.length))
}

/** Starts the current line with a bullet marker ("- "), which recipients see as a bullet. */
private fun bulletLine(value: TextFieldValue): TextFieldValue {
    val at = value.selection.min
    val lineStart = value.text.lastIndexOf('\n', (at - 1).coerceAtLeast(0)).let { if (it < 0 || at == 0) 0 else it + 1 }
    if (value.text.startsWith("- ", lineStart)) return value
    return value.copy(text = value.text.substring(0, lineStart) + "- " + value.text.substring(lineStart), selection = TextRange(at + 2))
}
