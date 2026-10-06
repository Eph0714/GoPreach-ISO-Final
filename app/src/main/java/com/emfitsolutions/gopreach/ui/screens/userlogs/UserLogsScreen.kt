package com.emfitsolutions.gopreach.ui.screens.userlogs

import com.emfitsolutions.gopreach.platform.SimpleDateFormat
import com.emfitsolutions.gopreach.platform.Locale
import com.emfitsolutions.gopreach.platform.Date
import androidx.compose.foundation.layout.Arrangement
import com.emfitsolutions.gopreach.ui.components.RecordFound
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.ui.components.CongregationFilterDropdown
import com.emfitsolutions.gopreach.ui.components.SelectCongregationPrompt
import com.emfitsolutions.gopreach.ui.components.rememberCongregationContext
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import androidx.compose.ui.window.DialogProperties

/**
 * Spec §3 — "user logs": Super-Admin sees everything and can delete entries;
 * Admin/Coordinator Elder see only their own congregation's, view/export only.
 * "Export" here means the same JSON-file pattern as Backup & Restore rather than
 * a separate mechanism — left for a follow-up pass since it's a straightforward
 * repeat of [com.emfitsolutions.gopreach.ui.screens.backup.BackupRestoreScreen]'s
 * file-picker code.
 *
 * "Select all user log AND DELETE IT PERMANENTLY" — [canDelete] (Super-Admin
 * only) also unlocks a checkbox per row, a "select all" toggle in the top
 * bar, and a bulk "delete selected" action next to it, gated behind an
 * are-you-sure confirmation the single-entry delete doesn't bother with
 * today since this one can wipe the entire log in one tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserLogsScreen(
    visibleCongregationId: String?,
    canDelete: Boolean,
    onBack: () -> Unit,
    viewModel: UserLogsViewModel = koinViewModel(),
) {
    // "Add a filter for Congregation" — only meaningful when this screen has
    // no fixed scope already (Super-Admin); an Admin/Coordinator Elder is
    // already scoped to their own congregation upstream via
    // [visibleCongregationId], so this stays unused for them.
    var congregationFilter by rememberCongregationContext("user_logs")
    val effectiveCongregationId = visibleCongregationId ?: congregationFilter
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()
    val needsCongregation = visibleCongregationId == null && congregationFilter == null
    val rowsFlow = remember(effectiveCongregationId, needsCongregation) { if (needsCongregation) kotlinx.coroutines.flow.flowOf(emptyList()) else viewModel.rowsFor(effectiveCongregationId) }
    val rows by rowsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()) }
    val showToast = rememberActionToast()

    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }
    // Selection can only ever reference rows still on screen — a row that
    // syncs away (deleted elsewhere, or scrolled out of a scoping change)
    // drops out of both places together rather than leaving a stale
    // "N selected" count the visible list no longer backs up.
    val visibleIds = remember(rows) { rows.map { it.entry.id }.toSet() }
    selectedIds = selectedIds.intersect(visibleIds)
    val allSelected = rows.isNotEmpty() && selectedIds.size == rows.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (selectedIds.isNotEmpty()) "${selectedIds.size} selected" else "User Logs")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (canDelete && rows.isNotEmpty()) {
                        Checkbox(
                            checked = allSelected,
                            onCheckedChange = { checked ->
                                selectedIds = if (checked) visibleIds else emptySet()
                            },
                        )
                        Text("Select All", style = MaterialTheme.typography.bodySmall)
                        if (selectedIds.isNotEmpty()) {
                            IconButton(onClick = { showBulkDeleteConfirm = true }) {
                                Icon(Icons.Rounded.DeleteSweep, contentDescription = "Delete selected")
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        if (visibleCongregationId == null) {
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
                Text("No activity recorded yet.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { RecordFound(rows.size) }
                items(rows, key = { it.entry.id }) { row ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (canDelete) {
                                    Checkbox(
                                        checked = row.entry.id in selectedIds,
                                        onCheckedChange = { checked ->
                                            selectedIds = if (checked) selectedIds + row.entry.id else selectedIds - row.entry.id
                                        },
                                    )
                                }
                                Column {
                                    Text(row.entry.action.replace('_', ' '), style = MaterialTheme.typography.titleSmall)
                                    Text(row.actorName, style = MaterialTheme.typography.bodySmall)
                                    Text(dateFormat.format(Date(row.entry.timestamp)), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (canDelete) {
                                IconButton(onClick = { viewModel.delete(row.entry.id) }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = "Delete entry")
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }

    if (showBulkDeleteConfirm) {
        val count = selectedIds.size
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { showBulkDeleteConfirm = false },
            title = { Text("Delete $count Log ${if (count == 1) "Entry" else "Entries"}?") },
            text = { Text("This will permanently delete $count selected log ${if (count == 1) "entry" else "entries"}. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAll(selectedIds)
                        showToast("$count log ${if (count == 1) "entry" else "entries"} deleted.")
                        selectedIds = emptySet()
                        showBulkDeleteConfirm = false
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showBulkDeleteConfirm = false }) { Text("Cancel") } },
        )
    }
}
