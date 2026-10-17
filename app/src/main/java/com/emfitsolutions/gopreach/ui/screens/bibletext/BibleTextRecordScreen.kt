package com.emfitsolutions.gopreach.ui.screens.bibletext

import com.emfitsolutions.gopreach.domain.BibleTextNarration
import com.emfitsolutions.gopreach.ui.components.ListenPanel
import androidx.compose.material3.OutlinedButton
import com.emfitsolutions.gopreach.ui.components.RecordViewToggle
import com.emfitsolutions.gopreach.ui.components.UniversalColumn
import com.emfitsolutions.gopreach.ui.components.recordItems
import com.emfitsolutions.gopreach.ui.components.rememberRecordViewMode
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import com.emfitsolutions.gopreach.platform.rememberPlatformActions
import android.net.Uri
import com.emfitsolutions.gopreach.ui.components.RecordFound
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import com.emfitsolutions.gopreach.ui.components.Scaffold
import com.emfitsolutions.gopreach.ui.components.HideInFullScreen
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.export.BibleTextExporter
import com.emfitsolutions.gopreach.data.export.IncomingBibleTextImportHolder
import com.emfitsolutions.gopreach.data.model.BibleTextCategory
import com.emfitsolutions.gopreach.data.model.BibleTextRecord
import com.emfitsolutions.gopreach.data.model.BIBLE_TEXT_KIND_NOTE
import com.emfitsolutions.gopreach.data.model.isNote
import com.emfitsolutions.gopreach.data.model.sortKey
import androidx.compose.material.icons.rounded.StickyNote2
import com.emfitsolutions.gopreach.data.model.BibleTextSubtopic
import com.emfitsolutions.gopreach.data.model.LEGACY_EVENT_PLACEHOLDER
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.repository.BibleTextLanguagePreference
import com.emfitsolutions.gopreach.data.print.ReportTable
import com.emfitsolutions.gopreach.domain.NwtBibleReferenceData
import com.emfitsolutions.gopreach.ui.components.FormDialog
import com.emfitsolutions.gopreach.ui.components.formatRecordTimestamp
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.components.requiredFieldsMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.launch
import androidx.compose.ui.window.DialogProperties

/** Spec §11 — the Publisher may either search across every field at once, or
 * pin the search to one specific one. */
private enum class BibleSearchBy(val label: String) {
    ALL("All"), EVENT("Event"), THEME_TOPIC("Theme/Topic"), SPEAKER("Speaker"), SUB_TOPIC("Sub Topic"), REMARKS("Notes / Bible Text")
}

/** One Event plus its own Bible Text children, resolved once per composition
 * — every list/search/print/export below reads off this instead of each
 * re-joining [BibleTextRecord.categoryId] against the Event list itself. */
private data class EventWithTexts(
    val event: BibleTextCategory,
    val texts: List<BibleTextRecord>,
)

/** "Apocalipsis 21:3-4" (spec's own worked example) — falls back to the raw
 * book id if the book somehow isn't in the reference data (a language/book
 * pairing removed after the record was saved). */
private fun BibleTextRecord.referenceLabel(): String {
    if (isNote()) return "Note"
    val book = NwtBibleReferenceData.book(bibleVersionId, languageId, bibleBookId)
    return "${book?.name ?: bibleBookId} $chapter:$verses"
}


/** What is read aloud for this record: the reference as spoken words, the saved verse text and the Publisher's own note. */
private fun BibleTextRecord.narration(l: com.emfitsolutions.gopreach.domain.NarrationLanguage = com.emfitsolutions.gopreach.domain.NarrationLanguage.ENGLISH): BibleTextNarration.Text {
    val book = NwtBibleReferenceData.book(bibleVersionId, languageId, bibleBookId)
    return BibleTextNarration.Text(
        reference = if (isNote()) "Note" else BibleTextNarration.spokenReference(book?.name ?: bibleBookId, chapter, verses, l),
        verseText = verseText, remarks = remarks, isNote = isNote(),
    )
}
/** A Bible Text matches by its reference (book chapter:verses), its remarks, or - for a Note - the note text. */
private fun BibleTextRecord.matches(query: String): Boolean =
    remarks.contains(query, ignoreCase = true) || verseText.contains(query, ignoreCase = true) || referenceLabel().contains(query, ignoreCase = true)

private val BibleTextCategory.eventLabel: String get() = event.ifBlank { LEGACY_EVENT_PLACEHOLDER }

/**
 * "My Bible Text Record" — a Publisher's personal Event → Bible Text
 * organizer. Add/Edit/Delete an Event (Event and Theme/Topic are searchable
 * dropdowns sourced from this module's existing sample lists, per "Redesign
 * the My Bible Text Record" §1/§2; Speaker stays free text), then add one or
 * more Bible Texts under it — Bible Book is a two-level Book list → that
 * book's Chapters navigator with a Back control (§3-§5), never both shown at
 * once; Bible Language is fixed to English and never shown in the UI (§6).
 * Search across Event/Theme/Speaker/Remarks, plus an optional Bible
 * Book/Chapter reference filter. Every read/write here is scoped to the
 * Publisher's own records only (see [BibleTextRecordViewModel]'s doc comment
 * for the ownership model).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BibleTextRecordScreen(
    publisherPersonId: String,
    @Suppress("UNUSED_PARAMETER") currentPerson: Person?,
    onBack: () -> Unit,
    viewModel: BibleTextRecordViewModel = koinViewModel(),
) {
    val recordsFlow = remember(publisherPersonId) { viewModel.recordsFor(publisherPersonId) }
    val records by recordsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val eventsFlow = remember(publisherPersonId) { viewModel.eventsFor(publisherPersonId) }
    val events by eventsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    val eventsWithTexts = remember(events, records) {
        events.map { event -> EventWithTexts(event, records.filter { it.categoryId == event.id }) }
    }

    var selectedEventId by remember { mutableStateOf<String?>(null) }
    val selected = eventsWithTexts.firstOrNull { it.event.id == selectedEventId }

    if (selected == null) {
        EventListScreen(
            publisherPersonId = publisherPersonId,
            eventsWithTexts = eventsWithTexts,
            onOpenEvent = { selectedEventId = it },
            onBack = onBack,
            viewModel = viewModel,
        )
    } else {
        EventDetailScreen(
            publisherPersonId = publisherPersonId,
            eventWithTexts = selected,
            onBack = { selectedEventId = null },
            onDeleted = { selectedEventId = null },
            viewModel = viewModel,
        )
    }
}

// ---------------------------------------------------------------------------
// Event list — search, filter, and the "+Add Event" entry point.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventListScreen(
    publisherPersonId: String,
    eventsWithTexts: List<EventWithTexts>,
    onOpenEvent: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: BibleTextRecordViewModel,
) {
    val showToast = rememberActionToast()
    val context = LocalContext.current
    val actions = rememberPlatformActions()
    val coroutineScope = rememberCoroutineScope()

    var searchText by remember { mutableStateOf("") }
    var searchBy by remember { mutableStateOf(BibleSearchBy.ALL) }
    var showFilters by remember { mutableStateOf(false) }
    var referenceBookId by remember { mutableStateOf<String?>(null) }
    var referenceChapter by remember { mutableStateOf<Int?>(null) }

    var showAddEvent by remember { mutableStateOf(false) }
    var pendingDeleteEvent by remember { mutableStateOf<EventWithTexts?>(null) }
    var showMoreMenu by remember { mutableStateOf(false) }

    val referenceBooks = remember { NwtBibleReferenceData.booksFor(NwtBibleReferenceData.defaultVersion.id, "en") }
    val referenceBookChapterCount = referenceBooks.firstOrNull { it.id == referenceBookId }?.chapterCount ?: 0

    // "The receiving Publisher can import the data" — shared by the manual
    // file picker below and by a tapped shared export file arriving via
    // [IncomingBibleTextImportHolder] (see that file's own doc comment).
    fun importFromUri(uri: Uri) {
        val file = runCatching {
            context.contentResolver.openInputStream(uri)?.use { BibleTextExporter.parseExportFile(it) }
        }.getOrNull()
        if (file == null) {
            showToast("That file isn't a valid Bible Text Record export.")
            return
        }
        coroutineScope.launch {
            val result = viewModel.importRecords(publisherPersonId, file, eventsWithTexts.map { it.event })
            val eventNote = if (result.newEvents > 0) " and ${result.newEvents} new event${if (result.newEvents == 1) "" else "s"}" else ""
            showToast("Imported ${result.newRecords} record${if (result.newRecords == 1) "" else "s"}$eventNote.")
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFromUri(uri)
    }
    // "If the receiving Publisher downloads and clicks it, it will
    // automatically import to his device" — no picker, no extra tap once
    // this screen is reached; the file was already chosen the moment the
    // Publisher opened it from Downloads/Gmail/Messenger/....
    LaunchedEffect(Unit) {
        IncomingBibleTextImportHolder.uri.collect { uri ->
            if (uri != null) {
                importFromUri(uri)
                IncomingBibleTextImportHolder.consume()
            }
        }
    }

    val filtered = remember(eventsWithTexts, searchText, searchBy, referenceBookId, referenceChapter) {
        eventsWithTexts.filter { item ->
            val referenceMatches = referenceBookId == null || item.texts.any { text ->
                text.bibleBookId == referenceBookId && (referenceChapter == null || text.chapter == referenceChapter)
            }
            if (!referenceMatches) return@filter false

            val query = searchText.trim()
            if (query.isBlank()) return@filter true
            when (searchBy) {
                BibleSearchBy.EVENT -> item.event.eventLabel.contains(query, ignoreCase = true)
                BibleSearchBy.THEME_TOPIC -> item.event.name.contains(query, ignoreCase = true)
                BibleSearchBy.SPEAKER -> item.event.speaker?.contains(query, ignoreCase = true) == true
                BibleSearchBy.SUB_TOPIC -> item.event.subtopics.any { it.name.contains(query, ignoreCase = true) }
                BibleSearchBy.REMARKS -> item.texts.any { it.matches(query) }
                BibleSearchBy.ALL ->
                    item.event.eventLabel.contains(query, ignoreCase = true) ||
                        item.event.name.contains(query, ignoreCase = true) ||
                        item.event.speaker?.contains(query, ignoreCase = true) == true ||
                        item.event.subtopics.any { it.name.contains(query, ignoreCase = true) } ||
                        item.texts.any { it.matches(query) }
            }
        }.sortedByDescending { it.event.updatedAt }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Bible Text Record") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                actions = {
                    // "Move the Add record button to the upper right of the
                    // Record list, make it smaller" — a compact icon button
                    // here instead of the previous full-width button below
                    // the list.
                    IconButton(onClick = { showAddEvent = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = "Add Record")
                    }
                    IconButton(onClick = { showFilters = !showFilters }) {
                        Icon(Icons.Rounded.FilterList, contentDescription = "Filters")
                    }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "Print, Share, or Import")
                        }
                        DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Print") },
                                leadingIcon = { Icon(Icons.Rounded.PictureAsPdf, contentDescription = null) },
                                onClick = { showMoreMenu = false; actions.print(bibleTextReportTable(filtered)) },
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                                onClick = {
                                    showMoreMenu = false
                                    val allRecords = eventsWithTexts.flatMap { it.texts }
                                    val eventsById = eventsWithTexts.associate { it.event.id to it.event }
                                    BibleTextExporter.share(context, BibleTextExporter.buildExportJson(allRecords, eventsById))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Import") },
                                leadingIcon = { Icon(Icons.Rounded.Upload, contentDescription = null) },
                                onClick = { showMoreMenu = false; importLauncher.launch(arrayOf("application/zip", "application/json", "text/plain", "*/*")) },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            HideInFullScreen(false) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    label = { Text("Search Event, Theme, Speaker, Remarks...") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.weight(1f),
                )
            }

            }
            HideInFullScreen(true) {
            if (showFilters) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledDropdown(
                        label = "Search By",
                        selectedLabel = searchBy.label,
                        options = BibleSearchBy.entries.map { it.name to it.label },
                        onSelected = { value -> searchBy = BibleSearchBy.entries.first { it.name == value } },
                    )
                    Text("Search by Bible Reference (optional)", style = MaterialTheme.typography.labelMedium)
                    BibleBookGrid(
                        books = referenceBooks,
                        selectedBookId = referenceBookId,
                        onSelect = { id -> referenceBookId = if (id == referenceBookId) null else id; referenceChapter = null },
                    )
                    if (referenceBookId != null) {
                        Text("Select Chapter", style = MaterialTheme.typography.labelMedium)
                        ChapterGrid(
                            chapterCount = referenceBookChapterCount,
                            selectedChapter = referenceChapter,
                            onSelect = { chapter -> referenceChapter = if (chapter == referenceChapter) null else chapter },
                        )
                    }
                    Row {
                        TextButton(onClick = { searchText = ""; searchBy = BibleSearchBy.ALL; referenceBookId = null; referenceChapter = null }) {
                            Text("Clear")
                        }
                    }
                    HorizontalDivider()
                }
            }

            }
            val viewMode = rememberRecordViewMode("bible_text_events")
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { RecordFound(filtered.size) }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            if (eventsWithTexts.isEmpty()) "No Events saved yet. Tap + above to add one." else "No Events found for the selected search/filter.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                } else {
                    item { RecordViewToggle(viewMode) }
                    recordItems(
                        viewMode, filtered, { it.event.id },
                        listOf(
                                UniversalColumn("Event", 220.dp) { it.event.name.ifBlank { it.event.eventLabel } },
                                UniversalColumn("Date / Label", 190.dp) { it.event.eventLabel },
                                UniversalColumn("Speaker", 170.dp) { it.event.speaker.orEmpty() },
                                UniversalColumn("Bible Texts", 110.dp) { it.texts.size.toString() },
                        ),
                        onRowClick = { onOpenEvent(it.event.id) },
                    ) { item ->
                        EventCard(item = item, onClick = { onOpenEvent(item.event.id) }, onDelete = { pendingDeleteEvent = item })
                    }
                item(key = "end-summary") { com.emfitsolutions.gopreach.ui.components.EndSummary(listOf("Total Records" to filtered.size.toString()), Modifier.padding(top = 8.dp)) }
                }
            }
        }
    }

    if (showAddEvent) {
        AddEditEventDialog(
            existing = null,
            publisherPersonId = publisherPersonId,
            onSave = { event ->
                showAddEvent = false
                coroutineScope.launch {
                    val saved = viewModel.saveEventAndReturn(event)
                    showToast("Event added successfully.")
                    onOpenEvent(saved.id)
                }
            },
            onDismiss = { showAddEvent = false },
        )
    }

    val toDelete = pendingDeleteEvent
    if (toDelete != null) {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { pendingDeleteEvent = null },
            title = { Text("Delete Event?") },
            text = {
                Text(
                    if (toDelete.texts.isEmpty()) {
                        "Are you sure you want to delete \"${toDelete.event.eventLabel}\"?"
                    } else {
                        "This Event contains ${toDelete.texts.size} Bible Text record${if (toDelete.texts.size == 1) "" else "s"}. " +
                            "Deleting the Event will also remove its associated Bible Text records. Do you want to continue?"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteEventCascade(publisherPersonId, toDelete.event.id)
                    showToast("Event deleted successfully.")
                    pendingDeleteEvent = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteEvent = null }) { Text("Cancel") } },
        )
    }
}

/** "Add a print button" — same [ReportTable]/[ReportPrinter] shape every
 * other report screen in this app already uses, one row per Bible Text
 * (spec's own record-level granularity), Event columns repeated per row. */
private fun bibleTextReportTable(items: List<EventWithTexts>): ReportTable {
    val rows = items.flatMap { item ->
        item.texts.map { text ->
            listOf(
                item.event.eventLabel,
                item.event.name,
                item.event.speaker ?: "—",
                text.referenceLabel(),
                text.verseText,
                text.remarks,
            )
        }
    }
    return ReportTable(
        title = "My Bible Text Record",
        count = rows.size,
        countLabel = "Total Bible Texts",
        columns = listOf("Event", "Theme/Topic", "Speaker", "Reference", "Verse Text", "Remarks"),
        rows = rows,
    )
}

/**
 * The heading colours for one Event, taken from the app's own theme (so they
 * follow whichever theme colour the user picked, light or dark). Always the
 * theme's primary/onPrimary pair — this used to rotate between primary,
 * secondary and tertiary per Event (picked from the Event's id), but
 * secondary/tertiary read visibly "faded" next to primary in this app's own
 * colour schemes (a lighter, less saturated tone by design in most Material 3
 * palettes), so some Event titles looked washed out compared to others with
 * no way to tell that was ever intentional. Every Event now gets the exact
 * same colour code and the exact same (full) opacity.
 */
@Composable
private fun eventAccent(@Suppress("UNUSED_PARAMETER") eventId: String): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return scheme.primary to scheme.onPrimary
}

/** A line of text led by a small icon — the icon says at a glance what the line
 * is (event, speaker, date, ...) without having to read the label. */
@Composable
private fun IconLine(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    fontWeight: FontWeight? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    textColor: Color = Color.Unspecified,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(text, style = style, fontWeight = fontWeight, color = textColor)
    }
}

@Composable
private fun EventCard(item: EventWithTexts, onClick: () -> Unit, onDelete: () -> Unit) {
    val (headingBackground, headingText) = eventAccent(item.event.id)
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column {
            // Theme/Topic leads, in capitals and larger (e.g. FAMILY), on a band in
            // the Event's theme colour; the arrow shows the card opens.
            Row(
                modifier = Modifier.fillMaxWidth().background(headingBackground).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.event.name.ifBlank { item.event.eventLabel }.uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = headingText,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Open event", tint = headingText)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconLine(Icons.Rounded.Event, item.event.eventLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, tint = MaterialTheme.colorScheme.primary)
                    if (item.event.speaker?.isNotBlank() == true) {
                        IconLine(Icons.Rounded.Person, "Speaker: ${item.event.speaker}", style = MaterialTheme.typography.bodySmall, tint = MaterialTheme.colorScheme.primary)
                    }
                    IconLine(
                        Icons.AutoMirrored.Rounded.MenuBook,
                        "${item.texts.size} Bible Text${if (item.texts.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Delete event", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Event details — Event info, its Bible Texts, "+Add Bible Text".

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventDetailScreen(
    publisherPersonId: String,
    eventWithTexts: EventWithTexts,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: BibleTextRecordViewModel,
) {
    val showToast = rememberActionToast()
    // Video download results ("downloaded for offline use" / "couldn't download").
    val videoViewModel: VideoViewModel = koinViewModel()
    LaunchedEffect(Unit) { videoViewModel.messages.collect { showToast(it) } }
    val event = eventWithTexts.event
    var showEditEvent by remember { mutableStateOf(false) }
    var showAddText by remember { mutableStateOf(false) }
    // Which subtopic (null = directly under the Event) a just-opened "Add
    // Bible Text" dialog will save into — set right before showAddText is
    // flipped on, by whichever "+" the Publisher tapped (the Event-level one
    // at the top of the Bible Texts section, or one specific subtopic's own).
    var addTextSubtopicId by remember { mutableStateOf<String?>(null) }
    var showAddNote by remember { mutableStateOf(false) }
    var addNoteSubtopicId by remember { mutableStateOf<String?>(null) }
    var showAddVideo by remember { mutableStateOf(false) }
    var pendingEditText by remember { mutableStateOf<BibleTextRecord?>(null) }
    var pendingDeleteText by remember { mutableStateOf<BibleTextRecord?>(null) }
    var pendingDeleteEvent by remember { mutableStateOf(false) }
    var showAddSubtopic by remember { mutableStateOf(false) }
    // The subtopic a new subtopic is created under — null for a top-level one
    // (the Bible Texts section's own "+"), or a subtopic's id when it comes
    // from that subtopic's own "Add Sub Topic Inside" menu item (nesting).
    var addSubtopicParentId by remember { mutableStateOf<String?>(null) }
    var pendingEditSubtopic by remember { mutableStateOf<BibleTextSubtopic?>(null) }
    var pendingDeleteSubtopic by remember { mutableStateOf<BibleTextSubtopic?>(null) }
    // "Allow to select multiple and move to a particular sub topic" — a
    // Bible Text is long-pressed to enter selection mode, tapped again to
    // toggle it in/out, and a "Move" action bulk-reassigns subtopicId for
    // everything selected. True cross-list drag-and-drop is fragile across
    // grouped/nested sections in Compose; tap-select + Move achieves the
    // same outcome reliably.
    var selectedTextIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showMoveToSubtopic by remember { mutableStateOf(false) }
    val selectionMode = selectedTextIds.isNotEmpty()
    fun toggleTextSelection(id: String) {
        selectedTextIds = if (id in selectedTextIds) selectedTextIds - id else selectedTextIds + id
    }
    // "Allow the user to rearrange the subtopic manually" — Move Up/Down
    // swaps [BibleTextSubtopic.order] between two adjacent siblings (same
    // parentId); works the same for top-level subtopics and for ones nested
    // inside another, since both just compare siblings by parentId.
    fun moveSubtopic(subtopic: BibleTextSubtopic, up: Boolean) {
        val siblings = event.subtopics.filter { it.parentId == subtopic.parentId }.sortedWith(compareBy({ it.order }, { it.createdAt }))
        val index = siblings.indexOfFirst { it.id == subtopic.id }
        val swapIndex = if (up) index - 1 else index + 1
        if (index < 0 || swapIndex < 0 || swapIndex >= siblings.size) return
        val other = siblings[swapIndex]
        viewModel.saveEvent(
            event.copy(
                subtopics = event.subtopics.map {
                    when (it.id) {
                        subtopic.id -> it.copy(order = other.order)
                        other.id -> it.copy(order = subtopic.order)
                        else -> it
                    }
                },
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    // The event's own videos, plus any attached to one of its Bible Texts before
    // events had their own gallery (each removed from wherever it is kept).
    val galleryVideos = remember(event, eventWithTexts.texts) {
        event.videos.map { video ->
            GalleryVideo(video) { viewModel.saveEvent(event.copy(videos = event.videos - video, updatedAt = System.currentTimeMillis())) }
        } + eventWithTexts.texts.flatMap { record ->
            record.videos.map { video ->
                GalleryVideo(video) { viewModel.saveRecord(record.copy(videos = record.videos - video, updatedAt = System.currentTimeMillis())) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(event.name.ifBlank { event.eventLabel }.uppercase()) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { showEditEvent = true }) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Edit event", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { pendingDeleteEvent = true }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete event", tint = MaterialTheme.colorScheme.error)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                // Same theme-coloured heading band as this Event's card in the list.
                val (headingBackground, headingText) = eventAccent(event.id)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Column(modifier = Modifier.fillMaxWidth().background(headingBackground).padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text("Event Information", style = MaterialTheme.typography.labelMedium, color = headingText)
                            Text(
                                event.name.ifBlank { event.eventLabel }.uppercase(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = headingText,
                            )
                        }
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            IconLine(Icons.Rounded.Event, "Event: ${event.eventLabel}", style = MaterialTheme.typography.titleMedium, tint = MaterialTheme.colorScheme.primary)
                            IconLine(Icons.Rounded.Person, "Speaker: ${event.speaker?.ifBlank { null } ?: "—"}", style = MaterialTheme.typography.titleMedium, tint = MaterialTheme.colorScheme.primary)
                            IconLine(Icons.Rounded.Schedule, "Created: ${formatRecordTimestamp(event.createdAt)}", style = MaterialTheme.typography.bodySmall)
                            IconLine(Icons.Rounded.Update, "Updated: ${formatRecordTimestamp(event.updatedAt)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                // Listen: the event's details, then every Bible text and note (grouped by sub topic) read aloud, in the language of the texts.
                val narrationLang = com.emfitsolutions.gopreach.ui.components.rememberNarrationLanguage()
                val script = remember(event, eventWithTexts.texts, narrationLang) {
                    val bySub = eventWithTexts.texts.groupBy { it.subtopicId }
                    fun ordered(id: String?) = bySub[id].orEmpty().sortedByDescending { it.sortKey() }.map { it.narration(narrationLang) }
                    val groups = listOf(BibleTextNarration.Group(null, ordered(null))) +
                        flattenSubtopics(event.subtopics).map { (st, _) -> BibleTextNarration.Group(st.name, ordered(st.id)) }
                    BibleTextNarration.eventScript(event.name.ifBlank { event.eventLabel }, event.eventLabel, event.speaker, groups, narrationLang)
                }
                val language = remember(eventWithTexts.texts) { eventWithTexts.texts.firstOrNull { !it.isNote() }?.let { NwtBibleReferenceData.language(it.languageId)?.code } }
                ListenPanel(script, "Listen to this event", language)
            }
            // Bible Texts and Videos are two separate groups, each with its own
            // small "+" in its own header row instead of a full-width button
            // below it (spec: "move the add button to the upper right...
            // make it smaller").
            item {
                val textsBySubtopic = remember(eventWithTexts.texts) { eventWithTexts.texts.groupBy { it.subtopicId } }
                val uncategorized = textsBySubtopic[null].orEmpty().sortedByDescending { it.sortKey() }
                val topLevelSubtopics = remember(event.subtopics) { event.subtopics.filter { it.parentId == null }.sortedWith(compareBy({ it.order }, { it.createdAt })) }
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (selectionMode) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${selectedTextIds.size} selected",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { showMoveToSubtopic = true }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Move to Sub Topic", modifier = Modifier.size(20.dp))
                                }
                                TextButton(onClick = { selectedTextIds = emptySet() }) { Text("Cancel") }
                            }
                        } else {
                            SectionHeader(icon = Icons.AutoMirrored.Rounded.MenuBook, title = "Bible Texts & Notes", count = eventWithTexts.texts.size) {
                                IconButton(onClick = { addSubtopicParentId = null; showAddSubtopic = true }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Rounded.CreateNewFolder, contentDescription = "Add Sub Topic", modifier = Modifier.size(20.dp))
                                }
                                IconButton(onClick = { addNoteSubtopicId = null; showAddNote = true }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Rounded.StickyNote2, contentDescription = "Add Note", modifier = Modifier.size(20.dp))
                                }
                                IconButton(onClick = { addTextSubtopicId = null; showAddText = true }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Rounded.Add, contentDescription = "Add Bible Text", modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                        if (eventWithTexts.texts.isEmpty() && event.subtopics.isEmpty()) {
                            Text("No Bible texts or notes yet. Tap + to add one.", style = MaterialTheme.typography.bodySmall)
                        }
                        if (uncategorized.isNotEmpty()) {
                            ReorderableTexts(
                                texts = uncategorized,
                                selectionMode = selectionMode,
                                onReorder = viewModel::reorderRecords,
                                onEnterSelection = { selectedTextIds = setOf(it) },
                            ) { text, dragModifier ->
                                BibleTextCard(
                                    text = text,
                                    selectionMode = selectionMode,
                                    selected = text.id in selectedTextIds,
                                    onEdit = { pendingEditText = text },
                                    onDelete = { pendingDeleteText = text },
                                    onToggleSelect = { toggleTextSelection(text.id) },
                                    dragModifier = dragModifier,
                                )
                            }
                        }
                        topLevelSubtopics.forEach { subtopic ->
                            SubtopicGroup(
                                subtopic = subtopic,
                                allSubtopics = event.subtopics,
                                textsBySubtopic = textsBySubtopic,
                                depth = 0,
                                selectionMode = selectionMode,
                                selectedTextIds = selectedTextIds,
                                onAddText = { addTextSubtopicId = it; showAddText = true },
                                onAddNote = { addNoteSubtopicId = it; showAddNote = true },
                                onAddChildSubtopic = { addSubtopicParentId = it; showAddSubtopic = true },
                                onRename = { pendingEditSubtopic = it },
                                onDelete = { pendingDeleteSubtopic = it },
                                onReorder = viewModel::reorderRecords,
                                onEditText = { pendingEditText = it },
                                onDeleteText = { pendingDeleteText = it },
                                onToggleSelect = ::toggleTextSelection,
                                onEnterSelection = { selectedTextIds = setOf(it) },
                                onMoveUp = { moveSubtopic(it, up = true) },
                                onMoveDown = { moveSubtopic(it, up = false) },
                            )
                        }
                    }
                }
            }
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader(icon = Icons.Rounded.VideoLibrary, title = "Videos", count = galleryVideos.size) {
                            IconButton(onClick = { showAddVideo = true }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.VideoLibrary, contentDescription = "Add Video", modifier = Modifier.size(20.dp))
                            }
                        }
                        if (galleryVideos.isEmpty()) {
                            Text("No videos yet. Tap + to add one from JW Library.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            VideoGallery(galleryVideos)
                        }
                    }
                }
            }
        }
    }

    if (showEditEvent) {
        AddEditEventDialog(
            existing = event,
            publisherPersonId = publisherPersonId,
            onSave = { updated -> viewModel.saveEvent(updated); showToast("Event updated successfully."); showEditEvent = false },
            onDismiss = { showEditEvent = false },
        )
    }

    if (showAddVideo) {
        AddVideoDialog(
            videos = event.videos,
            onVideosChange = { viewModel.saveEvent(event.copy(videos = it, updatedAt = System.currentTimeMillis())) },
            defaultLocale = "E",
            onDismiss = { showAddVideo = false },
        )
    }

    if (showAddText) {
        BibleTextRecordDialog(
            existing = null,
            eventId = event.id,
            subtopicId = addTextSubtopicId,
            publisherPersonId = publisherPersonId,
            onSave = { viewModel.saveRecord(it); showToast("Bible text added successfully."); showAddText = false },
            onDismiss = { showAddText = false },
        )
    }
    if (showAddNote) {
        NoteDialog(
            existing = null,
            onSave = { note ->
                val now = System.currentTimeMillis()
                viewModel.saveRecord(BibleTextRecord(publisherPersonId = publisherPersonId, categoryId = event.id, subtopicId = addNoteSubtopicId, kind = BIBLE_TEXT_KIND_NOTE, remarks = note, createdAt = now, updatedAt = now))
                showToast("Note added successfully."); showAddNote = false
            },
            onDismiss = { showAddNote = false },
        )
    }
    val toEditText = pendingEditText
    if (toEditText != null) {
        if (toEditText.isNote()) NoteDialog(
            existing = toEditText,
            onSave = { viewModel.saveRecord(toEditText.copy(remarks = it, updatedAt = System.currentTimeMillis())); showToast("Note updated successfully."); pendingEditText = null },
            onDismiss = { pendingEditText = null },
        ) else BibleTextRecordDialog(
            existing = toEditText,
            eventId = event.id,
            publisherPersonId = publisherPersonId,
            onSave = { viewModel.saveRecord(it); showToast("Bible text updated successfully."); pendingEditText = null },
            onDismiss = { pendingEditText = null },
        )
    }
    val toDeleteText = pendingDeleteText
    if (toDeleteText != null) {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { pendingDeleteText = null },
            title = { Text("Delete Bible Text?") },
            text = { Text("Are you sure you want to delete this Bible text (${toDeleteText.referenceLabel()})?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRecord(toDeleteText, toDeleteText.referenceLabel(), publisherPersonId)
                    showToast("Bible text moved to Deleted Records.")
                    pendingDeleteText = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteText = null }) { Text("Cancel") } },
        )
    }
    if (pendingDeleteEvent) {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { pendingDeleteEvent = false },
            title = { Text("Delete Event?") },
            text = {
                Text(
                    if (eventWithTexts.texts.isEmpty()) {
                        "Are you sure you want to delete \"${event.eventLabel}\"?"
                    } else {
                        "This Event contains ${eventWithTexts.texts.size} Bible Text record${if (eventWithTexts.texts.size == 1) "" else "s"}. " +
                            "Deleting the Event will also remove its associated Bible Text records. Do you want to continue?"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteEventCascade(publisherPersonId, event.id)
                    showToast("Event deleted successfully.")
                    pendingDeleteEvent = false
                    onDeleted()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteEvent = false }) { Text("Cancel") } },
        )
    }

    if (showAddSubtopic) {
        AddEditSubtopicDialog(
            existing = null,
            onSave = { name ->
                val now = System.currentTimeMillis()
                val subtopic = BibleTextSubtopic(id = java.util.UUID.randomUUID().toString(), name = name, parentId = addSubtopicParentId, order = now, createdAt = now)
                viewModel.saveEvent(event.copy(subtopics = event.subtopics + subtopic, updatedAt = now))
                showToast("Sub Topic added successfully.")
                showAddSubtopic = false
            },
            onDismiss = { showAddSubtopic = false },
        )
    }
    val toEditSubtopic = pendingEditSubtopic
    if (toEditSubtopic != null) {
        AddEditSubtopicDialog(
            existing = toEditSubtopic,
            onSave = { name ->
                viewModel.saveEvent(
                    event.copy(
                        subtopics = event.subtopics.map { if (it.id == toEditSubtopic.id) it.copy(name = name) else it },
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                showToast("Sub Topic renamed successfully.")
                pendingEditSubtopic = null
            },
            onDismiss = { pendingEditSubtopic = null },
        )
    }
    val toDeleteSubtopic = pendingDeleteSubtopic
    if (toDeleteSubtopic != null) {
        // Deleting a subtopic that has subtopics of its own (nesting) must
        // also remove those descendants — otherwise they'd be left pointing
        // at a parent that no longer exists.
        val affectedIds = subtopicAndDescendantIds(event.subtopics, toDeleteSubtopic.id)
        val affectedCount = eventWithTexts.texts.count { it.subtopicId in affectedIds }
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { pendingDeleteSubtopic = null },
            title = { Text("Delete Sub Topic?") },
            text = {
                Text(
                    if (affectedCount == 0) {
                        "Are you sure you want to delete \"${toDeleteSubtopic.name}\"?"
                    } else {
                        "\"${toDeleteSubtopic.name}\" has $affectedCount Bible Text${if (affectedCount == 1) "" else "s"} (including inside its own sub topics, if any). " +
                            "They will be moved out, not deleted, and will show directly under this Event instead."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val now = System.currentTimeMillis()
                    // "Never accidentally destroy saved Bible Text" — every
                    // affected text (from this subtopic and any nested under
                    // it) moves back to directly-under-the-Event (null)
                    // rather than being deleted along with it.
                    eventWithTexts.texts.filter { it.subtopicId in affectedIds }.forEach { text ->
                        viewModel.saveRecord(text.copy(subtopicId = null, updatedAt = now))
                    }
                    viewModel.saveEvent(event.copy(subtopics = event.subtopics.filterNot { it.id in affectedIds }, updatedAt = now))
                    showToast("Sub Topic deleted successfully.")
                    pendingDeleteSubtopic = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteSubtopic = null }) { Text("Cancel") } },
        )
    }

    if (showMoveToSubtopic) {
        MoveToSubtopicDialog(
            subtopics = event.subtopics,
            onMove = { targetSubtopicId ->
                val now = System.currentTimeMillis()
                eventWithTexts.texts.filter { it.id in selectedTextIds }.forEach { text ->
                    viewModel.saveRecord(text.copy(subtopicId = targetSubtopicId, updatedAt = now))
                }
                showToast("Moved ${selectedTextIds.size} Bible Text${if (selectedTextIds.size == 1) "" else "s"}.")
                selectedTextIds = emptySet()
                showMoveToSubtopic = false
            },
            onDismiss = { showMoveToSubtopic = false },
        )
    }
}

/** [rootId] plus every subtopic nested under it, to any depth — used so
 * deleting one subtopic also removes its own nested subtopics rather than
 * leaving them pointing at a parent that no longer exists. */
private fun subtopicAndDescendantIds(subtopics: List<BibleTextSubtopic>, rootId: String): Set<String> {
    val ids = mutableSetOf(rootId)
    var added = true
    while (added) {
        added = false
        subtopics.forEach { st ->
            if (st.parentId != null && st.parentId in ids && st.id !in ids) {
                ids += st.id
                added = true
            }
        }
    }
    return ids
}

/** [subtopics] in display order, each paired with its nesting depth (0 =
 * top-level) — used by [MoveToSubtopicDialog] so a deeply-nested subtopic's
 * place in the hierarchy is still clear in a flat picker list. */
private fun flattenSubtopics(subtopics: List<BibleTextSubtopic>, parentId: String? = null, depth: Int = 0): List<Pair<BibleTextSubtopic, Int>> =
    subtopics.filter { it.parentId == parentId }.sortedWith(compareBy({ it.order }, { it.createdAt })).flatMap { st ->
        listOf(st to depth) + flattenSubtopics(subtopics, st.id, depth + 1)
    }

/** Bulk-move every selected Bible Text into one chosen Sub Topic (or back to
 * directly-under-the-Event) — see [EventDetailScreen]'s selection-mode doc
 * comment for why this replaces literal drag-and-drop. */
@Composable
private fun MoveToSubtopicDialog(subtopics: List<BibleTextSubtopic>, onMove: (String?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        properties = DialogProperties(dismissOnClickOutside = true, dismissOnBackPress = true),
        onDismissRequest = onDismiss,
        title = { Text("Move to Sub Topic") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item {
                    TextButton(onClick = { onMove(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text("No Sub Topic (directly under Event)", modifier = Modifier.weight(1f))
                    }
                }
                items(flattenSubtopics(subtopics)) { (subtopic, depth) ->
                    TextButton(onClick = { onMove(subtopic.id) }, modifier = Modifier.fillMaxWidth().padding(start = (depth * 16).dp)) {
                        Text(subtopic.name, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Add/rename a Sub Topic — just a name, no other fields (spec: "allow the
 * publisher to add a sub topic"). */
@Composable
private fun AddEditSubtopicDialog(existing: BibleTextSubtopic?, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    FormDialog(
        onDismissRequest = onDismiss,
        title = if (existing == null) "Add Sub Topic" else "Rename Sub Topic",
        onConfirm = {
            if (name.isBlank()) {
                errorMessage = "Sub Topic name is required."
            } else {
                onSave(name.trim())
            }
        },
        confirmLabel = if (existing == null) "Save" else "Rename",
        hasUnsavedChanges = name != existing?.name.orEmpty(),
        errorMessage = errorMessage,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; errorMessage = null },
            label = { Text("Sub Topic Name *") },
            placeholder = { Text("e.g. Introduction, Main Point 1, Conclusion") },
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A section's own title row — icon + "Title (N)" on the left, one or more
 * small icon-only actions on the right (spec: "move the add button to the
 * upper right... make it smaller" — every section-level "add" action on
 * this screen now lives here instead of a full-width button below). */
@Composable
private fun SectionHeader(icon: ImageVector, title: String, count: Int, actions: @Composable RowScope.() -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconLine(
            icon, "$title ($count)",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp), content = actions)
    }
}

/** One Sub Topic's own group within the Bible Texts section — a compact
 * header (name, count, a scoped "+" that adds a Bible Text directly into
 * *this* subtopic, and a ⋮ menu for Rename/Delete/Add Sub Topic Inside)
 * followed by its own Bible Texts, then any subtopics nested under it
 * (rendered recursively, indented one step further each level — "theme
 * inside a sub theme, and so on"). */
@Composable
private fun SubtopicGroup(
    subtopic: BibleTextSubtopic,
    allSubtopics: List<BibleTextSubtopic>,
    textsBySubtopic: Map<String?, List<BibleTextRecord>>,
    depth: Int,
    selectionMode: Boolean,
    selectedTextIds: Set<String>,
    onAddText: (subtopicId: String) -> Unit,
    onAddNote: (subtopicId: String) -> Unit,
    onAddChildSubtopic: (parentId: String) -> Unit,
    onRename: (BibleTextSubtopic) -> Unit,
    onDelete: (BibleTextSubtopic) -> Unit,
    onReorder: (List<BibleTextRecord>) -> Unit,
    onEditText: (BibleTextRecord) -> Unit,
    onDeleteText: (BibleTextRecord) -> Unit,
    onToggleSelect: (String) -> Unit,
    onEnterSelection: (String) -> Unit,
    onMoveUp: (BibleTextSubtopic) -> Unit,
    onMoveDown: (BibleTextSubtopic) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val texts = textsBySubtopic[subtopic.id].orEmpty().sortedByDescending { it.sortKey() }
    val children = remember(allSubtopics, subtopic.id) { allSubtopics.filter { it.parentId == subtopic.id }.sortedWith(compareBy({ it.order }, { it.createdAt })) }
    val siblings = remember(allSubtopics, subtopic.parentId) { allSubtopics.filter { it.parentId == subtopic.parentId }.sortedWith(compareBy({ it.order }, { it.createdAt })) }
    val siblingIndex = siblings.indexOfFirst { it.id == subtopic.id }
    val canMoveUp = siblingIndex > 0
    val canMoveDown = siblingIndex in 0 until siblings.size - 1
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = (8 + depth * 12).dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${subtopic.name} (${texts.size})",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // "Do not hide the icons — Add Sub Topic Inside, Rename and
            // Delete must be shown on top of the Sub Topic" — these three
            // (plus Add Bible Text) are now their own icon buttons in the
            // header row instead of sitting inside a "⋮" menu; only the
            // less-common Move Up/Down stay in an overflow menu.
            IconButton(onClick = { onAddNote(subtopic.id) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Rounded.StickyNote2, contentDescription = "Add Note to ${subtopic.name}", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { onAddText(subtopic.id) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Rounded.Add, contentDescription = "Add Bible Text to ${subtopic.name}", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { onAddChildSubtopic(subtopic.id) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Rounded.CreateNewFolder, contentDescription = "Add Sub Topic Inside ${subtopic.name}", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { onRename(subtopic) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Rounded.Edit, contentDescription = "Rename ${subtopic.name}", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { onDelete(subtopic) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete ${subtopic.name}", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            }
            if (canMoveUp || canMoveDown) {
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "More Sub Topic options", modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        if (canMoveUp) {
                            DropdownMenuItem(text = { Text("Move Up") }, onClick = { showMenu = false; onMoveUp(subtopic) })
                        }
                        if (canMoveDown) {
                            DropdownMenuItem(text = { Text("Move Down") }, onClick = { showMenu = false; onMoveDown(subtopic) })
                        }
                    }
                }
            }
        }
        if (texts.isEmpty() && children.isEmpty()) {
            Text("No Bible texts yet.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            ReorderableTexts(texts = texts, selectionMode = selectionMode, onReorder = onReorder, onEnterSelection = onEnterSelection) { text, dragModifier ->
                BibleTextCard(
                    text = text,
                    selectionMode = selectionMode,
                    selected = text.id in selectedTextIds,
                    onEdit = { onEditText(text) },
                    onDelete = { onDeleteText(text) },
                    onToggleSelect = { onToggleSelect(text.id) },
                    dragModifier = dragModifier,
                )
            }
        }
        children.forEach { child ->
            SubtopicGroup(
                subtopic = child,
                allSubtopics = allSubtopics,
                textsBySubtopic = textsBySubtopic,
                depth = depth + 1,
                selectionMode = selectionMode,
                selectedTextIds = selectedTextIds,
                onAddText = onAddText,
                onAddNote = onAddNote,
                onAddChildSubtopic = onAddChildSubtopic,
                onRename = onRename,
                onDelete = onDelete,
                onReorder = onReorder,
                onEditText = onEditText,
                onDeleteText = onDeleteText,
                onToggleSelect = onToggleSelect,
                onEnterSelection = onEnterSelection,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
            )
        }
    }
}

/** "Make the record smaller" — a compact row instead of the previous
 * generously-padded card: smaller icons/text, tighter spacing, and the
 * Edit/Delete/JW Library actions collapsed into a single icon-only row so
 * more Bible Texts are visible on screen at once without scrolling past
 * mostly-empty space between them. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BibleTextCard(
    text: BibleTextRecord,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    dragModifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val actions = rememberPlatformActions()
    val showToast = rememberActionToast()
    val isNote = text.isNote()
    val bookNumber = NwtBibleReferenceData.book(text.bibleVersionId, text.languageId, text.bibleBookId)?.order
    val jwLocale = NwtBibleReferenceData.language(text.languageId)?.jwLocale
    var listening by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = selectionMode) { onToggleSelect() }.then(dragModifier),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(if (isNote) Icons.Rounded.StickyNote2 else Icons.AutoMirrored.Rounded.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                        Text(text.referenceLabel(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    val preview = text.verseText.ifBlank { text.remarks }
                    if (preview.isNotBlank()) {
                        Text(preview, style = MaterialTheme.typography.bodySmall, maxLines = if (isNote) 6 else 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = { listening = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Headphones, contentDescription = "Listen to this text", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
                if (!isNote && bookNumber != null && jwLocale != null) {
                    IconButton(
                        onClick = { if (!openInJwLibrary(context, jwLocale, bookNumber, text.chapter, text.verses)) showToast("Couldn't open JW Library.") },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Open verse in JW Library", modifier = Modifier.size(18.dp))
                    }
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Edit, contentDescription = "Edit Bible text", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Delete Bible text", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
    if (listening) {
        AlertDialog(
            onDismissRequest = { listening = false },
            title = { Text(text.referenceLabel()) },
            text = {
                ListenPanel(
                    script = com.emfitsolutions.gopreach.ui.components.rememberNarrationLanguage().let { nl -> remember(text, nl) { BibleTextNarration.textScript(text.narration(nl), nl) } },
                    label = if (isNote) "Listen to this note" else "Listen to this text",
                    language = remember(text.languageId) { NwtBibleReferenceData.language(text.languageId)?.code }.takeIf { !isNote },
                )
            },
            confirmButton = { TextButton(onClick = { listening = false }) { Text("Close") } },
        )
    }
}

// ---------------------------------------------------------------------------
// Add/Edit Event

/** "Redesign the My Bible Text Record" §1/§2 — Event and Theme/Topic are now
 * dropdowns sourced from this module's existing sample lists ([SUGGESTED_EVENTS]/
 * [SUGGESTED_THEME_TOPICS] — the exact same values the old free-text fields
 * offered as suggestion chips; no option added, removed, or reworded). Both
 * stay searchable-and-editable ([SearchableOptionField]) rather than a
 * strict closed dropdown, so an existing record's Event/Theme/Topic value is
 * always shown even if it was typed before this list existed and isn't one
 * of the sample values (spec §7 — never silently drop/replace an old value). */
@Composable
private fun AddEditEventDialog(
    existing: BibleTextCategory?,
    publisherPersonId: String,
    onSave: (BibleTextCategory) -> Unit,
    onDismiss: () -> Unit,
) {
    var eventText by remember { mutableStateOf(existing?.event.orEmpty()) }
    var themeTopic by remember { mutableStateOf(existing?.name.orEmpty()) }
    var speaker by remember { mutableStateOf(existing?.speaker.orEmpty()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val message = requiredFieldsMessage(
            "Event" to eventText.isNotBlank(),
            "Theme/Topic" to themeTopic.isNotBlank(),
        )
        if (message != null) {
            errorMessage = message
            return
        }
        val now = System.currentTimeMillis()
        val base = existing ?: BibleTextCategory(publisherPersonId = publisherPersonId, createdAt = now)
        onSave(base.copy(event = eventText.trim(), name = themeTopic.trim(), speaker = speaker.trim().ifBlank { null }, updatedAt = now))
    }

    FormDialog(
        onDismissRequest = onDismiss,
        title = if (existing == null) "Add Event" else "Edit Event",
        onConfirm = ::submit,
        confirmLabel = if (existing == null) "Save Event" else "Save",
        hasUnsavedChanges = eventText != existing?.event.orEmpty() || themeTopic != existing?.name.orEmpty() ||
            speaker != existing?.speaker.orEmpty(),
        errorMessage = errorMessage,
    ) {
        SearchableOptionField(
            label = "Event",
            value = eventText,
            options = SUGGESTED_EVENTS,
            onValueChange = { eventText = it; errorMessage = null },
            placeholder = "Select or search Event",
            required = true,
        )
        // A plain textbox — the publisher writes their own Theme or Topic rather
        // than choosing from a list.
        OutlinedTextField(
            value = themeTopic,
            onValueChange = { themeTopic = it; errorMessage = null },
            label = { Text("Theme/Topic *") },
            placeholder = { Text("Type your own Theme or Topic") },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = speaker,
            onValueChange = { speaker = it },
            label = { Text("Speaker (optional)") },
            placeholder = { Text("e.g. Brother Juan Dela Cruz") },
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A dropdown that's still typeable (a "combobox") — spec §1/§2 want a
 * dropdown the Publisher *selects* from, but also (§7) never lose an old
 * record's value if it isn't one of [options]. Typing narrows [options] to
 * matches (spec: "searchable if the list is long"); picking one from the
 * menu or leaving typed text as-is both just set [value] via [onValueChange]
 * — there's no separate "committed selection" state, so an old custom value
 * displays exactly as saved instead of being coerced onto a list entry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchableOptionField(
    label: String,
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    placeholder: String,
    required: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val filtered = remember(value, options) {
        if (value.isBlank()) options else options.filter { it.contains(value.trim(), ignoreCase = true) }
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it); expanded = true },
            label = { Text(if (required) "$label *" else label) },
            placeholder = { Text(placeholder) },
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
                .onFocusChanged { if (it.isFocused) expanded = true },
        )
        if (filtered.isNotEmpty()) {
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                filtered.forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = { onValueChange(option); expanded = false })
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Add/Edit Bible Text — Book → Chapters two-level navigation (spec §3-§5),
// Bible Language removed from the UI entirely and fixed to English (§6).

private val VERSE_PART_REGEX = Regex("""^\d+(-\d+)?$""")
private val SPACES_AROUND_HYPHEN = Regex("""\s*-\s*""")

/** "3 - 4" and "3–4" (en dash, already converted to "-" as it's typed) read the
 * same as "3-4". */
private fun normalizeVerses(text: String): String = text.replace(SPACES_AROUND_HYPHEN, "-").trim()

/** True for a comma-separated list of verses/ranges ("3", "3-4", "3, 5",
 * "3-4, 8") whose ranges run low to high. Takes text already run through
 * [normalizeVerses]. */
private fun isValidVerseList(normalized: String): Boolean {
    if (normalized.isEmpty()) return false
    return normalized.split(",").map { it.trim() }.all { part ->
        VERSE_PART_REGEX.matches(part) &&
            part.split("-").let { bounds ->
                val numbers = bounds.map { it.toIntOrNull() ?: return false }
                numbers.size == 1 || numbers[0] <= numbers[1]
            }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BibleTextRecordDialog(
    existing: BibleTextRecord?,
    eventId: String,
    publisherPersonId: String,
    onSave: (BibleTextRecord) -> Unit,
    onDismiss: () -> Unit,
    // Which Sub Topic (if any) a brand-new record is created into — see
    // this screen's own "+" placement (the Event-level one passes null,
    // each Sub Topic's own scoped one passes its id). Irrelevant when
    // [existing] is non-null: an edit always keeps that record's own
    // already-saved subtopicId, never moves it.
    subtopicId: String? = null,
) {
    val version = NwtBibleReferenceData.defaultVersion
    val context = LocalContext.current
    val actions = rememberPlatformActions()
    // Bible Language — now with a picker so a record can be kept in another
    // language the New World Translation is published in. An existing record
    // starts on whatever language it already had (spec §7: never silently
    // change saved data); a brand-new record starts on whichever language the
    // publisher picked last time ("make it permanent except the publisher
    // will change it" — see [BibleTextLanguagePreference]), falling back to
    // the first language only the very first time this dialog is ever used.
    var languageId by remember {
        mutableStateOf(
            existing?.languageId?.ifBlank { null }
                ?: BibleTextLanguagePreference.get(context)
                ?: NwtBibleReferenceData.languages.first().id,
        )
    }
    val language = NwtBibleReferenceData.language(languageId)
    val books = remember(languageId) { NwtBibleReferenceData.booksFor(version.id, languageId) }
    var bookId by remember { mutableStateOf(existing?.bibleBookId?.ifBlank { null }) }
    val selectedBook = books.firstOrNull { it.id == bookId }
    // Spec §3 — starts on the Chapters view when editing a record that
    // already has a book (so the Publisher immediately sees what's saved),
    // and on the Book list otherwise. Toggled only by picking a book (spec
    // §3: "Select Genesis -> Genesis Chapters") or pressing Back (spec §4).
    var showingBookList by remember { mutableStateOf(bookId == null) }
    // Spec test 6 — changing the Bible Book always resets the selected
    // Chapter; an already-valid chapter for the *previous* book is never
    // carried over to a book it might not even have that many chapters in.
    var chapter by remember { mutableStateOf(existing?.chapter?.takeIf { it > 0 }) }
    var versesText by remember { mutableStateOf(existing?.verses.orEmpty()) }
    var verseText by remember { mutableStateOf(existing?.verseText.orEmpty()) }
    var remarks by remember { mutableStateOf(existing?.remarks.orEmpty()) }

    // Bug fix: a publisher citing more than one verse in the same chapter
    // (e.g. "16, 18" or "3-4, 8") is a normal, common case — the old regex
    // only ever accepted a single verse ("3") or a single range ("3-4"),
    // silently rejecting anything else as "invalid" even though the field
    // visibly had text in it; from the Save button's error message ("Verses
    // is required.") that read as a bug ("I typed a verse and it still says
    // required"), not as "wrong format". Now a comma-separated list of
    // verses/ranges is accepted too ("3", "3-4", "3, 5", "3-4, 8"); still
    // rejects "abc", "3--4", "-4", "hello", trailing/leading commas, etc.
    // Chapter/verse *existence* is already enforced structurally by the
    // Chapter grid (only ever offers 1..chapterCount) rather than needing a
    // separate existence check here.
    // "3 - 4" and "3–4" (en dash) read the same as "3-4".
    // Used for what's shown on screen. [submit] and [lookUpVerseText] below
    // deliberately re-derive these from [versesText] when they run rather than
    // reading these two values: they are plain values captured when the dialog
    // was composed, and the Save button's callback can keep using the version
    // captured while Verses was still empty — which made Save reject a
    // perfectly valid "1".
    val versesNormalized = normalizeVerses(versesText)
    val verseRangeValid = isValidVerseList(versesNormalized)
    // Verse Text: the scripture itself, imported on request or typed. Nothing is filled in automatically - an import runs only when the
    // publisher picks one of the three sources below, it never overwrites text already there without asking, and the result can be
    // reviewed and edited before saving. Remarks stay the publisher's own note.
    val verseTextViewModel: BibleVerseTextViewModel = koinViewModel()
    val showToast = rememberActionToast()
    val clipboardManager = LocalClipboardManager.current
    val verseScope = rememberCoroutineScope()
    var importState by remember { mutableStateOf(VerseImportState.IDLE) }
    var pendingJwOrgReplace by remember { mutableStateOf(false) }
    var pendingPaste by remember { mutableStateOf<String?>(null) }
    val verseFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val canLookUpVerses = selectedBook != null && chapter != null && verseRangeValid && language != null

    // The reference (book, chapter, verses, language) is the SOURCE of an import. [importedText] is the Verse Text exactly as an import put
    // it there, so clearing the source can remove imported text without ever touching text the publisher typed or edited, or Remarks.
    var importedText by remember { mutableStateOf<String?>(null) }
    var importedFor by remember { mutableStateOf<String?>(null) }
    var importJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun referenceKey() = "$bookId|$chapter|${normalizeVerses(versesText)}|$languageId"

    suspend fun importFromJwOrg() {
        val book = selectedBook ?: return
        val chapterNumber = chapter ?: return
        val jwLocale = language?.jwLocale ?: return
        val sourceAtStart = referenceKey()
        val verseTextAtStart = verseText
        importState = VerseImportState.LOADING
        val text = verseTextViewModel.fetchVerses(jwLocale, book.order, chapterNumber, normalizeVerses(versesText))
        coroutineContext.ensureActive()
        // A slow answer for a reference that has since been cleared or changed, or that arrives after the publisher edited Verse Text,
        // is dropped: it must never repopulate the field.
        if (referenceKey() != sourceAtStart || verseText != verseTextAtStart) {
            if (importState == VerseImportState.LOADING) importState = VerseImportState.IDLE
            return
        }
        if (text.isNullOrBlank()) {
            importState = VerseImportState.FAILED
            return
        }
        verseText = text
        importedText = text
        importedFor = sourceAtStart
        importState = VerseImportState.IMPORTED
    }
    fun startJwOrgImport() {
        importJob?.cancel()
        importJob = verseScope.launch { importFromJwOrg() }
    }
    fun requestJwOrgImport() {
        if (verseText.isBlank()) startJwOrgImport() else pendingJwOrgReplace = true
    }
    // JW Library has no public API, content provider or callback that returns verse text to another app, so a silent background import is not
    // possible (reading its private data or screen is off limits). The closest supported handoff is started only by the publisher's tap:
    // JW Library briefly opens at the verse, the verse is copied there, and when GoPreach is back in front it looks at the clipboard ONCE - never
    // continuously - and offers the copied text for review. Whatever was already on the clipboard is not offered, only something newly copied,
    // and nothing is inserted or called a success until the publisher confirms real text.
    var armedFor by remember { mutableStateOf<String?>(null) }
    var clipboardBefore by remember { mutableStateOf("") }
    fun tryJwLibraryImport() {
        if (!isJwLibraryInstalled(context)) {
            importState = VerseImportState.LIBRARY_FAILED
            return
        }
        val book = selectedBook
        val chapterNumber = chapter
        val jwLocale = language?.jwLocale
        clipboardBefore = clipboardManager.getText()?.text?.replace("\r", "")?.trim().orEmpty() // only to tell later whether something NEW was copied
        armedFor = referenceKey()
        val opened = book != null && chapterNumber != null && jwLocale != null &&
            openInJwLibrary(context, jwLocale, book.order, chapterNumber, normalizeVerses(versesText))
        if (opened) importState = VerseImportState.LIBRARY_WAITING
        else { armedFor = null; importState = VerseImportState.LIBRARY_FAILED }
    }
    // A verse shared from JW Library (Share -> GoPreach) lands here while this form is open: cleaned of its link, shown for confirmation, never
    // inserted unseen. It replaces the clipboard check for that request.
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.emfitsolutions.gopreach.data.export.IncomingSharedVerseText.consumerActive = true
        onDispose { com.emfitsolutions.gopreach.data.export.IncomingSharedVerseText.consumerActive = false }
    }
    // JW Library's Share usually sends just a jw.org link for the verse. GoPreach reads the reference out of that link (book, chapter, verses,
    // language), fills the reference fields and imports the verse text from JW.org; if the share also carries the verse text itself, that text is
    // offered for confirmation instead. A link that is not a single-chapter Bible link is not guessed at.
    val latestRequestJwOrgImport by androidx.compose.runtime.rememberUpdatedState({ requestJwOrgImport() })
    LaunchedEffect(Unit) {
        com.emfitsolutions.gopreach.data.export.IncomingSharedVerseText.text.collect { raw ->
            if (raw == null) return@collect
            armedFor = null
            val link = com.emfitsolutions.gopreach.domain.JwBibleLink.parse(raw)
            val cleaned = com.emfitsolutions.gopreach.domain.SharedVerseText.clean(raw)
            if (link != null) {
                val sharedLanguage = NwtBibleReferenceData.languages.firstOrNull { it.jwLocale.equals(link.locale, ignoreCase = true) }
                    ?: NwtBibleReferenceData.language(languageId)
                val sharedBook = sharedLanguage?.let { NwtBibleReferenceData.booksFor(version.id, it.id).firstOrNull { b -> b.order == link.book } }
                if (sharedLanguage != null && sharedBook != null) {
                    languageId = sharedLanguage.id
                    bookId = sharedBook.id
                    chapter = link.chapter
                    versesText = link.verses
                    showingBookList = false
                    if (cleaned != null) pendingPaste = cleaned
                    else {
                        delay(200) // let the new reference take effect first
                        latestRequestJwOrgImport()
                    }
                } else {
                    showToast("That verse link could not be matched to a Bible book here, so nothing was added.")
                }
            } else if (cleaned == null) {
                showToast("That share had no verse text, so nothing was added.")
            } else {
                pendingPaste = cleaned
            }
            importState = if (importState == VerseImportState.LOADING) importState else VerseImportState.IDLE
            com.emfitsolutions.gopreach.data.export.IncomingSharedVerseText.consume()
        }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && armedFor != null) {
                val requestedFor = armedFor
                armedFor = null // one check per request
                verseScope.launch {
                    delay(350) // let the window regain focus: the clipboard is only readable by the app in front
                    if (pendingPaste != null) return@launch // a verse shared from JW Library already arrived
                    if (requestedFor != referenceKey()) { importState = VerseImportState.IDLE; return@launch }
                    val copiedNow = clipboardManager.getText()?.text?.replace("\r", "")?.trim().orEmpty()
                    if (copiedNow.isNotEmpty() && copiedNow != clipboardBefore) {
                        pendingPaste = copiedNow
                        importState = VerseImportState.IDLE
                    } else {
                        importState = VerseImportState.LIBRARY_NOTHING_COPIED
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Keeps Verse Text in step with its source: a change of source cancels an import still running for the old one, and clearing the source
    // takes imported, unedited Verse Text with it (typed or edited text, and Remarks, are left alone).
    LaunchedEffect(bookId, chapter, versesText, languageId) {
        importJob?.cancel()
        importJob = null
        if (importState == VerseImportState.LOADING) importState = VerseImportState.IDLE
        if (versesText.isBlank() && importedText != null && verseText == importedText) {
            verseText = ""
            importedText = null
            importedFor = null
            importState = VerseImportState.IDLE
        }
    }
    fun pasteCopiedText() {
        val copied = clipboardManager.getText()?.text?.replace("\r", "")?.trim().orEmpty()
        when {
            copied.isEmpty() -> showToast("Nothing copied yet. Copy the verse in JW Library first, then tap here.")
            verseText.isBlank() -> { verseText = copied; importedText = copied; importedFor = referenceKey(); importState = VerseImportState.IMPORTED }
            else -> pendingPaste = copied
        }
    }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    // A validation message from an earlier Save attempt is stale as soon as the
    // publisher changes anything — it must not linger over a now-valid entry.
    LaunchedEffect(bookId, chapter, versesText) { errorMessage = null }

    fun submit() {
        val message = requiredFieldsMessage(
            "Bible Book" to (bookId != null),
            "Chapter" to (chapter != null && chapter!! > 0),
            "Verses" to versesText.isNotBlank(),
        )
        if (message != null) {
            errorMessage = message
            return
        }
        // Reported separately from the block above — a Verses field that
        // *has* text but doesn't parse as a verse/range list is a format
        // problem, not a missing-field one; conflating the two into the same
        // "is required" message is exactly what made this look like a bug
        // rather than a validation error to the Publisher.
        val currentVerses = normalizeVerses(versesText)
        if (!isValidVerseList(currentVerses)) {
            errorMessage = "Please enter a valid verse or verse range (e.g. 3, 3-4, or 3, 5)."
            return
        }
        val now = System.currentTimeMillis()
        val base = existing ?: BibleTextRecord(publisherPersonId = publisherPersonId, categoryId = eventId, subtopicId = subtopicId, createdAt = now)
        onSave(
            base.copy(
                bibleVersionId = version.id,
                languageId = languageId,
                bibleBookId = bookId.orEmpty(),
                chapter = chapter ?: 0,
                verses = currentVerses,
                categoryId = eventId,
                verseText = verseText.trim(),
                remarks = remarks.trim(),
                updatedAt = now,
            ),
        )
    }

    FormDialog(
        onDismissRequest = onDismiss,
        title = if (existing == null) "Add Bible Text" else "Edit Bible Text",
        onConfirm = ::submit,
        confirmLabel = if (existing == null) "Save Bible Text" else "Save",
        errorMessage = errorMessage,
        maxContentHeight = 620.dp,
        hasUnsavedChanges = bookId != existing?.bibleBookId?.ifBlank { null } ||
            languageId != (existing?.languageId?.ifBlank { null } ?: NwtBibleReferenceData.languages.first().id) ||
            chapter != existing?.chapter?.takeIf { it > 0 } ||
            versesText != existing?.verses.orEmpty() || verseText != existing?.verseText.orEmpty() || remarks != existing?.remarks.orEmpty(),
    ) {
        LabeledDropdown(
            label = "Bible Language",
            selectedLabel = language?.name ?: languageId,
            options = NwtBibleReferenceData.languages.map { it.id to it.name },
            onSelected = { newId ->
                if (newId != null) {
                    languageId = newId
                    BibleTextLanguagePreference.set(context, newId)
                }
            },
        )
        Text("Bible Book *", style = MaterialTheme.typography.labelMedium)
        if (showingBookList) {
            // Spec §3 initial view — books only, no chapters shown alongside.
            BibleBookGrid(
                books = books,
                selectedBookId = bookId,
                onSelect = { newBookId ->
                    if (newBookId != bookId) chapter = null
                    bookId = newBookId
                    showingBookList = false
                },
            )
        } else if (selectedBook != null) {
            // Spec §3/§4/§5 — only the selected book's chapters, the book
            // name shown at top, and a Back control that returns to the
            // full book list without losing this book/chapter selection.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { showingBookList = true }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text("Back to Bible Books")
                }
            }
            if (chapter == null) {
                Text(selectedBook.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("Select Chapter *", style = MaterialTheme.typography.labelMedium)
                ChapterGrid(
                    chapterCount = selectedBook.chapterCount,
                    selectedChapter = chapter,
                    onSelect = { chapter = it },
                )
            } else {
                // "Hide the chapter after selecting, show only the verse
                // textbox" — once a chapter is picked, collapse the grid down
                // to a one-line summary with a "Change" link back to it,
                // instead of leaving the whole 1-50 grid on screen.
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${selectedBook.name} Chapter $chapter",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { chapter = null }) { Text("Change") }
                }
            }
        } else {
            // Spec §7 — an old record's [bookId] that no longer matches any
            // current Bible book (e.g. a removed/renamed id) is preserved
            // as-is rather than silently cleared; the Publisher still has to
            // pick a real book from the list to save further changes.
            Text(
                "Previously selected Bible Book \"$bookId\" is no longer available. Please select a Bible Book.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { showingBookList = true }) { Text("Select Bible Book") }
        }
        OutlinedTextField(
            value = versesText,
            onValueChange = { input ->
                versesText = input
                    .map { c -> if (c == '–' || c == '—') '-' else c }
                    .filter { c -> c.isDigit() || c == '-' || c == ',' || c == ' ' }
                    .joinToString("")
            },
            label = { Text("Verses *") },
            placeholder = { Text("e.g. 3, 3-4, or 3, 5") },
            singleLine = true,
            isError = versesText.isNotBlank() && !verseRangeValid,
            supportingText = {
                if (versesText.isNotBlank() && !verseRangeValid) {
                    Text("Please enter a valid verse or verse range (e.g. 3, 3-4, or 3, 5).")
                }
            },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        // ---- Verse Text: pick where the scripture comes from ----
        Text("Verse Text", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        if (!canLookUpVerses) {
            Text(
                "Choose the Bible book, chapter and verses above to import the verse text, or type it yourself.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = ::requestJwOrgImport, enabled = canLookUpVerses && importState != VerseImportState.LOADING, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Import from JW.org", modifier = Modifier.weight(1f))
            }
            OutlinedButton(onClick = ::tryJwLibraryImport, enabled = canLookUpVerses, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.AutoStories, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Import from JW Library", modifier = Modifier.weight(1f))
            }
            OutlinedButton(
                onClick = { importState = VerseImportState.IDLE; runCatching { verseFocus.requestFocus() } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Enter Verse Text Manually", modifier = Modifier.weight(1f))
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = importState != VerseImportState.IDLE,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
        ) {
            val failedColor = MaterialTheme.colorScheme.error
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (importState) {
                    VerseImportState.LOADING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Getting the verse text from JW.org...", style = MaterialTheme.typography.bodySmall)
                    }
                    VerseImportState.IMPORTED -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text("Verse text added below. Review and edit it before saving.", style = MaterialTheme.typography.bodySmall)
                    }
                    VerseImportState.FAILED -> {
                        Text(
                            "Couldn't get this verse from JW.org. The verse may not be available there for this language, or there is no internet connection. Nothing was changed.",
                            style = MaterialTheme.typography.bodySmall, color = failedColor,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = ::startJwOrgImport) { Text("Retry") }
                            TextButton(onClick = { importState = VerseImportState.IDLE; runCatching { verseFocus.requestFocus() } }) { Text("Enter it manually") }
                        }
                    }
                    VerseImportState.LIBRARY_WAITING -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Waiting for the verse from JW Library...", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            "JW Library can't send its Bible text to GoPreach by itself. Easiest: select the verse there, tap Share and choose GoPreach - GoPreach reads the verse from the shared link and imports it. (Or tap Copy and come back.)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(enabled = canLookUpVerses, onClick = ::requestJwOrgImport) { Text("Import from JW.org instead") }
                    }
                    VerseImportState.LIBRARY_NOTHING_COPIED -> {
                        Text("Nothing new was copied, so nothing was imported.", style = MaterialTheme.typography.bodySmall, color = failedColor)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = ::tryJwLibraryImport) { Text("Try again") }
                            TextButton(enabled = canLookUpVerses, onClick = ::requestJwOrgImport) { Text("Import from JW.org") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = ::pasteCopiedText) { Text("Use copied text") }
                            TextButton(onClick = { importState = VerseImportState.IDLE; runCatching { verseFocus.requestFocus() } }) { Text("Enter manually") }
                        }
                    }
                    VerseImportState.LIBRARY_FAILED -> {
                        Text("JW Library isn't installed on this phone (or couldn't be reached), so there is nothing to import from it. Nothing was imported. You can import from JW.org instead, or enter the verse text yourself.", style = MaterialTheme.typography.bodySmall, color = failedColor)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = canLookUpVerses, onClick = ::requestJwOrgImport) { Text("Import from JW.org") }
                            TextButton(onClick = { importState = VerseImportState.IDLE; runCatching { verseFocus.requestFocus() } }) { Text("Enter it manually") }
                        }
                    }
                    VerseImportState.IDLE -> Unit
                }
            }
        }
        OutlinedTextField(
            value = verseText,
            onValueChange = { verseText = it },
            label = { Text("Verse Text") },
            placeholder = { Text("The scripture text") },
            minLines = 4,
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).focusRequester(verseFocus),
        )
        if (importedText != null && verseText == importedText && importedFor != null && importedFor != referenceKey() && versesText.isNotBlank()) {
            Text(
                "This Verse Text was imported for the earlier reference. Import it again for the new one, or edit it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
        // A record saved before Verse Text existed kept its text in Remarks: it is left exactly where it was; the publisher may move it.
        if (existing != null && !existing.isNote() && existing.verseText.isBlank() && remarks.isNotBlank() && verseText.isBlank()) {
            Text(
                "This record's earlier text is in Remarks. If it is the verse text, move it to Verse Text.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { verseText = remarks; remarks = "" }) { Text("Move Remarks to Verse Text") }
        }
        OutlinedTextField(
            value = remarks,
            onValueChange = { remarks = it },
            label = { Text("Remarks (optional)") },
            placeholder = { Text("Your own notes or comments") },
            minLines = 2,
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        )
        if (pendingJwOrgReplace) {
            AlertDialog(
                onDismissRequest = { pendingJwOrgReplace = false },
                title = { Text("Replace Verse Text?") },
                text = { Text("The Verse Text field already has text. Replace it with the text from JW.org?") },
                confirmButton = { TextButton(onClick = { pendingJwOrgReplace = false; startJwOrgImport() }) { Text("Replace") } },
                dismissButton = { TextButton(onClick = { pendingJwOrgReplace = false }) { Text("Keep my text") } },
            )
        }
        pendingPaste?.let { copied ->
            val hasText = verseText.isNotBlank()
            AlertDialog(
                onDismissRequest = { pendingPaste = null },
                title = { Text(if (hasText) "Replace or add the copied text?" else "Use the copied text?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("This is the text received. Check it is the verse you want:", style = MaterialTheme.typography.bodySmall)
                        Text(copied.take(600) + if (copied.length > 600) "..." else "", style = MaterialTheme.typography.bodyMedium)
                        if (hasText) Text("The Verse Text field already has text.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { verseText = copied; importedText = copied; importedFor = referenceKey(); importState = VerseImportState.IMPORTED; pendingPaste = null }) {
                        Text(if (hasText) "Replace" else "Use this text")
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { pendingPaste = null }) { Text("Cancel") }
                        if (hasText) TextButton(onClick = { verseText = verseText.trimEnd() + "\n" + copied; importedText = null; importedFor = null; importState = VerseImportState.IMPORTED; pendingPaste = null }) { Text("Add below") }
                    }
                },
            )
        }
    }
}

/** Shared dropdown shape every non-box picker on this screen uses (Search By,
 * Sort) — [options] is (value, label) so a `null` value ("All", ...) reads
 * naturally alongside real ids. Bible Book/Chapter deliberately do NOT use
 * this — see [BibleBookGrid]/[ChapterGrid]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabeledDropdown(
    label: String,
    selectedLabel: String,
    options: List<Pair<String?, String>>,
    onSelected: (String?) -> Unit,
    required: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(if (required) "$label *" else label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, optionLabel) ->
                DropdownMenuItem(text = { Text(optionLabel) }, onClick = { onSelected(value); expanded = false })
            }
        }
    }
}

/** Add/Edit a free-text Note kept in an Event next to its Bible Texts. */
@Composable
private fun NoteDialog(existing: BibleTextRecord?, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var note by remember { mutableStateOf(existing?.remarks.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    FormDialog(
        onDismissRequest = onDismiss,
        title = if (existing == null) "Add Note" else "Edit Note",
        onConfirm = { if (note.isBlank()) error = "Note is required." else onSave(note.trim()) },
        confirmLabel = if (existing == null) "Save Note" else "Save",
        errorMessage = error,
        hasUnsavedChanges = note != existing?.remarks.orEmpty(),
    ) {
        OutlinedTextField(
            value = note,
            onValueChange = { note = it; error = null },
            label = { Text("Note") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Long-press a Bible Text or Note, then drag it up or down to rearrange the sequence. A long-press released
 * without dragging still starts multi-select (as before). The new order is saved when the finger lifts.
 */
@Composable
private fun ReorderableTexts(
    texts: List<BibleTextRecord>,
    selectionMode: Boolean,
    onReorder: (List<BibleTextRecord>) -> Unit,
    onEnterSelection: (String) -> Unit,
    item: @Composable (BibleTextRecord, Modifier) -> Unit,
) {
    var order by remember(texts) { mutableStateOf(texts) }
    val heights = remember { mutableMapOf<String, Int>() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 4.dp.toPx() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        order.forEach { text ->
            androidx.compose.runtime.key(text.id) {
                val dragging = draggingId == text.id
                val dragModifier = if (selectionMode) Modifier else Modifier.pointerInput(text.id) {
                    var moved = 0f
                    detectDragGesturesAfterLongPress(
                        onDragStart = { draggingId = text.id; dragOffset = 0f; moved = 0f },
                        onDrag = { change, amount ->
                            change.consume()
                            moved += kotlin.math.abs(amount.x) + kotlin.math.abs(amount.y)
                            dragOffset += amount.y
                            val index = order.indexOfFirst { it.id == text.id }
                            val next = order.getOrNull(index + 1)
                            val prev = order.getOrNull(index - 1)
                            if (next != null && dragOffset > (heights[next.id] ?: 0) / 2f) {
                                dragOffset -= (heights[next.id] ?: 0) + gap
                                order = order.toMutableList().also { java.util.Collections.swap(it, index, index + 1) }
                            } else if (prev != null && dragOffset < -(heights[prev.id] ?: 0) / 2f) {
                                dragOffset += (heights[prev.id] ?: 0) + gap
                                order = order.toMutableList().also { java.util.Collections.swap(it, index, index - 1) }
                            }
                        },
                        onDragEnd = {
                            if (moved < 12f) onEnterSelection(text.id)
                            else if (order.map { it.id } != texts.map { it.id }) onReorder(order)
                            draggingId = null; dragOffset = 0f
                        },
                        onDragCancel = { order = texts; draggingId = null; dragOffset = 0f },
                    )
                }
                Box(
                    modifier = Modifier
                        .onGloballyPositioned { heights[text.id] = it.size.height }
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragOffset else 0f; alpha = if (dragging) 0.9f else 1f },
                ) { item(text, dragModifier) }
            }
        }
    }
}

/** Where the Verse Text import stands (shown in a small animated panel under the three import choices). */
private enum class VerseImportState { IDLE, LOADING, IMPORTED, FAILED, LIBRARY_WAITING, LIBRARY_NOTHING_COPIED, LIBRARY_FAILED }
