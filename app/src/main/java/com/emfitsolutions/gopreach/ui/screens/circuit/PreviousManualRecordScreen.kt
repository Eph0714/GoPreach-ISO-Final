package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Remove
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.repository.ManualImportInfo
import com.emfitsolutions.gopreach.data.repository.ManualSummary
import com.emfitsolutions.gopreach.data.repository.SheetSnapshot
import com.emfitsolutions.gopreach.ui.components.AlertDialog
import com.emfitsolutions.gopreach.ui.components.Scaffold
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import org.koin.compose.viewmodel.koinViewModel
import java.text.DateFormat
import java.util.Date

private fun savedOn(millis: Long): String = if (millis <= 0) "" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

/**
 * The Circuit Overseer's Previous Manual Record. An imported Excel file simply opens, looking as it does in Excel (its layout, colours and every worksheet), and
 * can be searched, summarised and read at once. Save then asks for the congregation and the remarks and keeps the whole file; saved files are listed by
 * congregation, searched inside, edited cell by cell and saved again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviousManualRecordScreen(currentPersonId: String, onBack: () -> Unit, viewModel: PreviousManualRecordViewModel = koinViewModel()) {
    val toast = rememberActionToast()
    val viewer by viewModel.viewer.collectAsStateWithLifecycle()
    val saveForm by viewModel.saveForm.collectAsStateWithLifecycle()
    val importDialog by viewModel.importDialog.collectAsStateWithLifecycle()
    val stored by viewModel.stored.collectAsStateWithLifecycle()
    // The congregations enrolled to this Circuit Overseer: the same list every other Circuit Overseer module uses (their access grant).
    val people: CircuitPeopleViewModel = koinViewModel()
    val congregations by remember(currentPersonId) { people.congregations(currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var editingRemarks by remember { mutableStateOf<ManualImportInfo?>(null) }
    var removing by remember { mutableStateOf<ManualImportInfo?>(null) }
    var leaving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.messages.collect { toast(it) } }

    val v = viewer
    if (v == null) {
        Library(viewModel, congregations.map { it.id to it.name }, onBack, onEditRemarks = { editingRemarks = it }, onRemove = { removing = it })
    } else {
        BackHandler { if (v.dirty) leaving = true else viewModel.closeViewer() }
        SheetScreen(v, viewModel, onBack = { if (v.dirty) leaving = true else viewModel.closeViewer() }, onEditRemarks = { editingRemarks = it }, onRemove = { removing = it })
    }

    if (importDialog) ImportDialog(viewModel)
    stored?.let { listing ->
        StoredPicker(listing.path.map { it.id to it.name }, listing.folders.map { it.id to it.name }, listing.files.filter { it.isExcel }.map { Triple(it.id, it.name, it.size) },
            onFolder = viewModel::browseStored, onFile = { id, name -> viewModel.pickStored(id, name) }, onDismiss = { viewModel.closeStored() })
    }
    saveForm?.let { form -> SaveDialog(form, viewModel, congregations.map { it.id to it.name }) }
    editingRemarks?.let { info -> EditRemarksDialog(info, viewModel, onDone = { editingRemarks = null }) }
    removing?.let { info ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove this saved file?") },
            text = { Text("${info.fileName} (${info.sheets.size} worksheet${if (info.sheets.size == 1) "" else "s"}) for ${info.congregationName} will be removed from your saved records. The original Excel file is not touched.") },
            confirmButton = { TextButton(onClick = { viewModel.removeFile(info); removing = null }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Leave without saving?") },
            text = { Text("You have changes that are not saved.") },
            confirmButton = { TextButton(onClick = { leaving = false; viewModel.closeViewer() }) { Text("Leave", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text("Stay") } },
        )
    }
}

// ------------------------------------------------------------------------------------------------------------------------------------------------ the saved files

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Library(viewModel: PreviousManualRecordViewModel, congregations: List<Pair<String, String>>, onBack: () -> Unit, onEditRemarks: (ManualImportInfo) -> Unit, onRemove: (ManualImportInfo) -> Unit) {
    val imports by viewModel.imports.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val searched by viewModel.searched.collectAsStateWithLifecycle()
    val congregationFilter by viewModel.congregationFilter.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var filterMenu by remember { mutableStateOf(false) }

    Scaffold(
        circuitTab = com.emfitsolutions.gopreach.ui.components.CircuitTab.MANUAL_RECORD,
        topBar = { TopAppBar(title = { Text("Previous Manual Record") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query, onValueChange = viewModel::onQuery, singleLine = true,
                placeholder = { Text("Search inside all saved records...") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    Row {
                        if (query.isNotEmpty()) IconButton(onClick = { viewModel.clearSearch() }) { Icon(Icons.Rounded.Close, "Clear search") }
                        TextButton(onClick = { viewModel.search() }) { Text("Search") }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { filterMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(congregations.firstOrNull { it.first == congregationFilter }?.second ?: "All congregations", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Rounded.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                        DropdownMenuItem(text = { Text("All congregations") }, onClick = { filterMenu = false; viewModel.setCongregationFilter(null) })
                        congregations.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { filterMenu = false; viewModel.setCongregationFilter(id) }) }
                    }
                }
                Button(onClick = { viewModel.startImport() }) { Icon(Icons.Rounded.Upload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Import Excel") }
            }
            Text(
                if (loading && imports.isEmpty()) "Loading..." else "${imports.size} saved file${if (imports.size == 1) "" else "s"}" + if (searched.isNotBlank()) " contain \"$searched\"" else "",
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            when {
                error != null && imports.isEmpty() -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { viewModel.reload() }) { Text("Try again") }
                }
                !loading && imports.isEmpty() -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val noFilter = searched.isBlank() && congregationFilter == null
                    Text(if (noFilter) "No previous manual records yet" else "No saved record matches", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (noFilter) "Tap Import Excel to bring in an old record. It opens at once, looking as it does in Excel. Tap Save to keep it for a congregation." else "Try a different word or congregation.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val groups = imports.groupBy { it.congregationId }
                    groups.forEach { (id, files) ->
                        item(key = "h$id") {
                            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
                                Text(files.first().congregationName.uppercase(), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary, maxLines = 2)
                                Text("  ${files.size} saved file${if (files.size == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(files, key = { "f" + it.id }) { info -> FileCard(info, searched.isNotBlank(), onOpen = { index -> viewModel.open(info, index, searched) }, onEditRemarks = { onEditRemarks(info) }, onRemove = { onRemove(info) }) }
                    }
                }
            }
        }
    }
}

/** One saved file: what it is, its remarks, its worksheets (the ones holding the searched text are marked) and the actions. */
@Composable
private fun FileCard(info: ManualImportInfo, searching: Boolean, onOpen: (Int) -> Unit, onEditRemarks: () -> Unit, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) { Icon(Icons.Rounded.TableChart, null, Modifier.padding(8.dp).size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                Column(Modifier.weight(1f)) {
                    Text(info.fileName, fontWeight = FontWeight.Bold)
                    Text(
                        (savedOn(info.createdAt).takeIf { it.isNotBlank() }?.let { "Saved $it · " } ?: "") + "${info.sheets.size} worksheet${if (info.sheets.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (info.remarks.isNotBlank()) {
                            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.weight(1f, fill = false)) {
                                Text("Remarks: ${info.remarks}", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
                            }
                        } else {
                            Text("No remarks", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = onEditRemarks) { Text(if (info.remarks.isBlank()) "Add remarks" else "Edit remarks", style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
            if (searching) Text("Found in:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(info.sheets, key = { it.index }) { s -> FilterChip(selected = searching && s.hit, onClick = { onOpen(s.index) }, label = { Text(s.name) }) }
            }
            Row {
                Button(onClick = { onOpen(info.sheets.firstOrNull { it.hit }?.index ?: 0) }) { Text("Open") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------------------------------------------------------ one record

/** The cell text on screen: the saved text with the not-yet-saved edits on top. */
private fun effectiveTexts(snap: SheetSnapshot, edits: Map<Long, String>): Map<Long, String> {
    val m = HashMap<Long, String>(snap.cells.size + edits.size)
    for (c in snap.cells) if (c.text.isNotEmpty()) m[cellKey(c.r, c.c)] = c.text
    for ((k, v) in edits) if (v.isEmpty()) m.remove(k) else m[k] = v
    return m
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetScreen(v: Viewer, viewModel: PreviousManualRecordViewModel, onBack: () -> Unit, onEditRemarks: (ManualImportInfo) -> Unit, onRemove: (ManualImportInfo) -> Unit) {
    var summaryOpen by remember { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }
    var editCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // Back leaves full screen first; the screen's own Back handler (leave the record) runs only afterwards.
    BackHandler(enabled = fullScreen) { fullScreen = false }
    val texts = remember(v.snapshot, v.edits) { effectiveTexts(v.snapshot, v.edits) }
    val words = remember(v.find) { v.find.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() } }
    val hits = remember(texts, words) { if (words.isEmpty()) emptyList() else texts.filter { (_, t) -> val l = t.lowercase(); words.any { l.contains(it) } }.keys.sorted() }
    var pos by remember(hits) { mutableIntStateOf(if (hits.isEmpty()) -1 else 0) }

    Scaffold(
        circuitTab = com.emfitsolutions.gopreach.ui.components.CircuitTab.MANUAL_RECORD,
        topBar = {
            TopAppBar(
                title = { Text(v.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    TextButton(onClick = { summaryOpen = true }) { Text("Σ Summary") }
                    if (!v.draft) IconButton(onClick = { viewModel.setEditing(!v.editing) }) { Icon(Icons.Rounded.Edit, if (v.editing) "Stop editing" else "Edit cells", tint = if (v.editing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                },
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
        val viewerHeight = maxHeight
        val page = rememberScrollState()
        // In full screen the controls stay exactly as they are, above the viewer; the viewer alone grows to the height of the screen.
        androidx.compose.runtime.LaunchedEffect(fullScreen, page.maxValue) { if (fullScreen && page.maxValue > 0) page.scrollTo(page.maxValue) else if (!fullScreen) page.scrollTo(0) }
        Column(if (fullScreen) Modifier.fillMaxSize().verticalScroll(page) else Modifier.fillMaxSize()) {
            if (v.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                if (v.draft) {
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text("Not saved yet - tap Save to keep this file.", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                    }
                } else v.info?.let { info ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(info.congregationName, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("  Remarks: ${info.remarks.ifBlank { "none" }}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { onEditRemarks(info) }) { Text("Edit") }
                        TextButton(onClick = { onRemove(info) }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }
                }
                if (v.sheets.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(v.sheets, key = { it.index }) { s -> FilterChip(selected = s.index == v.index, onClick = { viewModel.switchSheet(s.index) }, label = { Text(s.name) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = v.find, onValueChange = viewModel::setFind, singleLine = true, modifier = Modifier.weight(1f),
                        placeholder = { Text("Find in this sheet...") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = { if (v.find.isNotEmpty()) IconButton(onClick = { viewModel.setFind("") }) { Icon(Icons.Rounded.Close, "Clear") } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { if (hits.isNotEmpty()) pos = (pos + 1) % hits.size }),
                    )
                    IconButton(onClick = { if (hits.isNotEmpty()) pos = (pos - 1 + hits.size) % hits.size }, enabled = hits.isNotEmpty()) { Icon(Icons.Rounded.KeyboardArrowUp, "Previous match") }
                    IconButton(onClick = { if (hits.isNotEmpty()) pos = (pos + 1) % hits.size }, enabled = hits.isNotEmpty()) { Icon(Icons.Rounded.KeyboardArrowDown, "Next match") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (words.isEmpty()) (if (v.editing) "Tap a cell to change it." else "") else if (hits.isEmpty()) "No match in this sheet" else "${pos + 1} of ${hits.size}",
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { fullScreen = !fullScreen }) { Icon(if (fullScreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(if (fullScreen) "Exit full screen" else "Full screen") }
                    if (v.draft) Button(onClick = { viewModel.askSave() }) { Icon(Icons.Rounded.Save, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Save to my records") }
                    else if (v.dirty) {
                        TextButton(onClick = { viewModel.discardEdits() }) { Text("Discard") }
                        Button(onClick = { viewModel.saveEdits() }, enabled = !v.busy) { Icon(Icons.Rounded.Save, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Save changes (${v.edits.size})") }
                    }
                }
            }
            Box(if (fullScreen) Modifier.fillMaxWidth().height(viewerHeight) else Modifier.weight(1f).fillMaxWidth()) {
                SheetCanvas(
                    v.snapshot, texts, v.edits.keys, hits.toSet(), hits.getOrNull(pos), v.editing && !v.draft,
                    onTapCell = { r, c -> editCell = r to c }, modifier = Modifier.fillMaxSize(),
                )
                if (fullScreen) Button(onClick = { fullScreen = false }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) { Icon(Icons.Rounded.FullscreenExit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Exit Full Screen") }
            }
        }
        }
    }

    if (summaryOpen) SummaryDialog(v.summary, v, viewModel, onDismiss = { summaryOpen = false })
    editCell?.let { (r, c) ->
        var text by remember(r, c) { mutableStateOf(texts[cellKey(r, c)].orEmpty()) }
        AlertDialog(
            onDismissRequest = { editCell = null },
            title = { Text("Cell ${columnName(c)}${r + 1}") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(1000) }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { viewModel.editCell(r, c, text); editCell = null }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { editCell = null }) { Text("Cancel") } },
        )
    }
}

private fun columnName(c: Int): String { var n = c + 1; val sb = StringBuilder(); while (n > 0) { sb.insert(0, 'A' + (n - 1) % 26); n = (n - 1) / 26 }; return sb.toString() }

private const val HDR_W = 42f
private const val HDR_H = 20f
private val NUMBER = Regex("^-?\\d[\\d,]*(\\.\\d+)?$|^-?\\.\\d+$")

/**
 * The worksheet drawn as it looks in Excel: the same merged areas, column widths, row heights, fills, fonts, alignment and borders. It scrolls both ways;
 * matches of the search are yellow (the current one orange), edited cells have a green outline, and tapping a cell in edit mode opens it for changing.
 */
@Composable
private fun SheetCanvas(
    snap: SheetSnapshot, texts: Map<Long, String>, edited: Set<Long>, hits: Set<Long>, current: Long?, editing: Boolean, onTapCell: (Int, Int) -> Unit, modifier: Modifier,
) {
    val density = LocalDensity.current.density
    val layout = remember(snap) {
        val colX = FloatArray(snap.cols + 1); for (c in 0 until snap.cols) colX[c + 1] = colX[c] + (snap.colWidths.getOrElse(c) { 64 }).toFloat()
        val rowY = FloatArray(snap.rows + 1); for (r in 0 until snap.rows) rowY[r + 1] = rowY[r] + (snap.rowHeights.getOrElse(r) { 20 }).toFloat()
        val spans = HashMap<Long, IntArray>(); val origin = HashMap<Long, Long>()
        for (m in snap.merges) { spans[cellKey(m[0], m[1])] = m; for (r in m[0]..m[2]) for (c in m[1]..m[3]) if (r != m[0] || c != m[1]) origin[cellKey(r, c)] = cellKey(m[0], m[1]) }
        Triple(colX, rowY, Pair(spans, origin))
    }
    val (colX, rowY, so) = layout
    val (spans, origin) = so
    val cellStyle = remember(snap) { snap.cells.associate { cellKey(it.r, it.c) to snap.styles.getOrElse(it.style) { snap.styles[0] } } }
    val hs = rememberScrollState()
    val vs = rememberScrollState()
    var viewport by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val totalW = HDR_W + colX[snap.cols]
    val totalH = HDR_H + rowY[snap.rows]

    // Zoom: two fingers pinch the sheet (around the fingers), double-tap fits it to the screen and back, and the buttons zoom with a short glide. One finger still scrolls.
    // [dz] is the pixels per sheet unit at the current zoom. A zoom keeps the point under the fingers where it was (the scroll is set again once the new size is laid out).
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var zoom by remember(snap) { mutableFloatStateOf(1f) }
    var autoFit by remember(snap) { mutableStateOf(false) }
    var pendingScroll by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var backZoom by remember(snap) { mutableStateOf<Float?>(null) }
    val dz = density * zoom
    val fitZoom = remember(viewport, snap) { if (viewport.width > 0) ((viewport.width / density - 2f) / totalW).coerceIn(MIN_ZOOM, 1f) else 1f }
    fun applyZoom(target: Float, cx: Float, cy: Float) {
        val nz = target.coerceIn(MIN_ZOOM, MAX_ZOOM); if (nz == zoom) return
        val k = nz / zoom
        pendingScroll = ((hs.value + cx) * k - cx) to ((vs.value + cy) * k - cy)
        zoom = nz
    }
    fun glideTo(target: Float, cx: Float = viewport.width / 2f, cy: Float = viewport.height / 2f) {
        scope.launch { androidx.compose.animation.core.animate(zoom, target.coerceIn(MIN_ZOOM, MAX_ZOOM), animationSpec = androidx.compose.animation.core.tween(240)) { v, _ -> applyZoom(v, cx, cy) } }
    }
    LaunchedEffect(zoom) {
        val p = pendingScroll ?: return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        hs.scrollTo(p.first.toInt().coerceAtLeast(0)); vs.scrollTo(p.second.toInt().coerceAtLeast(0)); pendingScroll = null
    }
    // while on automatic fit (after Fit was pressed) the sheet follows the size of the screen, e.g. when the phone is turned
    LaunchedEffect(fitZoom, autoFit) { if (autoFit && kotlin.math.abs(zoom - fitZoom) > 0.002f) { pendingScroll = 0f to 0f; zoom = fitZoom } }

    LaunchedEffect(current, viewport) {
        val k = current ?: return@LaunchedEffect
        val r = (k shr 32).toInt(); val c = (k and 0xffffffffL).toInt()
        val x = (HDR_W + colX[c.coerceIn(0, snap.cols - 1)]) * dz; val y = (HDR_H + rowY[r.coerceIn(0, snap.rows - 1)]) * dz
        hs.animateScrollTo((x - viewport.width / 2f).toInt().coerceAtLeast(0))
        vs.animateScrollTo((y - viewport.height / 2f).toInt().coerceAtLeast(0))
    }

    Box(modifier.onSizeChanged { viewport = it }) {
    Box(
        Modifier.fillMaxSize()
            // two fingers pinch (seen first, before the scroll, and only then consumed); one finger is left to the scroll
            .pointerInput(snap) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        if (event.changes.size >= 2 && event.changes.count { it.pressed } >= 2) {
                            val z = event.calculateZoom(); val c = event.calculateCentroid()
                            if (z != 1f && c.isSpecified) { autoFit = false; applyZoom(zoom * z, c.x, c.y) }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(snap, fitZoom) {
                detectTapGestures(onDoubleTap = { off ->
                    if (backZoom != null && kotlin.math.abs(zoom - fitZoom) < 0.02f) { autoFit = false; glideTo(backZoom!!, off.x, off.y); backZoom = null }
                    else { backZoom = zoom; autoFit = true; pendingScroll = 0f to 0f; glideTo(fitZoom, 0f, 0f) }
                })
            }
            .horizontalScroll(hs).verticalScroll(vs),
    ) {
        Canvas(
            Modifier.size((totalW * zoom).dp, (totalH * zoom).dp).pointerInput(snap, editing, zoom) {
                if (editing) detectTapGestures { off ->
                    val x = off.x / dz - HDR_W; val y = off.y / dz - HDR_H
                    if (x < 0 || y < 0) return@detectTapGestures
                    var c = colX.indexOfLast { it <= x }; var r = rowY.indexOfLast { it <= y }
                    if (c < 0 || r < 0 || c >= snap.cols || r >= snap.rows) return@detectTapGestures
                    origin[cellKey(r, c)]?.let { o -> r = (o shr 32).toInt(); c = (o and 0xffffffffL).toInt() }
                    onTapCell(r, c)
                }
            },
        ) {
            val d = dz
            drawIntoCanvas { canvas ->
                val cv = canvas.nativeCanvas
                val fill = android.graphics.Paint().apply { style = android.graphics.Paint.Style.FILL }
                val line = android.graphics.Paint().apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 1f }
                val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                fun color(hex: String?, fallback: Int) = runCatching { android.graphics.Color.parseColor(hex) }.getOrDefault(fallback)
                fun x(c: Int) = (HDR_W + colX[c]) * d
                fun y(r: Int) = (HDR_H + rowY[r]) * d
                fun rectOf(r: Int, c: Int): FloatArray { val sp = spans[cellKey(r, c)]; val r2 = (sp?.get(2) ?: r) + 1; val c2 = (sp?.get(3) ?: c) + 1; return floatArrayOf(x(c), y(r), x(c2.coerceAtMost(snap.cols)), y(r2.coerceAtMost(snap.rows))) }

                cv.drawColor(android.graphics.Color.WHITE)
                // headers
                fill.color = 0xFFF1F3F4.toInt(); cv.drawRect(0f, 0f, totalW * d, HDR_H * d, fill); cv.drawRect(0f, 0f, HDR_W * d, totalH * d, fill)
                text.color = 0xFF666666.toInt(); text.textSize = 11f * d; text.textAlign = android.graphics.Paint.Align.CENTER; text.isFakeBoldText = false
                for (c in 0 until snap.cols) if (colX[c + 1] > colX[c]) cv.drawText(columnName(c), (x(c) + x(c + 1)) / 2, (HDR_H - 6f) * d, text)
                for (r in 0 until snap.rows) if (rowY[r + 1] > rowY[r]) cv.drawText((r + 1).toString(), HDR_W * d / 2, (y(r) + y(r + 1)) / 2 + 4f * d, text)
                // grid
                line.color = 0xFFDCDCDC.toInt()
                for (c in 0..snap.cols) cv.drawLine(x(c), 0f, x(c), totalH * d, line)
                for (r in 0..snap.rows) cv.drawLine(0f, y(r), totalW * d, y(r), line)
                // fills: merged areas first (they hide the grid inside), then coloured cells, then matches
                for ((k, sp) in spans) { val r = (k shr 32).toInt(); val c = (k and 0xffffffffL).toInt(); fill.color = color(cellStyle[k]?.bg, android.graphics.Color.WHITE); val b = rectOf(r, c); cv.drawRect(b[0], b[1], b[2], b[3], fill) }
                for (cell in snap.cells) { val st = cellStyle[cellKey(cell.r, cell.c)] ?: continue; if (st.bg != null && !spans.containsKey(cellKey(cell.r, cell.c))) { fill.color = color(st.bg, 0); val b = rectOf(cell.r, cell.c); cv.drawRect(b[0], b[1], b[2], b[3], fill) } }
                for (k in hits) { val r = (k shr 32).toInt(); val c = (k and 0xffffffffL).toInt(); if (r < snap.rows && c < snap.cols) { fill.color = if (k == current) 0xFFFFB74D.toInt() else 0xFFFFF176.toInt(); val b = rectOf(r, c); cv.drawRect(b[0], b[1], b[2], b[3], fill) } }
                // borders
                line.color = 0xFF222222.toInt()
                for (cell in snap.cells) {
                    val st = cellStyle[cellKey(cell.r, cell.c)] ?: continue
                    if (!(st.top || st.bottom || st.left || st.right)) continue
                    val b = rectOf(cell.r, cell.c)
                    if (st.top) cv.drawLine(b[0], b[1], b[2], b[1], line); if (st.bottom) cv.drawLine(b[0], b[3], b[2], b[3], line)
                    if (st.left) cv.drawLine(b[0], b[1], b[0], b[3], line); if (st.right) cv.drawLine(b[2], b[1], b[2], b[3], line)
                }
                // text
                for ((k, value) in texts) {
                    val r = (k shr 32).toInt(); val c = (k and 0xffffffffL).toInt()
                    if (r >= snap.rows || c >= snap.cols || origin.containsKey(k) || colX[c + 1] <= colX[c] || rowY[r + 1] <= rowY[r]) continue
                    val st = cellStyle[k]
                    val b = rectOf(r, c)
                    text.color = color(st?.fg, android.graphics.Color.BLACK); text.textSize = (if ((st?.size ?: 0) > 0) st!!.size * 1.2f else 13f) * d
                    text.isFakeBoldText = st?.bold == true; text.textSkewX = if (st?.italic == true) -0.25f else 0f; text.isUnderlineText = st?.underline == true
                    val align = st?.ha ?: if (NUMBER.matches(value.trim())) "right" else "left"
                    val pad = 3f * d
                    cv.save(); cv.clipRect(b[0], b[1], b[2], b[3])
                    val lines = if (st?.wrap == true) wrapLines(value, text, b[2] - b[0] - 2 * pad) else value.split('\n')
                    val lh = text.fontSpacing
                    if ((st?.rot ?: 0) != 0) {
                        text.textAlign = android.graphics.Paint.Align.CENTER
                        cv.translate((b[0] + b[2]) / 2, (b[1] + b[3]) / 2); cv.rotate(if (st!!.rot == 90) -90f else if (st.rot == 255) 0f else 90f)
                        cv.drawText(value, 0f, -(text.ascent() + text.descent()) / 2, text)
                    } else {
                        text.textAlign = when (align) { "center" -> android.graphics.Paint.Align.CENTER; "right" -> android.graphics.Paint.Align.RIGHT; else -> android.graphics.Paint.Align.LEFT }
                        val px = when (align) { "center" -> (b[0] + b[2]) / 2; "right" -> b[2] - pad; else -> b[0] + pad }
                        val blockH = lh * lines.size
                        val top = when (st?.va) { "top" -> b[1] + 2f * d; "center" -> (b[1] + b[3]) / 2 - blockH / 2; else -> b[3] - blockH - 1f * d }
                        lines.forEachIndexed { i, ln -> cv.drawText(ln, px, top + lh * i - text.ascent(), text) }
                    }
                    cv.restore()
                }
                // edited cells
                line.color = 0xFF43A047.toInt(); line.strokeWidth = 2f * d
                for (k in edited) { val r = (k shr 32).toInt(); val c = (k and 0xffffffffL).toInt(); if (r < snap.rows && c < snap.cols) { val b = rectOf(r, c); cv.drawRect(b[0], b[1], b[2], b[3], line) } }
                if (current != null) { line.color = 0xFFE65100.toInt(); val r = (current shr 32).toInt(); val c = (current and 0xffffffffL).toInt(); if (r < snap.rows && c < snap.cols) { val b = rectOf(r, c); cv.drawRect(b[0], b[1], b[2], b[3], line) } }
            }
        }
    }
    ZoomPill(
        percent = kotlin.math.round(zoom * 100).toInt(), atFit = kotlin.math.abs(zoom - fitZoom) < 0.01f,
        onOut = { autoFit = false; glideTo(zoom / 1.35f) }, onIn = { autoFit = false; glideTo(zoom * 1.35f) },
        onFit = { backZoom = null; autoFit = true; pendingScroll = 0f to 0f; glideTo(fitZoom, 0f, 0f) },
        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
    )
    }
}

private const val MIN_ZOOM = 0.05f
private const val MAX_ZOOM = 3f

/** A small floating pill over the sheet: zoom out, the level, zoom in, and Fit (the whole width on the screen). It slides in when the sheet opens and fades its hint away. */
@Composable
private fun ZoomPill(percent: Int, atFit: Boolean, onOut: () -> Unit, onIn: () -> Unit, onFit: () -> Unit, modifier: Modifier = Modifier) {
    var hint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(3500); hint = false }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    androidx.compose.animation.AnimatedVisibility(
        visible = shown, modifier = modifier,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(350)) + androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(450)) { it / 2 } + androidx.compose.animation.scaleIn(initialScale = 0.9f),
    ) {
        Column(horizontalAlignment = Alignment.End) {
            androidx.compose.animation.AnimatedVisibility(visible = hint, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(500))) {
                Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.9f), modifier = Modifier.padding(bottom = 8.dp)) {
                    Text("Pinch to zoom  ·  double-tap to fit", Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.inverseOnSurface)
                }
            }
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(50), shadowElevation = 10.dp, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)) {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onOut) { Icon(androidx.compose.material.icons.Icons.Rounded.Remove, "Zoom out") }
                    Text("$percent%", Modifier.widthIn(min = 44.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = onIn) { Icon(androidx.compose.material.icons.Icons.Rounded.Add, "Zoom in") }
                    androidx.compose.material3.FilledTonalButton(onClick = onFit, contentPadding = PaddingValues(horizontal = 12.dp), colors = if (atFit) androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) else androidx.compose.material3.ButtonDefaults.filledTonalButtonColors()) {
                        Icon(androidx.compose.material.icons.Icons.Rounded.FitScreen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Fit")
                    }
                }
            }
        }
    }
}

/** Breaks [value] into lines that fit [width] pixels (words kept whole when they can be). */
private fun wrapLines(value: String, paint: android.graphics.Paint, width: Float): List<String> {
    val out = mutableListOf<String>()
    for (para in value.split('\n')) {
        var cur = ""
        for (word in para.split(' ')) {
            val trial = if (cur.isEmpty()) word else "$cur $word"
            if (paint.measureText(trial) <= width || cur.isEmpty()) cur = trial else { out += cur; cur = word }
        }
        out += cur
    }
    return out
}

// ------------------------------------------------------------------------------------------------------------------------------------------------ summary

@Composable
private fun SummaryDialog(s: ManualSummary, v: Viewer, viewModel: PreviousManualRecordViewModel, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(v.index, v.comparison) { viewModel.startCompare() }
    androidx.compose.runtime.LaunchedEffect(v.index, v.info?.id) { if (!v.draft) viewModel.startAnalysis() }
    val compare by viewModel.compare.collectAsStateWithLifecycle()
    val analysisUi by viewModel.analysis.collectAsStateWithLifecycle()
    val analysis = analysisUi.analysis?.takeIf { analysisUi.forImport == v.info?.id && analysisUi.forIndex == v.index }
    val showAnalysis = !v.draft && compare.other == null
    // The words read aloud: the analysis' narration (the AI's wording only once it was checked, otherwise GoPreach's own sentences), the other-file report's, or the worksheet's own summary.
    val sheetScript = remember(s) { com.emfitsolutions.gopreach.domain.ManualSummaryNarration.script(s) }
    val script = (if (showAnalysis) analysis?.narration else compare.report?.voice)?.takeIf { it.isNotEmpty() } ?: sheetScript
    val shareText: (String) -> Unit = { text ->
        context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_SUBJECT, "Previous Manual Record - Summary and Comparative Analysis"); putExtra(android.content.Intent.EXTRA_TEXT, text) }, "Export Summary"))
    }
    val narrator = remember { ReportNarrator(context, emptyList()) }
    androidx.compose.runtime.LaunchedEffect(script) { narrator.load(script) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { narrator.shutdown() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Summary" + if (s.title.isNotBlank()) " - ${s.title}" else "", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") }
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (showAnalysis && analysisUi.aiBusy) Text("Preparing the narration...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else ReadReportPanel(narrator, script, label = "Listen to Summary")
                    if (showAnalysis) AnalysisView(analysisUi.takeIf { analysisUi.forImport == v.info?.id && analysisUi.forIndex == v.index } ?: AnalysisUi(loading = true), compare, viewModel, shareText)
                    else ComparativeReport(compare, v.comparison.message.ifBlank { "No comparable records were found in this Excel file. Upload a file containing matching indicators across different periods, visits, or congregations to generate a comparative report." }, canChoose = !v.draft, viewModel = viewModel)
                    HorizontalDivider()
                    Text("Worksheet details", fontWeight = FontWeight.SemiBold)
                    Text("${s.rows} rows × ${s.cols} columns · ${s.filled} filled cells · ${s.numeric} numbers · ${s.merged} merged areas · ${s.formulaCount} formulas", style = MaterialTheme.typography.bodyMedium)
                    if (s.notes.isNotEmpty()) {
                        Text("Worth checking", fontWeight = FontWeight.SemiBold)
                        s.notes.forEach { n -> Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer) { Text(n, Modifier.fillMaxWidth().padding(8.dp), style = MaterialTheme.typography.bodySmall) } }
                    }
                    if (s.columns.size >= 2) {
                        Text("Comparison between the columns", fontWeight = FontWeight.SemiBold)
                        com.emfitsolutions.gopreach.domain.ManualSummaryNarration.comparison(s.columns).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                    if (s.columns.isNotEmpty()) {
                        Text("Numbers by column", fontWeight = FontWeight.SemiBold)
                        Card { Column(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
                            Row { listOf("Column" to 70, "Heading" to 150, "Count" to 60, "Total" to 90, "Average" to 90).forEach { (h, w) -> Text(h, Modifier.width(w.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge) } }
                            HorizontalDivider()
                            s.columns.forEach { c -> Row(Modifier.padding(vertical = 3.dp)) {
                                Text(c.column, Modifier.width(70.dp)); Text(c.label, Modifier.width(150.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(c.count.toString(), Modifier.width(60.dp)); Text(num(c.sum), Modifier.width(90.dp)); Text(num(c.average), Modifier.width(90.dp))
                            } }
                        } }
                    }
                    if (s.functions.isNotEmpty()) {
                        Text("Formulas used in this sheet", fontWeight = FontWeight.SemiBold)
                        Text(s.functions.entries.sortedByDescending { it.value }.joinToString("  ·  ") { "${it.key} × ${it.value}" }, style = MaterialTheme.typography.bodyMedium)
                    } else Text("This sheet has no formulas.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.formulas.isNotEmpty()) {
                        Text("The formulas (${s.formulas.size}${if (s.formulaCount > s.formulas.size) " of ${s.formulaCount}" else ""})", fontWeight = FontWeight.SemiBold)
                        s.formulas.forEach { f -> Text("${f.cell}  =${f.formula}   →  ${f.shows}", style = MaterialTheme.typography.bodySmall) }
                        Text("Formulas are only read. The numbers shown are the ones saved in the file; nothing is recalculated.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun num(v: Double): String = if (v == Math.floor(v) && Math.abs(v) < 1e12) v.toLong().toString() else "%.2f".format(v)

// ------------------------------------------------------------------------------------------------------------------------------------------------ dialogs

@Composable
private fun EditRemarksDialog(info: ManualImportInfo, viewModel: PreviousManualRecordViewModel, onDone: () -> Unit) {
    var text by remember(info.id) { mutableStateOf(info.remarks) }
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = { if (!saving) onDone() },
        title = { Text("Remarks") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it.take(500) }, label = { Text("Remarks about ${info.fileName}") }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { viewModel.editRemarks(info, text.trim(), onDone) }, enabled = !saving) { Text(if (saving) "Saving..." else "Save") } },
        dismissButton = { TextButton(onClick = onDone, enabled = !saving) { Text("Cancel") } },
    )
}

/** Import: only the file is chosen. It opens at once; the congregation and the remarks are asked when the user saves. */
@Composable
private fun ImportDialog(viewModel: PreviousManualRecordViewModel) {
    val reading by viewModel.reading.collectAsStateWithLifecycle()
    val error by viewModel.importError.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) viewModel.pickFile(uri) }
    AlertDialog(
        onDismissRequest = { if (!reading) viewModel.cancelImport() },
        title = { Text("Import Excel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Choose the Excel file. It opens right away, looking as it does in Excel. It is only kept when you tap Save.", style = MaterialTheme.typography.bodySmall)
                FilledTonalButton(onClick = { picker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "text/csv", "text/comma-separated-values", "application/octet-stream")) }, enabled = !reading) { Text("From this phone") }
                OutlinedButton(onClick = { viewModel.browseStored(null) }, enabled = !reading) { Icon(Icons.Rounded.Folder, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("From File Manager") }
                Text(".xlsx, .xls or .csv, up to 10 MB.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (reading) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("  Reading the file...") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { viewModel.cancelImport() }, enabled = !reading) { Text("Cancel") } },
    )
}

/** Save: the congregation (required) and the remarks (optional) of the file on screen. */
@Composable
private fun SaveDialog(form: SaveForm, viewModel: PreviousManualRecordViewModel, congregations: List<Pair<String, String>>) {
    var menu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!form.saving) viewModel.cancelSave() },
        title = { Text("Save this record") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Congregation *", fontWeight = FontWeight.SemiBold)
                Box {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(congregations.firstOrNull { it.first == form.congregationId }?.second ?: "Choose a congregation", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Rounded.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (congregations.isEmpty()) DropdownMenuItem(text = { Text("No congregations are assigned to you") }, onClick = { menu = false })
                        congregations.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { menu = false; viewModel.setCongregation(id) }) }
                    }
                }
                OutlinedTextField(value = form.remarks, onValueChange = viewModel::setRemarks, label = { Text("Remarks (optional)") }, placeholder = { Text("e.g. visit of March 2026") }, modifier = Modifier.fillMaxWidth())
                Text("The whole file is saved the way it looks, with all its worksheets. You can open it again any time.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { viewModel.confirmSave() }, enabled = !form.saving && form.congregationId != null) { Text(if (form.saving) "Saving..." else "Save") } },
        dismissButton = { TextButton(onClick = { viewModel.cancelSave() }, enabled = !form.saving) { Text("Cancel") } },
    )
    if (form.duplicate) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDuplicate() },
            title = { Text("Already saved") },
            text = { Text("This file was already saved for this congregation. Saving it again would keep a second copy.") },
            confirmButton = { TextButton(onClick = { viewModel.confirmSave(allowDuplicate = true) }) { Text("Save anyway") } },
            dismissButton = { TextButton(onClick = { viewModel.dismissDuplicate() }) { Text("Cancel") } },
        )
    }
}

/** Browse the File Manager and pick one Excel / CSV file. Nothing is moved or changed. */
@Composable
private fun StoredPicker(path: List<Pair<String, String>>, folders: List<Pair<String, String>>, files: List<Triple<String, String, Long>>, onFolder: (String?) -> Unit, onFile: (String, String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("File Manager") },
        text = {
            Column {
                Text(("File Manager" + path.joinToString("") { " / ${it.second}" }), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (path.isNotEmpty()) item { PickRow("..  (up one folder)", null) { onFolder(path.dropLast(1).lastOrNull()?.first) } }
                    items(folders, key = { "d" + it.first }) { f -> PickRow(f.second, "Folder") { onFolder(f.first) } }
                    items(files, key = { "f" + it.first }) { f -> PickRow(f.second, sizeLabel(f.third)) { onFile(f.first, f.second) } }
                    if (folders.isEmpty() && files.isEmpty()) item { Text("No folders or Excel files here.", Modifier.padding(vertical = 12.dp)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun PickRow(name: String, hint: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}
