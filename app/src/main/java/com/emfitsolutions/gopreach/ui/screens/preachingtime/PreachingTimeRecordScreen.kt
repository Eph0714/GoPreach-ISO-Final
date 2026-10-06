package com.emfitsolutions.gopreach.ui.screens.preachingtime

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
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.PreachingTimeRecord
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.ui.components.DateRangeFilterBar
import com.emfitsolutions.gopreach.ui.components.DateTimeField
import com.emfitsolutions.gopreach.ui.components.DeleteChoiceDialog
import com.emfitsolutions.gopreach.ui.components.EditSectionHeader
import com.emfitsolutions.gopreach.ui.components.FormDialog
import com.emfitsolutions.gopreach.ui.components.ReadOnlyField
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.components.requiredFieldsMessage
import com.emfitsolutions.gopreach.ui.components.formatRecordTimestamp

/** "Preaching Time Record Module" spec §12-§15 — Pioneer-only Add/Edit/
 * Delete/View/Search/date-filter CRUD, mirroring this app's established
 * Manage-screen pattern (Bible Study Record, Interested People, ...). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreachingTimeRecordScreen(
    publisherPersonId: String,
    congregationId: String?,
    canPermanentlyDelete: Boolean,
    onBack: () -> Unit,
    viewModel: PreachingTimeRecordViewModel = koinViewModel(),
) {
    val recordsFlow = remember(publisherPersonId) { viewModel.recordsFor(publisherPersonId) }
    val allRecords by recordsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val dateRange by viewModel.dateRange.collectAsStateWithLifecycle()

    var showInactive by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingEdit by remember { mutableStateOf<PreachingTimeRecord?>(null) }
    var pendingDelete by remember { mutableStateOf<PreachingTimeRecord?>(null) }
    val showToast = rememberActionToast()

    val records = remember(allRecords, dateRange, showInactive, searchText) {
        allRecords
            .filter { showInactive || it.status == RecordStatus.ACTIVE }
            .filter { dateRange.contains(it.date) }
            .filter { searchText.isBlank() || it.remarks?.contains(searchText, ignoreCase = true) == true }
            .sortedByDescending { it.date }
    }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val totalHours = remember(records) { records.sumOf { it.hoursConsumed } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Preaching Time Record") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("ADD RECORD") },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            DateRangeFilterBar(range = dateRange, onRangeChange = viewModel::setDateRange, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                label = { Text("Search remarks") },
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = showInactive, onCheckedChange = { showInactive = it })
                Text("Show Inactive")
            }
            Text(
                "Total: ${"%.2f".format(totalHours)} hours",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (records.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    RecordFound(0)
                    Text("No preaching time records for this period. Tap Add Record to log one.", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { RecordFound(records.size) }
                    items(records, key = { it.id }) { record ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(dateFormat.format(Date(record.date)), style = MaterialTheme.typography.titleMedium)
                                    Text("${"%.2f".format(record.hoursConsumed)} hours", style = MaterialTheme.typography.bodyMedium)
                                    if (record.remarks != null) {
                                        Text(record.remarks!!, style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (record.status == RecordStatus.INACTIVE) {
                                        Text("Inactive", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                                Row {
                                    IconButton(onClick = { pendingEdit = record }) {
                                        Icon(Icons.Rounded.Edit, contentDescription = "Edit")
                                    }
                                    if (record.status == RecordStatus.ACTIVE) {
                                        IconButton(onClick = { pendingDelete = record }) {
                                            Icon(Icons.Rounded.Delete, contentDescription = "Delete")
                                        }
                                    } else {
                                        IconButton(onClick = { viewModel.setStatus(record, RecordStatus.ACTIVE); showToast("Record reactivated.") }) {
                                            Icon(Icons.Rounded.RestoreFromTrash, contentDescription = "Reactivate")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        PreachingTimeRecordDialog(
            existingRecord = null,
            publisherPersonId = publisherPersonId,
            congregationId = congregationId.orEmpty(),
            onSave = { viewModel.save(it); showToast("Record added.") },
            onDismiss = { showCreateDialog = false },
        )
    }

    val toEdit = pendingEdit
    if (toEdit != null) {
        PreachingTimeRecordDialog(
            existingRecord = toEdit,
            publisherPersonId = publisherPersonId,
            congregationId = congregationId.orEmpty(),
            onSave = { viewModel.save(it); showToast("Record saved.") },
            onDismiss = { pendingEdit = null },
        )
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        DeleteChoiceDialog(
            recordLabel = dateFormat.format(Date(toDelete.date)),
            canPermanentlyDelete = canPermanentlyDelete,
            onDismiss = { pendingDelete = null },
            onMoveToInactive = { viewModel.setStatus(toDelete, RecordStatus.INACTIVE) },
            onDeletePermanently = { viewModel.permanentlyDelete(toDelete.id) },
        )
    }
}

/** Add/Edit — shows the complete record, not just Hour Consumed (spec §14:
 * "when editing a record, show the complete record"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreachingTimeRecordDialog(
    existingRecord: PreachingTimeRecord?,
    publisherPersonId: String,
    congregationId: String,
    onSave: (PreachingTimeRecord) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialDate = remember { existingRecord?.date?.takeIf { it != 0L } ?: System.currentTimeMillis() }
    var date by remember { mutableStateOf(initialDate) }
    var hoursText by remember { mutableStateOf(existingRecord?.hoursConsumed?.toString().orEmpty()) }
    var remarks by remember { mutableStateOf(existingRecord?.remarks.orEmpty()) }

    val hours = hoursText.toDoubleOrNull()
    val canSave = hours != null && hours > 0.0
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val message = requiredFieldsMessage("Hour Consumed" to canSave)
        if (message != null) {
            errorMessage = message
            return
        }
        val now = System.currentTimeMillis()
        val base = existingRecord ?: PreachingTimeRecord(
            publisherPersonId = publisherPersonId,
            congregationId = congregationId,
            createdByPersonId = publisherPersonId,
            createdAt = now,
        )
        onSave(
            base.copy(
                date = date,
                hoursConsumed = hours!!,
                remarks = remarks.trim().ifBlank { null },
                lastEditedByPersonId = if (existingRecord != null) publisherPersonId else base.lastEditedByPersonId,
                lastEditedAt = if (existingRecord != null) now else base.lastEditedAt,
            ),
        )
        onDismiss()
    }

    FormDialog(
        onDismissRequest = onDismiss,
        title = if (existingRecord == null) "Add Preaching Time Record" else "Edit Preaching Time Record",
        onConfirm = ::submit,
        confirmLabel = if (existingRecord == null) "Add" else "Save",
        errorMessage = errorMessage,
        maxContentHeight = 500.dp,
        hasUnsavedChanges = date != initialDate || hoursText != existingRecord?.hoursConsumed?.toString().orEmpty() ||
            remarks != existingRecord?.remarks.orEmpty(),
    ) {
                DateTimeField(label = "Date", valueMillis = date, onValueChange = { date = it })
                OutlinedTextField(
                    value = hoursText,
                    onValueChange = { hoursText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Hour Consumed") },
                    placeholder = { Text("e.g. 2.5") },
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = remarks,
                    onValueChange = { remarks = it },
                    label = { Text("Remarks (optional)") },
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (existingRecord != null) {
                    EditSectionHeader("System Information")
                    ReadOnlyField("Created By", existingRecord.createdByPersonId.ifBlank { "—" })
                    ReadOnlyField("Date Created", formatRecordTimestamp(existingRecord.createdAt))
                    if (existingRecord.lastEditedAt != null) {
                        ReadOnlyField("Updated By", existingRecord.lastEditedByPersonId ?: "—")
                        ReadOnlyField("Date Updated", formatRecordTimestamp(existingRecord.lastEditedAt!!))
                    }
                }
    }
}
