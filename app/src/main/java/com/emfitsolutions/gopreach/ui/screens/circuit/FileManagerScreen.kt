package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.repository.CoFile
import com.emfitsolutions.gopreach.data.repository.CoFolder
import com.emfitsolutions.gopreach.data.repository.CoSearchItem
import com.emfitsolutions.gopreach.ui.components.Scaffold
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import java.text.DateFormat
import java.util.Date

/** The File Manager takes PDF, Excel and Word files only. */
private val DOCUMENT_MIME_TYPES = arrayOf(
    "application/pdf", "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
)

/** The Circuit Overseer's File Manager: folders and subfolders, uploads, downloads, preview, search, list / grid view and a recycle bin. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileManagerScreen(onBack: () -> Unit, viewModel: FileManagerViewModel = koinViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberActionToast()
    val folderId by viewModel.folderId.collectAsStateWithLifecycle()
    val listing by viewModel.listing.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val tree by viewModel.tree.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val descending by viewModel.descending.collectAsStateWithLifecycle()
    val grid by viewModel.grid.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val uploads by viewModel.uploads.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val warning by viewModel.folderWarning.collectAsStateWithLifecycle()
    val trash by viewModel.trash.collectAsStateWithLifecycle()

    var creating by remember { mutableStateOf(false) }
    var renamingFolder by remember { mutableStateOf<CoFolder?>(null) }
    var renamingFile by remember { mutableStateOf<CoFile?>(null) }
    var movingFolder by remember { mutableStateOf<CoFolder?>(null) }
    var movingFile by remember { mutableStateOf<CoFile?>(null) }
    var movingSelection by remember { mutableStateOf(false) }
    var deletingFile by remember { mutableStateOf<CoFile?>(null) }
    var deletingFolder by remember { mutableStateOf<CoFolder?>(null) }
    var deletingSelection by remember { mutableStateOf(false) }
    var detailsOf by remember { mutableStateOf<CoFile?>(null) }
    var previewOf by remember { mutableStateOf<CoFile?>(null) }
    var sortMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.messages.collect { toast(it) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> viewModel.upload(uris) }

    fun download(file: CoFile) {
        scope.launch {
            runCatching { viewModel.saveToDownloads(file, viewModel.bytesOf(file)) }
                .onSuccess { toast(it) }
                .onFailure { toast(it.message ?: "The download failed.") }
        }
    }

    val selecting = selection.isNotEmpty()
    // Back climbs one folder first; only at the top level does it leave the module.
    val goBack = {
        when {
            selecting -> viewModel.clearSelection()
            query.isNotBlank() -> viewModel.clearSearch()
            listing?.path?.isNotEmpty() == true -> viewModel.open(listing?.path?.dropLast(1)?.lastOrNull()?.id)
            else -> onBack()
        }
    }
    androidx.activity.compose.BackHandler(enabled = selecting || query.isNotBlank() || listing?.path?.isNotEmpty() == true) { goBack() }

    Scaffold(
        circuitTab = com.emfitsolutions.gopreach.ui.components.CircuitTab.FILES,
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${selection.size} selected") },
                    navigationIcon = { IconButton(onClick = { viewModel.clearSelection() }) { Icon(Icons.Rounded.Close, "Clear selection") } },
                    actions = {
                        IconButton(onClick = { movingSelection = true }) { Icon(Icons.Rounded.DriveFileMove, "Move") }
                        IconButton(onClick = { deletingSelection = true }) { Icon(Icons.Rounded.Delete, "Delete") }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("File Manager") },
                    navigationIcon = { IconButton(onClick = goBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                    actions = { IconButton(onClick = { viewModel.openTrash() }) { Icon(Icons.Rounded.RestoreFromTrash, "Recycle bin") } },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQuery,
                placeholder = { Text("Search files and folders...") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { viewModel.clearSearch() }) { Icon(Icons.Rounded.Close, "Clear search") } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (query.isNotBlank()) {
                SearchResults(
                    searching = searching, result = search?.items.orEmpty(), total = search?.total ?: 0,
                    onOpenFolder = { id -> viewModel.open(id) },
                    onDownload = { item -> download(CoFile(id = item.id, name = item.name, ext = item.ext, mime = mimeOf(item.ext))) },
                )
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { picker.launch(DOCUMENT_MIME_TYPES) }) { Icon(Icons.Rounded.Upload, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Upload File") }
                    FilledTonalButton(onClick = { creating = true }) { Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("New Folder") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { viewModel.setGrid(!grid) }) { Icon(if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView, if (grid) "List view" else "Grid view") }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.Rounded.Sort, "Sort") }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            listOf("name" to "Name", "type" to "Type", "size" to "Size", "uploaded" to "Date uploaded", "modified" to "Last modified").forEach { (key, label) ->
                                DropdownMenuItem(text = { Text(if (sort == key) "$label  ✓" else label) }, onClick = { viewModel.setSort(key); sortMenu = false })
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text(if (descending) "Descending  (tap for ascending)" else "Ascending  (tap for descending)") }, onClick = { viewModel.toggleDirection(); sortMenu = false })
                        }
                    }
                }
                Breadcrumbs(listing?.path.orEmpty().map { it.id to it.name }, onOpen = viewModel::open)
                tree?.usage?.let { u -> if (u.quotaBytes > 0) Text("Storage: ${sizeLabel(u.usedBytes)} of ${sizeLabel(u.quotaBytes)} used", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                uploads.forEach { UploadRow(it) }
                if (uploads.isNotEmpty() && uploads.none { it.state == UploadState.UPLOADING }) TextButton(onClick = { viewModel.dismissUploads() }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Clear upload list") }

                val folders = listing?.folders.orEmpty()
                val files = listing?.files.orEmpty()
                when {
                    error != null && listing == null -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { viewModel.refresh() }) { Text("Try again") }
                    }
                    !loading && folders.isEmpty() && files.isEmpty() -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Folder, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                        Text("This folder is empty", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                        Text("Tap Upload File to add documents, or New Folder to organise them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    grid -> LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(folders, key = { "d" + it.id }) { f ->
                            ItemCard(
                                icon = Icons.Rounded.Folder, title = f.name, subtitle = "${f.itemCount} item${if (f.itemCount == 1) "" else "s"}", selected = viewModel.isFolderSelected(f.id), grid = true,
                                onClick = { if (selecting) viewModel.toggleFolder(f.id) else viewModel.open(f.id) }, onLongClick = { viewModel.toggleFolder(f.id) },
                                menu = { close -> FolderMenu(close, onOpen = { viewModel.open(f.id) }, onRename = { renamingFolder = f }, onMove = { movingFolder = f }, onDelete = { deletingFolder = f }) },
                            )
                        }
                        items(files, key = { "f" + it.id }) { f ->
                            ItemCard(
                                icon = iconFor(f), title = f.name, subtitle = "${sizeLabel(f.size)} · ${dateLabel(f.createdAt)}", selected = viewModel.isFileSelected(f.id), grid = true,
                                onClick = { if (selecting) viewModel.toggleFile(f.id) else previewOf = f }, onLongClick = { viewModel.toggleFile(f.id) },
                                menu = { close -> FileMenu(close, onPreview = { previewOf = f }, onDownload = { download(f) }, onRename = { renamingFile = f }, onMove = { movingFile = f }, onDetails = { detailsOf = f }, onDelete = { deletingFile = f }) },
                            )
                        }
                    }
                    else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(folders, key = { "d" + it.id }) { f ->
                            ItemCard(
                                icon = Icons.Rounded.Folder, title = f.name, subtitle = "${f.itemCount} item${if (f.itemCount == 1) "" else "s"} · ${dateLabel(f.updatedAt)}", selected = viewModel.isFolderSelected(f.id), grid = false,
                                onClick = { if (selecting) viewModel.toggleFolder(f.id) else viewModel.open(f.id) }, onLongClick = { viewModel.toggleFolder(f.id) },
                                menu = { close -> FolderMenu(close, onOpen = { viewModel.open(f.id) }, onRename = { renamingFolder = f }, onMove = { movingFolder = f }, onDelete = { deletingFolder = f }) },
                            )
                        }
                        items(files, key = { "f" + it.id }) { f ->
                            ItemCard(
                                icon = iconFor(f), title = f.name, subtitle = "${f.ext.uppercase()} · ${sizeLabel(f.size)} · uploaded ${dateLabel(f.createdAt)} · modified ${dateLabel(f.updatedAt)}", selected = viewModel.isFileSelected(f.id), grid = false,
                                onClick = { if (selecting) viewModel.toggleFile(f.id) else previewOf = f }, onLongClick = { viewModel.toggleFile(f.id) },
                                menu = { close -> FileMenu(close, onPreview = { previewOf = f }, onDownload = { download(f) }, onRename = { renamingFile = f }, onMove = { movingFile = f }, onDetails = { detailsOf = f }, onDelete = { deletingFile = f }) },
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- dialogs ----
    if (creating) NameDialog("New folder", "", "Create", onDismiss = { creating = false }, onConfirm = { viewModel.createFolder(it); creating = false })
    renamingFolder?.let { f -> NameDialog("Rename folder", f.name, "Rename", onDismiss = { renamingFolder = null }, onConfirm = { viewModel.renameFolder(f, it); renamingFolder = null }) }
    renamingFile?.let { f -> NameDialog("Rename file", f.name, "Rename", onDismiss = { renamingFile = null }, onConfirm = { viewModel.renameFile(f, it); renamingFile = null }) }
    movingFolder?.let { f -> MoveDialog(tree, current = f.parentId, exclude = f.id, onDismiss = { movingFolder = null }, onPick = { viewModel.moveFolder(f, it); movingFolder = null }) }
    movingFile?.let { f -> MoveDialog(tree, current = f.folderId, exclude = null, onDismiss = { movingFile = null }, onPick = { viewModel.moveFile(f, it); movingFile = null }) }
    if (movingSelection) MoveDialog(tree, current = folderId, exclude = null, onDismiss = { movingSelection = false }, onPick = { viewModel.moveSelection(it); movingSelection = false })
    deletingFile?.let { f -> ConfirmDialog("Delete file?", "\"${f.name}\" will be moved to the recycle bin. You can restore it from there.", "Delete", onDismiss = { deletingFile = null }, onConfirm = { viewModel.deleteFile(f); deletingFile = null }) }
    deletingFolder?.let { f -> ConfirmDialog("Delete folder?", "\"${f.name}\" will be moved to the recycle bin.", "Delete", onDismiss = { deletingFolder = null }, onConfirm = { viewModel.deleteFolder(f); deletingFolder = null }) }
    if (deletingSelection) ConfirmDialog("Delete ${selection.size} item(s)?", "They will be moved to the recycle bin, including everything inside the selected folders.", "Delete", onDismiss = { deletingSelection = false }, onConfirm = { viewModel.deleteSelection(); deletingSelection = false })
    warning?.let { w ->
        ConfirmDialog(
            "This folder is not empty",
            "\"${w.folder.name}\" contains ${w.files} file(s) and ${w.folders} subfolder(s). They will all be moved to the recycle bin with it.",
            "Delete everything", onDismiss = { viewModel.dismissFolderWarning() }, onConfirm = { viewModel.deleteFolder(w.folder, confirmed = true) },
        )
    }
    detailsOf?.let { f -> FileDetailsDialog(f, load = { viewModel.detailsOf(f) }, onDismiss = { detailsOf = null }) }
    previewOf?.let { f ->
        PreviewDialog(
            file = f, load = { viewModel.bytesOf(f) }, onDownload = { download(f) },
            onOpenExternally = { bytes -> scope.launch { runCatching { viewModel.openExternally(context, f, bytes) }.onFailure { toast("No app on this phone can open this file. It was not changed.") } } },
            onDismiss = { previewOf = null },
        )
    }
    trash?.let { items -> TrashDialog(items, onRestore = viewModel::restore, onDeleteForever = viewModel::deleteForever, onEmpty = viewModel::emptyTrash, onDismiss = { viewModel.closeTrash() }) }
}

@Composable
private fun Breadcrumbs(path: List<Pair<String, String>>, onOpen: (String?) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        item { TextButton(onClick = { onOpen(null) }) { Text("File Manager", fontWeight = if (path.isEmpty()) FontWeight.Bold else FontWeight.Normal) } }
        items(path.size) { i ->
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
            TextButton(onClick = { onOpen(path[i].first) }) { Text(path[i].second, fontWeight = if (i == path.lastIndex) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun UploadRow(item: UploadItem) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (item.state) {
            UploadState.UPLOADING -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            UploadState.DONE -> Text("✓", color = MaterialTheme.colorScheme.primary)
            UploadState.FAILED -> Text("✕", color = MaterialTheme.colorScheme.error)
        }
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            if (item.error != null) Text(item.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            else if (item.state == UploadState.UPLOADING) Text("Uploading...", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemCard(
    icon: ImageVector, title: String, subtitle: String, selected: Boolean, grid: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, menu: @Composable (close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        if (grid) {
            Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth()) {
                    Icon(icon, null, Modifier.size(48.dp).align(Alignment.Center), tint = tint)
                    Box(Modifier.align(Alignment.TopEnd)) {
                        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.MoreVert, "More") }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
                    }
                }
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, Modifier.size(36.dp), tint = tint)
                Column(Modifier.weight(1f)) {
                    Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
                }
            }
        }
    }
}

@Composable
private fun FolderMenu(close: () -> Unit, onOpen: () -> Unit, onRename: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
    DropdownMenuItem(text = { Text("Open") }, onClick = { close(); onOpen() })
    DropdownMenuItem(text = { Text("Rename") }, onClick = { close(); onRename() })
    DropdownMenuItem(text = { Text("Move") }, onClick = { close(); onMove() })
    DropdownMenuItem(text = { Text("Delete") }, onClick = { close(); onDelete() })
}

@Composable
private fun FileMenu(close: () -> Unit, onPreview: () -> Unit, onDownload: () -> Unit, onRename: () -> Unit, onMove: () -> Unit, onDetails: () -> Unit, onDelete: () -> Unit) {
    DropdownMenuItem(text = { Text("Open / Preview") }, onClick = { close(); onPreview() })
    DropdownMenuItem(text = { Text("Download") }, onClick = { close(); onDownload() })
    DropdownMenuItem(text = { Text("Rename") }, onClick = { close(); onRename() })
    DropdownMenuItem(text = { Text("Move") }, onClick = { close(); onMove() })
    DropdownMenuItem(text = { Text("View details") }, onClick = { close(); onDetails() })
    DropdownMenuItem(text = { Text("Delete") }, onClick = { close(); onDelete() })
}

@Composable
private fun SearchResults(searching: Boolean, result: List<CoSearchItem>, total: Int, onOpenFolder: (String?) -> Unit, onDownload: (CoSearchItem) -> Unit) {
    if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
    if (!searching && result.isEmpty()) {
        Text("No files or folders match your search.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Text("$total result${if (total == 1) "" else "s"}", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(result, key = { it.kind + it.id }) { item ->
            Card(Modifier.fillMaxWidth().clickable { onOpenFolder(if (item.isFolder) item.id else item.folderId) }) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(if (item.isFolder) Icons.Rounded.Folder else iconFor(item.ext), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(item.folderPath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (item.isFolder) "Folder" else item.ext.uppercase()) + (item.size?.let { " · ${sizeLabel(it)}" } ?: "") + " · ${dateLabel(item.createdAt)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = { onOpenFolder(if (item.isFolder) item.id else item.folderId) }) { Text(if (item.isFolder) "Open" else "Show folder") }
                        if (!item.isFolder) TextButton(onClick = { onDownload(item) }) { Text("Download") }
                    }
                }
            }
        }
    }
}

internal fun iconFor(f: CoFile): ImageVector = iconFor(f.ext)

internal fun iconFor(ext: String): ImageVector = when (ext.lowercase()) {
    "pdf" -> Icons.Rounded.PictureAsPdf
    "xlsx", "xls", "csv", "ods" -> Icons.Rounded.TableChart
    "doc", "docx", "odt", "rtf", "txt" -> Icons.Rounded.Description
    "jpg", "jpeg", "png", "gif", "webp" -> Icons.Rounded.Image
    "ppt", "pptx", "odp" -> Icons.Rounded.Slideshow
    "zip" -> Icons.Rounded.FolderZip
    else -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

internal fun mimeOf(ext: String): String = when (ext.lowercase()) {
    "pdf" -> "application/pdf"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "xls" -> "application/vnd.ms-excel"
    "csv" -> "text/csv"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "doc" -> "application/msword"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "txt" -> "text/plain"
    "zip" -> "application/zip"
    else -> "application/octet-stream"
}

internal fun sizeLabel(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
    else -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824.0)
}

internal fun dateLabel(millis: Long): String = if (millis <= 0) "-" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

