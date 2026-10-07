package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.ui.components.FormDialog
import com.emfitsolutions.gopreach.ui.components.RecordFound
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import org.koin.compose.viewmodel.koinViewModel

/**
 * Administration → Circuit Codes. Super-Admin creates, edits, activates/deactivates and deletes codes; the screen
 * is only reachable for that role (nav graph), and every write is a server transaction that re-checks the rules.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircuitCodesScreen(
    currentPersonId: String,
    onBack: () -> Unit,
    viewModel: CircuitCodesViewModel = koinViewModel(),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val showToast = rememberActionToast()
    var query by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<RecordStatus?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CircuitCodeRow?>(null) }
    var deleting by remember { mutableStateOf<CircuitCodeRow?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    val visible = rows.filter { row ->
        (statusFilter == null || row.code.status == statusFilter) &&
            (query.isBlank() || row.code.code.contains(query.trim(), ignoreCase = true) ||
                row.code.description.contains(query.trim(), ignoreCase = true) ||
                (row.overseerName ?: "").contains(query.trim(), ignoreCase = true))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Circuit Codes") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Rounded.Add, contentDescription = "New Circuit Code") }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search code, description or overseer") },
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = statusFilter == null, onClick = { statusFilter = null }, label = { Text("All") })
                FilterChip(selected = statusFilter == RecordStatus.ACTIVE, onClick = { statusFilter = RecordStatus.ACTIVE }, label = { Text("Active") })
                FilterChip(selected = statusFilter == RecordStatus.INACTIVE, onClick = { statusFilter = RecordStatus.INACTIVE }, label = { Text("Inactive") })
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { RecordFound(visible.size) }
                if (rows.isEmpty()) {
                    item { Text("No Circuit Codes yet. Tap + to add one, e.g. NT01.", style = MaterialTheme.typography.bodyMedium) }
                }
                items(visible, key = { it.code.id }) { row ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(row.code.code, style = MaterialTheme.typography.titleMedium)
                                if (row.code.description.isNotBlank()) Text(row.code.description, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    row.overseerName?.let { "Circuit Overseer: $it" } ?: "Not assigned to a Circuit Overseer",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (row.congregationCount > 0) {
                                    Text("${row.congregationCount} congregation(s)", style = MaterialTheme.typography.bodySmall)
                                }
                                AssistChip(onClick = {}, label = { Text(if (row.code.status == RecordStatus.ACTIVE) "Active" else "Inactive") })
                            }
                            Row {
                                IconButton(onClick = { editing = row }) { Icon(Icons.Rounded.Edit, contentDescription = "Edit") }
                                IconButton(onClick = { deleteError = null; deleting = row }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        CodeDialog(
            title = "New Circuit Code",
            initialCode = "",
            initialDescription = "",
            initialStatus = RecordStatus.ACTIVE,
            isNew = true,
            onDismiss = { showAdd = false },
            onSave = { code, description, _, onProblem ->
                viewModel.create(code, description, currentPersonId) { problem ->
                    if (problem == null) { showToast("Circuit Code ${code.trim().uppercase()} created."); showAdd = false } else onProblem(problem)
                }
            },
        )
    }
    editing?.let { row ->
        CodeDialog(
            title = "Edit Circuit Code",
            initialCode = row.code.code,
            initialDescription = row.code.description,
            initialStatus = row.code.status,
            isNew = false,
            onDismiss = { editing = null },
            onSave = { _, description, status, onProblem ->
                viewModel.update(row, description, status, currentPersonId) { problem ->
                    if (problem == null) { showToast("Circuit Code ${row.code.code} saved."); editing = null } else onProblem(problem)
                }
            },
        )
    }
    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${row.code.code}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This permanently removes the Circuit Code. A code that is assigned to a Circuit Overseer or carried by congregations cannot be deleted — remove those assignments first.")
                    deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(row, currentPersonId) { problem ->
                        if (problem == null) { showToast("Circuit Code ${row.code.code} deleted."); deleting = null } else deleteError = problem
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CodeDialog(
    title: String,
    initialCode: String,
    initialDescription: String,
    initialStatus: RecordStatus,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (code: String, description: String, status: RecordStatus, onProblem: (String) -> Unit) -> Unit,
) {
    var code by remember { mutableStateOf(initialCode) }
    var description by remember { mutableStateOf(initialDescription) }
    var status by remember { mutableStateOf(initialStatus) }
    var error by remember { mutableStateOf<String?>(null) }

    FormDialog(
        onDismissRequest = onDismiss,
        title = title,
        onConfirm = { error = null; onSave(code, description, status) { error = it } },
        errorMessage = error,
        hasUnsavedChanges = code != initialCode || description != initialDescription || status != initialStatus,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = code,
                onValueChange = { if (isNew) code = it.uppercase() },
                label = { Text("Circuit Code *") },
                placeholder = { Text("NT01") },
                singleLine = true,
                enabled = isNew,
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description / Name (optional)") },
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!isNew) {
                Text("Status", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = status == RecordStatus.ACTIVE, onClick = { status = RecordStatus.ACTIVE }, label = { Text("Active") })
                    FilterChip(selected = status == RecordStatus.INACTIVE, onClick = { status = RecordStatus.INACTIVE }, label = { Text("Inactive") })
                }
                Text(
                    "An inactive code is hidden from the Circuit Overseer assignment list.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
