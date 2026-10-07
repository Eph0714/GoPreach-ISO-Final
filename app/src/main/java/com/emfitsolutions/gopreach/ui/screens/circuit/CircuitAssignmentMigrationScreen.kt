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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.ui.components.RecordFound
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.SimpleDropdown
import org.koin.compose.viewmodel.koinViewModel

/**
 * Administration → Circuit Assignment. The update process for congregations created before the Circuit Overseer
 * module: pick a Circuit Overseer, tick the congregations that belong to them, assign. Each one is an individual
 * server transaction, so a congregation somebody else just took is refused (and reported) while the rest go through.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircuitAssignmentMigrationScreen(
    currentPersonId: String,
    onBack: () -> Unit,
    viewModel: CircuitAssignmentMigrationViewModel = koinViewModel(),
) {
    val unassigned by viewModel.unassigned.collectAsStateWithLifecycle()
    val overseers by viewModel.overseers.collectAsStateWithLifecycle()
    val showToast = rememberActionToast()
    var overseerId by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val picked = overseers.firstOrNull { it.personId == overseerId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Circuit Assignment") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Congregations created before Circuit Overseers existed need one. Once this list is empty, " +
                        "\"Circuit Overseer Assigned\" becomes required on every congregation.",
                    style = MaterialTheme.typography.bodySmall,
                )
                SimpleDropdown(
                    label = "Assign to Circuit Overseer",
                    selectedLabel = picked?.let { "${it.name} (${it.circuitCode})" }.orEmpty(),
                    options = overseers.map { it.personId to "${it.name} (${it.circuitCode})" },
                    onSelected = { overseerId = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { selected = if (selected.size == unassigned.size) emptySet() else unassigned.map { it.id }.toSet() }) {
                        Text(if (selected.size == unassigned.size && unassigned.isNotEmpty()) "Clear" else "Select All")
                    }
                    Button(
                        enabled = !working && picked != null && selected.isNotEmpty(),
                        onClick = {
                            working = true
                            message = null
                            viewModel.assignMany(selected, picked!!.personId, currentPersonId) { done, problem ->
                                working = false
                                selected = emptySet()
                                if (done > 0) showToast("$done congregation(s) assigned to ${picked.name}.")
                                message = problem
                            }
                        },
                    ) { Text(if (working) "Assigning…" else "Assign Selected (${selected.size})") }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { RecordFound(unassigned.size) }
                if (unassigned.isEmpty()) item { Text("Every congregation has a Circuit Overseer.", style = MaterialTheme.typography.bodyMedium) }
                items(unassigned, key = { it.id }) { congregation ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(
                            checked = congregation.id in selected,
                            onCheckedChange = { selected = if (it) selected + congregation.id else selected - congregation.id },
                        )
                        Column {
                            Text(congregation.name, style = MaterialTheme.typography.titleSmall)
                            Text(congregation.address, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
