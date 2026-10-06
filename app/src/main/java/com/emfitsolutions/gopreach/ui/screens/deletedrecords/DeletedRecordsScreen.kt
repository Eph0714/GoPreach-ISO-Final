package com.emfitsolutions.gopreach.ui.screens.deletedrecords

import androidx.compose.foundation.layout.Arrangement
import com.emfitsolutions.gopreach.ui.components.RecordFound
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.DeletedRecord
import com.emfitsolutions.gopreach.data.repository.RecycleBinRepository
import com.emfitsolutions.gopreach.data.repository.RestoreResult
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private enum class DateFilter(val label: String, val days: Int?) {
    ALL("Any date", null), TODAY("Today", 1), WEEK("Last 7 days", 7), MONTH("Last 30 days", 30), QUARTER("Last 90 days", 90),
}

private const val ALL = "All"

/** Records within this many days of automatic permanent deletion get the warning treatment. */
private const val WARNING_DAYS = 7L

/**
 * Deleted Records — the recycle bin. Everything deleted anywhere in GoPreach lands here first; from here it can
 * be restored to where it came from, or permanently deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeletedRecordsScreen(
    access: DeletedRecordsAccess,
    onBack: () -> Unit,
    viewModel: DeletedRecordsViewModel = hiltViewModel(),
) {
    val records by remember(access) { viewModel.recordsFor(access) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()
    val toast = rememberActionToast()
    val scope = rememberCoroutineScope()
    val dateFormat = remember { SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()) }

    // Anything past its retention period is removed when this screen opens (when that setting is on).
    LaunchedEffect(access, settings.trashAutoDeleteEnabled, settings.trashRetentionDays) { viewModel.purgeExpired(access) }

    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf(ALL) }
    var moduleFilter by remember { mutableStateOf(ALL) }
    var byFilter by remember { mutableStateOf(ALL) }
    var dateFilter by remember { mutableStateOf(DateFilter.ALL) }
    var newestFirst by remember { mutableStateOf(true) }

    var restoreTarget by remember { mutableStateOf<DeletedRecord?>(null) }
    var deleteTarget by remember { mutableStateOf<DeletedRecord?>(null) }
    var conflictMessage by remember { mutableStateOf<String?>(null) }

    val congregationName = remember(congregations) { congregations.associate { it.id to it.name } }
    val now = System.currentTimeMillis()
    val filtered = records
        .filter { r ->
            (query.isBlank() || listOf(r.label, r.recordType, r.module, r.deletedByName, r.groupName.orEmpty(), congregationName[r.congregationId].orEmpty())
                .any { it.contains(query, ignoreCase = true) }) &&
                (typeFilter == ALL || r.recordType == typeFilter) &&
                (moduleFilter == ALL || r.module == moduleFilter) &&
                (byFilter == ALL || r.deletedByName == byFilter) &&
                (dateFilter.days == null || r.deletedAt >= now - TimeUnit.DAYS.toMillis(dateFilter.days!!.toLong()))
        }
        .let { if (newestFirst) it.sortedByDescending { r -> r.deletedAt } else it.sortedBy { r -> r.deletedAt } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Deleted Records") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Records moved here can be restored or permanently deleted.", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (settings.trashAutoDeleteEnabled) "Automatic permanent deletion is ON — records are permanently deleted ${settings.trashRetentionDays} days after they were deleted."
                        else "Automatic permanent deletion is OFF — records stay here until they are restored or permanently deleted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search") },
                        singleLine = true,
                        visualTransformation = VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FilterDropdown("Record Type", listOf(ALL) + records.map { it.recordType }.distinct().sorted(), typeFilter) { typeFilter = it }
                    FilterDropdown("Module", listOf(ALL) + records.map { it.module }.distinct().sorted(), moduleFilter) { moduleFilter = it }
                    FilterDropdown("Deleted By", listOf(ALL) + records.map { it.deletedByName }.filter { it.isNotBlank() }.distinct().sorted(), byFilter) { byFilter = it }
                    FilterDropdown("Date Deleted", DateFilter.entries.map { it.label }, dateFilter.label) { label -> dateFilter = DateFilter.entries.first { it.label == label } }
                    FilterDropdown("Sort", listOf("Newest deleted first", "Oldest deleted first"), if (newestFirst) "Newest deleted first" else "Oldest deleted first") { newestFirst = it.startsWith("Newest") }
                    RecordFound(filtered.size)
                }
            }
            if (filtered.isEmpty()) {
                item {
                    Text(
                        if (records.isEmpty()) "Nothing has been deleted." else "No deleted records match your search or filters.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(filtered, key = { it.id }) { record ->
                DeletedRecordCard(
                    record = record,
                    congregation = congregationName[record.congregationId],
                    dueAt = RecycleBinRepository.permanentDeleteAt(record, settings),
                    dateFormat = dateFormat,
                    now = now,
                    onRestore = { restoreTarget = record },
                    onDeletePermanently = { deleteTarget = record },
                )
            }
        }
    }

    restoreTarget?.let { record ->
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text("Restore this record?") },
            text = { Text("\"${record.label}\" will go back to ${record.module}, with its original information and relationships.") },
            confirmButton = {
                TextButton(onClick = {
                    restoreTarget = null
                    scope.launch {
                        when (val result = viewModel.restore(record, access)) {
                            RestoreResult.Restored -> toast("\"${record.label}\" was restored.")
                            is RestoreResult.Conflict -> conflictMessage = result.message
                            is RestoreResult.Failed -> toast(result.message)
                        }
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { restoreTarget = null }) { Text("Cancel") } },
        )
    }

    deleteTarget?.let { record ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Permanently") },
            text = { Text("This action will permanently delete this record and cannot be undone. Continue?\n\n\"${record.label}\" (${record.recordType})") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.permanentlyDelete(record, access)
                        deleteTarget = null
                        toast("\"${record.label}\" was permanently deleted.")
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete Permanently") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }

    conflictMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { conflictMessage = null },
            title = { Text("Can't Restore Yet") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { conflictMessage = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun DeletedRecordCard(
    record: DeletedRecord,
    congregation: String?,
    dueAt: Long?,
    dateFormat: SimpleDateFormat,
    now: Long,
    onRestore: () -> Unit,
    onDeletePermanently: () -> Unit,
) {
    // Whole days left, rounded up so "less than a day" reads as 1, never 0 until it is actually due.
    val daysLeft = dueAt?.let { TimeUnit.MILLISECONDS.toDays((it - now).coerceAtLeast(0) + TimeUnit.DAYS.toMillis(1) - 1) }
    val warning = daysLeft != null && daysLeft <= WARNING_DAYS
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (warning) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)) else CardDefaults.cardColors(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(record.label.ifBlank { "(no name)" }, style = MaterialTheme.typography.titleMedium)
            Detail("Record Type", record.recordType)
            Detail("Module", record.module)
            Detail("Congregation", congregation ?: "—")
            if (!record.groupName.isNullOrBlank()) Detail("Field Service Group", record.groupName)
            Detail("Deleted By", record.deletedByName.ifBlank { "—" })
            Detail("Deleted on", dateFormat.format(Date(record.deletedAt)))
            if (dueAt != null) {
                Detail("Permanent deletion", dateFormat.format(Date(dueAt)))
                if (warning) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text(
                            if (daysLeft == 0L) "Permanent deletion is due now" else "Permanent deletion in $daysLeft ${if (daysLeft == 1L) "day" else "days"}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            Detail("Status", "Deleted")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = onRestore) { Text("Restore") }
                OutlinedButton(onClick = onDeletePermanently, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Delete Permanently")
                }
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$label:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.orEmpty(), style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterDropdown(label: String, options: List<String>, selected: String, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelected(option); expanded = false })
            }
        }
    }
}
