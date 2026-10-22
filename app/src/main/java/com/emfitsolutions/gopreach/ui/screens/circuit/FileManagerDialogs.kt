package com.emfitsolutions.gopreach.ui.screens.circuit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.emfitsolutions.gopreach.data.repository.CoFile
import com.emfitsolutions.gopreach.data.repository.CoTrashItem
import com.emfitsolutions.gopreach.data.repository.CoTree
import com.emfitsolutions.gopreach.ui.components.AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A one-line name entry (new folder, rename). The server also validates the name; this only blocks an empty one. */
@Composable
internal fun NameDialog(title: String, initial: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Text("A name cannot contain / \\ : * ? \" < > |", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun ConfirmDialog(title: String, message: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Picks a destination folder from the whole folder tree (the top level is "File Manager"). A folder cannot be moved into itself or its own subfolders. */
@Composable
internal fun MoveDialog(tree: CoTree?, current: String?, exclude: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val folders = tree?.folders.orEmpty()
    val blocked = remember(folders, exclude) {
        buildSet {
            if (exclude != null) {
                add(exclude)
                var grew = true
                while (grew) { grew = false; folders.forEach { if (it.id !in this && it.parentId in this) { add(it.id); grew = true } } }
            }
        }
    }
    fun depth(id: String?): Int { var d = 0; var cur = folders.firstOrNull { it.id == id }; while (cur?.parentId != null && d < 20) { d++; cur = folders.firstOrNull { it.id == cur?.parentId } }; return d }
    // The tree in reading order: each folder directly after its parent.
    val ordered = remember(folders) {
        val out = mutableListOf<com.emfitsolutions.gopreach.data.repository.CoTreeFolder>()
        fun walk(parent: String?) { folders.filter { it.parentId == parent }.sortedBy { it.name.lowercase() }.forEach { out += it; walk(it.id) } }
        walk(null)
        out
    }
    var chosen by remember { mutableStateOf<String?>(current) }
    var picked by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item { MoveRow("File Manager (top level)", 0, chosen == null && picked || (!picked && current == null), enabled = true) { chosen = null; picked = true } }
                items(ordered, key = { it.id }) { f ->
                    MoveRow(f.name, depth(f.id) + 1, selected = picked && chosen == f.id || (!picked && current == f.id), enabled = f.id !in blocked) { chosen = f.id; picked = true }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(if (picked) chosen else current) }, enabled = picked) { Text("Move here") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MoveRow(name: String, depth: Int, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(start = (depth * 16).dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Icon(Icons.Rounded.Folder, null, Modifier.size(20.dp), tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        Text(name, Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
    }
}

/** Filename, type, size, dates, folder and owner (the server's own view of the file). */
@Composable
internal fun FileDetailsDialog(file: CoFile, load: suspend () -> CoFile, onDismiss: () -> Unit) {
    var details by remember { mutableStateOf<CoFile?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(file.id) { runCatching { load() }.onSuccess { details = it }.onFailure { failed = it.message } }
    val d = details ?: file
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("File details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailLine("Name", d.name)
                DetailLine("Type", d.ext.uppercase().ifBlank { d.mime })
                DetailLine("Size", sizeLabel(d.size))
                DetailLine("Uploaded", dateLabel(d.createdAt))
                DetailLine("Last modified", dateLabel(d.updatedAt))
                DetailLine("Folder", d.folderPath.ifBlank { "File Manager" })
                DetailLine("Owner", d.owner.ifBlank { "You" })
                failed?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column { Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, fontWeight = FontWeight.SemiBold) }
}

/**
 * Opens a file for reading without changing it: images, PDF pages and text are drawn inside the app; Word, Excel, PowerPoint and anything else are offered
 * to the phone's own apps (or as a download), because the app never renders documents it cannot show safely.
 */
@Composable
internal fun PreviewDialog(file: CoFile, load: suspend () -> ByteArray, onDownload: () -> Unit, onOpenExternally: (ByteArray) -> Unit, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(file.id) { runCatching { load() }.onSuccess { bytes = it }.onFailure { failed = it.message ?: "The file could not be opened." } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(file.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${file.ext.uppercase()} · ${sizeLabel(file.size)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onDownload) { Text("Download") }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") }
                }
                HorizontalDivider()
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val data = bytes
                    when {
                        failed != null -> Text(failed.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
                        data == null -> CircularProgressIndicator()
                        file.isImage -> ImagePreview(data)
                        file.isPdf -> PdfPreview(data, context.cacheDir)
                        file.isText -> TextPreview(data)
                        else -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("This kind of file can't be shown inside the app.", style = MaterialTheme.typography.titleSmall)
                            Text("Open it with another app on your phone, or download it. Opening never changes the file.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                            TextButton(onClick = { onOpenExternally(data) }) { Text("Open with another app") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImagePreview(data: ByteArray) {
    val bitmap by produceDecoded(data)
    val b = bitmap
    if (b == null) Text("This image could not be shown.", modifier = Modifier.padding(24.dp)) else Image(b.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
}

@Composable
private fun produceDecoded(data: ByteArray) = androidx.compose.runtime.produceState<Bitmap?>(null, data) {
    value = withContext(Dispatchers.Default) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeByteArray(data, 0, data.size, it) }
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        runCatching { BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull()
    }
}

@Composable
private fun TextPreview(data: ByteArray) {
    // Only the first 200 KB are shown; a larger text file is meant to be downloaded.
    val text = remember(data) { data.copyOf(minOf(data.size, 200 * 1024)).decodeToString() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        if (data.size > 200 * 1024) Text("... (only the first 200 KB are shown)", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun PdfPreview(data: ByteArray, cacheDir: File) {
    val pages = androidx.compose.runtime.produceState<List<Bitmap>?>(null, data) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val temp = File.createTempFile("preview", ".pdf", cacheDir).also { it.writeBytes(data) }
                try {
                    ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                        PdfRenderer(fd).use { renderer ->
                            (0 until minOf(renderer.pageCount, 40)).map { i ->
                                renderer.openPage(i).use { page ->
                                    val width = 1080
                                    val height = (width * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.WHITE); page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                                }
                            }
                        }
                    }
                } finally { temp.delete() }
            }.getOrNull()
        }
    }.value
    when (val p = pages) {
        null -> CircularProgressIndicator()
        else -> if (p.isEmpty()) Text("This PDF could not be shown. Download it instead.", modifier = Modifier.padding(24.dp)) else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(p.size) { i -> Image(p[i].asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
        }
    }
}

/** The recycle bin: restore, delete for good, or empty it. Nothing here is permanent until the person confirms it. */
@Composable
internal fun TrashDialog(items: List<CoTrashItem>, onRestore: (CoTrashItem) -> Unit, onDeleteForever: (CoTrashItem) -> Unit, onEmpty: () -> Unit, onDismiss: () -> Unit) {
    var forever by remember { mutableStateOf<CoTrashItem?>(null) }
    var emptying by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recycle bin") },
        text = {
            if (items.isEmpty()) Text("The recycle bin is empty.") else LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(items, key = { it.kind + it.id }) { item ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (item.isFolder) Icons.Rounded.Folder else iconFor(item.ext), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "Deleted ${dateLabel(item.deletedAt)}" + (item.size?.let { " · ${sizeLabel(it)}" } ?: "") + (if (item.isFolder && item.contains > 0) " · ${item.contains} file(s) inside" else ""),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row { TextButton(onClick = { onRestore(item) }) { Text("Restore") }; TextButton(onClick = { forever = item }) { Text("Delete forever", color = MaterialTheme.colorScheme.error) } }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { if (items.isNotEmpty()) TextButton(onClick = { emptying = true }) { Text("Empty recycle bin", color = MaterialTheme.colorScheme.error) } },
    )
    forever?.let { i -> ConfirmDialog("Delete forever?", "\"${i.name}\" and its stored file will be removed for good. This can't be undone.", "Delete forever", onDismiss = { forever = null }, onConfirm = { onDeleteForever(i); forever = null }) }
    if (emptying) ConfirmDialog("Empty the recycle bin?", "Everything in it will be removed for good. This can't be undone.", "Empty", onDismiss = { emptying = false }, onConfirm = { onEmpty(); emptying = false })
}
