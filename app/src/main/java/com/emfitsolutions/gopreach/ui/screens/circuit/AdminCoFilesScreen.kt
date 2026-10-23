package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.repository.CoAccount
import com.emfitsolutions.gopreach.data.repository.CoFile
import com.emfitsolutions.gopreach.ui.components.Scaffold
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/** Super Admin only: every Circuit Overseer on one list; open one to browse their files (read-only: preview and download, no change). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminCoFilesScreen(onBack: () -> Unit, viewModel: AdminCoFilesViewModel = koinViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberActionToast()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val listing by viewModel.listing.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    var coQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("ALL") }
    var previewOf by remember { mutableStateOf<CoFile?>(null) }

    LaunchedEffect(Unit) { viewModel.messages.collect { toast(it) } }

    fun download(file: CoFile) {
        scope.launch {
            runCatching { viewModel.saveToDownloads(file, viewModel.bytesOf(file)) }
                .onSuccess { toast(it) }
                .onFailure { toast(it.message ?: "The download failed.") }
        }
    }

    val goBack = {
        when {
            query.isNotBlank() -> viewModel.clearSearch()
            selected != null && listing?.path?.isNotEmpty() == true -> viewModel.open(listing?.path?.dropLast(1)?.lastOrNull()?.id)
            selected != null -> viewModel.pick(null)
            else -> onBack()
        }
    }
    androidx.activity.compose.BackHandler(enabled = selected != null) { goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selected?.let { "${it.name} · Circuit ${it.circuitCode}" } ?: "CO File Management", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = goBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            val co = selected
            if (co == null) {
                OutlinedTextField(
                    value = coQuery, onValueChange = { coQuery = it }, singleLine = true,
                    placeholder = { Text("Search by name or circuit code") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ALL" to "All", "ACTIVE" to "Active", "INACTIVE" to "Inactive").forEach { (key, label) ->
                        FilterChip(selected = statusFilter == key, onClick = { statusFilter = key }, label = { Text(label) })
                    }
                }
                val q = coQuery.trim().lowercase()
                val rows = accounts.orEmpty().filter { a ->
                    (statusFilter == "ALL" || a.status.equals(statusFilter, true)) &&
                        (q.isEmpty() || a.name.lowercase().contains(q) || a.circuitCode.lowercase().contains(q) || a.username.lowercase().contains(q))
                }
                when {
                    error != null && accounts == null -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { viewModel.loadAccounts() }) { Text("Try again") }
                    }
                    !loading && rows.isEmpty() -> Text("No Circuit Overseer matches.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(rows, key = { it.id }) { a -> CoRow(a, onClick = { viewModel.pick(a) }) }
                    }
                }
            } else {
                OutlinedTextField(
                    value = query, onValueChange = viewModel::onQuery, singleLine = true,
                    placeholder = { Text("Search ${co.name}'s files and folders...") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { viewModel.clearSearch() }) { Icon(Icons.Rounded.Close, "Clear search") } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                val path = listing?.path.orEmpty()
                Text(
                    "File Manager" + path.joinToString("") { " / ${it.name}" }, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (query.isNotBlank()) {
                    val items = search?.items.orEmpty()
                    if (items.isEmpty()) Text("No files or folders match your search.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(items, key = { it.kind + it.id }) { item ->
                            ItemRow(
                                icon = if (item.isFolder) Icons.Rounded.Folder else iconFor(item.ext), title = item.name, subtitle = item.folderPath,
                                onClick = {
                                    if (item.isFolder) viewModel.open(item.id)
                                    else previewOf = CoFile(id = item.id, name = item.name, ext = item.ext, mime = mimeOf(item.ext))
                                },
                            )
                        }
                    }
                } else {
                    val folders = listing?.folders.orEmpty()
                    val files = listing?.files.orEmpty()
                    when {
                        error != null && listing == null -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { viewModel.refresh() }) { Text("Try again") }
                        }
                        !loading && folders.isEmpty() && files.isEmpty() -> Text("${co.name} has no files here.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(folders, key = { "d" + it.id }) { f ->
                                ItemRow(Icons.Rounded.Folder, f.name, "${f.itemCount} item${if (f.itemCount == 1) "" else "s"} · ${dateLabel(f.updatedAt)}", onClick = { viewModel.open(f.id) })
                            }
                            items(files, key = { "f" + it.id }) { f ->
                                ItemRow(iconFor(f), f.name, "${f.ext.uppercase()} · ${sizeLabel(f.size)} · uploaded ${dateLabel(f.createdAt)}", onClick = { previewOf = f })
                            }
                        }
                    }
                }
            }
        }
    }

    previewOf?.let { f ->
        PreviewDialog(
            file = f, load = { viewModel.bytesOf(f) }, onDownload = { download(f) },
            onOpenExternally = { bytes -> scope.launch { runCatching { viewModel.openExternally(context, f, bytes) }.onFailure { toast("No app on this phone can open this file. It was not changed.") } } },
            onDismiss = { previewOf = null },
        )
    }
}

@Composable
private fun CoRow(a: CoAccount, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Person, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(a.name.ifBlank { a.username }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Circuit ${a.circuitCode} · @${a.username} · ${a.status.lowercase().replaceFirstChar { it.uppercase() }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${a.fileCount} file${if (a.fileCount == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ItemRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
