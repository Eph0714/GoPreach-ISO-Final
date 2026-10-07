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
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.emfitsolutions.gopreach.data.model.AccountStatus
import com.emfitsolutions.gopreach.ui.components.RecordFound
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.SimpleDropdown
import org.koin.compose.viewmodel.koinViewModel

/**
 * Administration → Circuit Overseer Accounts. Super-Admin only (nav graph). Columns: Last Name, First Name,
 * Username, Circuit Code, Assigned Congregations, Status, Actions (View / Edit / Delete). No password is ever
 * shown here — passwords live only in the sign-in provider, which stores a salted hash.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircuitOverseerAccountsScreen(
    currentPersonId: String,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onView: (String) -> Unit,
    onEdit: (String) -> Unit,
    viewModel: CircuitOverseerAccountsViewModel = koinViewModel(),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val showToast = rememberActionToast()
    var query by remember { mutableStateOf("") }
    var codeFilter by remember { mutableStateOf<String?>(null) }
    var statusFilter by remember { mutableStateOf<AccountStatus?>(null) }
    var deleting by remember { mutableStateOf<CircuitOverseerRow?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    val codes = rows.mapNotNull { it.circuitCode }.distinct().sorted()
    val visible = rows.filter { row ->
        row.matches(query) &&
            (codeFilter == null || row.circuitCode == codeFilter) &&
            (statusFilter == null || row.person.accountStatus == statusFilter)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Circuit Overseer Accounts") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Rounded.Add, contentDescription = "New Circuit Overseer") }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search name, username, circuit or congregation") },
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SimpleDropdown(
                        label = "Circuit Code",
                        selectedLabel = codeFilter ?: "All",
                        options = listOf("" to "All") + codes.map { it to it },
                        onSelected = { codeFilter = it.ifEmpty { null } },
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    SimpleDropdown(
                        label = "Status",
                        selectedLabel = statusFilter?.name ?: "All",
                        options = listOf("" to "All") + AccountStatus.entries.map { it.name to it.name },
                        onSelected = { statusFilter = if (it.isEmpty()) null else AccountStatus.valueOf(it) },
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { RecordFound(visible.size) }
                if (rows.isEmpty()) {
                    item { Text("No Circuit Overseer accounts yet. Tap + to add one.", style = MaterialTheme.typography.bodyMedium) }
                }
                items(visible, key = { it.person.id }) { row ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("${row.person.lastName}, ${row.person.firstName}", style = MaterialTheme.typography.titleMedium)
                                Text("@${row.person.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Circuit: ${row.circuitCode ?: "—"}", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    if (row.congregations.isEmpty()) "Congregations: none assigned"
                                    else "Congregations (${row.congregations.size}): ${row.congregations.joinToString(", ") { it.name }}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                AssistChip(onClick = {}, label = { Text(if (row.person.isTemporaryCredential && row.person.accountStatus == AccountStatus.ACTIVE) "TEMPORARY ACCOUNT" else row.person.accountStatus.name) })
                            }
                            Row {
                                IconButton(onClick = { onView(row.person.id) }) { Icon(Icons.Rounded.Visibility, contentDescription = "View") }
                                IconButton(onClick = { onEdit(row.person.id) }) { Icon(Icons.Rounded.Edit, contentDescription = "Edit") }
                                IconButton(onClick = { deleteError = null; deleting = row }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete") }
                            }
                        }
                    }
                }
            }
        }
    }

    deleting?.let { row ->
        val blocker = viewModel.deleteBlocker(row)
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(if (blocker != null) "Can't delete ${row.person.fullName}" else "Delete ${row.person.fullName}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(blocker ?: "This permanently deletes the Circuit Overseer account and frees its Circuit Code ${row.circuitCode ?: ""}. This cannot be undone.")
                    deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                if (blocker == null) {
                    TextButton(onClick = {
                        viewModel.delete(row, currentPersonId) { problem ->
                            if (problem == null) { showToast("${row.person.fullName} deleted."); deleting = null } else deleteError = problem
                        }
                    }) { Text("Delete") }
                } else {
                    TextButton(onClick = { deleting = null; onEdit(row.person.id) }) { Text("Edit assignments") }
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(if (blocker != null) "Close" else "Cancel") } },
        )
    }
}

