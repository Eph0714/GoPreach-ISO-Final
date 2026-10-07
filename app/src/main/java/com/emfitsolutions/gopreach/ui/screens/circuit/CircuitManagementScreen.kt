package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.displayName
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.SimpleDropdown
import org.koin.compose.viewmodel.koinViewModel

/**
 * Circuit Overseer Management (Super-Admin). One place to see every circuit: the system-wide numbers, a circuit picker
 * ("All Circuits" or one overseer), Quick Access counts for that choice (optionally narrowed to one congregation), and
 * buttons that open the very same Circuit screens a Circuit Overseer uses — people, territory, reports, history —
 * pointed at the chosen circuit. Switching overseers never leaves this module.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircuitManagementScreen(
    onBack: () -> Unit,
    onOpenCongregations: () -> Unit,
    onOpenPublishers: (category: String?, congregationId: String?) -> Unit,
    onOpenLeaders: () -> Unit,
    onOpenTerritory: (congregationId: String?) -> Unit,
    onOpenReports: () -> Unit,
    onOpenCircuitReport: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenAccount: (personId: String) -> Unit,
    viewModel: CircuitManagementViewModel = koinViewModel(),
    people: CircuitPeopleViewModel = koinViewModel(),
) {
    val overview by viewModel.overview.collectAsStateWithLifecycle(initialValue = null)
    val choices by viewModel.choices.collectAsStateWithLifecycle(initialValue = emptyList())
    val scopeId = CircuitScopeStore.selected
    // A circuit whose overseer was since removed falls back to "All Circuits" rather than showing an empty page.
    val choice = choices.firstOrNull { it.scopeId == scopeId } ?: choices.firstOrNull()
    val effectiveScope = choice?.scopeId ?: CIRCUIT_SCOPE_ALL
    var congregationId by remember(effectiveScope) { mutableStateOf<String?>(null) }
    val congregations by remember(effectiveScope) { people.congregations(effectiveScope) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val counts by remember(effectiveScope, congregationId) { people.counts(effectiveScope, congregationId) }
        .collectAsStateWithLifecycle(initialValue = QuickAccessCounts(emptyMap()))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Circuit Overseer Management") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                overview?.let { o ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Tile("Circuit Overseers", o.overseers, Modifier.weight(1f), onOpenAccounts)
                            Tile("Circuits", o.circuits, Modifier.weight(1f))
                            Tile("Congregations", o.congregations, Modifier.weight(1f))
                        }
                    }
                }
            }
            item {
                SimpleDropdown(
                    label = "Circuit / Circuit Overseer",
                    selectedLabel = choice?.label ?: "All Circuits",
                    options = choices.map { it.scopeId to it.label },
                    onSelected = { CircuitScopeStore.selectCircuit(it) },
                )
            }
            item { Text("CO Quick Access", style = MaterialTheme.typography.titleMedium) }
            item {
                SimpleDropdown(
                    label = "Congregation",
                    selectedLabel = congregations.firstOrNull { it.id == congregationId }?.name ?: "All Congregations",
                    options = listOf("" to "All Congregations") + congregations.map { it.id to it.name },
                    onSelected = { congregationId = it.ifEmpty { null } },
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CIRCUIT_QUICK_CATEGORIES.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            pair.forEach { category ->
                                Tile(
                                    category.displayName.lowercase().replaceFirstChar { it.uppercase() } + "s",
                                    counts[category], Modifier.weight(1f),
                                ) { onOpenPublishers(category.name, congregationId) }
                            }
                        }
                    }
                }
            }
            item { Text(choice?.label ?: "All Circuits", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onOpenCongregations, modifier = Modifier.weight(1f)) { Text("Congregations") }
                        OutlinedButton(onClick = { onOpenPublishers(null, congregationId) }, modifier = Modifier.weight(1f)) { Text("Publishers") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onOpenLeaders, modifier = Modifier.weight(1f)) { Text("Elders & MS") }
                        OutlinedButton(onClick = { onOpenTerritory(congregationId) }, modifier = Modifier.weight(1f)) { Text("Territory Maps") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onOpenReports, modifier = Modifier.weight(1f)) { Text("Field Service Reports") }
                    }
                    choice?.overseerPersonId?.let { id ->
                        OutlinedButton(onClick = { onOpenAccount(id) }, modifier = Modifier.fillMaxWidth()) { Text("Account Information") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: Int, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val content: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(value.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
    }
    if (onClick != null) Card(onClick = onClick, modifier = modifier) { content() } else Card(modifier = modifier) { content() }
}
