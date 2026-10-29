package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.ui.components.LanguagesTagInput
import com.emfitsolutions.gopreach.ui.components.PhilippineAddressPicker
import com.emfitsolutions.gopreach.ui.components.ReadOnlyField
import com.emfitsolutions.gopreach.ui.components.formatRecordTimestamp
import org.koin.compose.viewmodel.koinViewModel

private enum class CoSort(val label: String) { NAME_ASC("Name A-Z"), NAME_DESC("Name Z-A"), NEWEST("Newest"), OLDEST("Oldest") }

/**
 * Congregation Management (Circuit Overseer): the congregations enrolled under the signed-in account, with search, sorting, refresh, details and edit.
 * Adding one opens the same enrolment form the Super Admin uses, with the overseer fixed to this account.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoCongregationsScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    viewModel: CoCongregationsViewModel = koinViewModel(),
) {
    val all by viewModel.mine.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val myName by viewModel.myName.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(CoSort.NAME_ASC) }
    var viewing by remember { mutableStateOf<Congregation?>(null) }
    var editing by remember { mutableStateOf<Congregation?>(null) }

    val q = query.trim().lowercase()
    val shown = all
        .filter { q.isEmpty() || listOf(it.name, it.code, it.province.orEmpty(), it.cityMunicipality.orEmpty(), it.barangay.orEmpty()).any { v -> v.lowercase().contains(q) } }
        .let { list ->
            when (sort) {
                CoSort.NAME_ASC -> list.sortedBy { it.name.lowercase() }
                CoSort.NAME_DESC -> list.sortedByDescending { it.name.lowercase() }
                CoSort.NEWEST -> list.sortedByDescending { it.createdAt }
                CoSort.OLDEST -> list.sortedBy { it.createdAt }
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Congregation Management") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (refreshing) CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp).height(22.dp).widthIn(max = 22.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = viewModel::refresh) { Icon(Icons.Rounded.Refresh, contentDescription = "Refresh") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (all.isEmpty()) {
                EmptyState(onAdd)
                return@Column
            }
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Add Congregation")
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search by name, code or place") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear the search") } },
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CoSort.entries.forEach { s -> FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.label, maxLines = 1) }) }
            }
            Text("${shown.size} of ${all.size} congregation${if (all.size == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (shown.isEmpty()) {
                Text("No congregation matches your search.", modifier = Modifier.padding(top = 24.dp).fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(shown, key = { it.id }) { c -> CongregationCard(c, onView = { viewing = c }, onEdit = { editing = c }) }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    viewing?.let { c -> DetailsDialog(c, assignedTo = myName, onClose = { viewing = null }, onEdit = { viewing = null; editing = c }) }
    editing?.let { c -> EditDialog(c, viewModel, onClose = { editing = null }) }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.AccountBalance, contentDescription = null, modifier = Modifier.height(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("No congregations have been enrolled under your account yet.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAdd) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text("Add Congregation")
        }
    }
}

@Composable
private fun CongregationCard(c: Congregation, onView: () -> Unit, onEdit: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), modifier = Modifier.fillMaxWidth().clickable(onClick = onView)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                StatusTag(c.status)
            }
            Text(listOfNotNull(c.barangay, c.cityMunicipality, c.province).filter { it.isNotBlank() }.joinToString(", ").ifBlank { c.address.ifBlank { "No location recorded" } }, style = MaterialTheme.typography.bodyMedium)
            Text("Code ${c.code.ifBlank { "-" }}" + if (c.createdAt > 0) "  -  Enrolled ${formatRecordTimestamp(c.createdAt)}" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = onView) { Text("Details") }
                if (c.status == RecordStatus.ACTIVE) OutlinedButton(onClick = onEdit) { Text("Edit") }
            }
        }
    }
}

@Composable
private fun StatusTag(status: RecordStatus) {
    val active = status == RecordStatus.ACTIVE
    Surface(shape = RoundedCornerShape(50), color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.error.copy(alpha = 0.14f)) {
        Text(if (active) "Active" else "Inactive", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp), color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun DetailsDialog(c: Congregation, assignedTo: String, onClose: () -> Unit, onEdit: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(c.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadOnlyField("Congregation Code", c.code.ifBlank { "-" })
                ReadOnlyField("Province / City", c.province.orEmpty().ifBlank { "-" })
                ReadOnlyField("Municipality / City", c.cityMunicipality.orEmpty().ifBlank { "-" })
                ReadOnlyField("Barangay", c.barangay.orEmpty().ifBlank { "-" })
                ReadOnlyField("Languages", c.languages.joinToString(", ").ifBlank { "-" })
                ReadOnlyField("Status", if (c.status == RecordStatus.ACTIVE) "Active" else "Inactive")
                ReadOnlyField("Enrollment date", if (c.createdAt > 0) formatRecordTimestamp(c.createdAt) else "-")
                ReadOnlyField("Assigned Circuit Overseer", assignedTo.ifBlank { "You" })
            }
        },
        confirmButton = { if (c.status == RecordStatus.ACTIVE) TextButton(onClick = onEdit) { Text("Edit") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun EditDialog(c: Congregation, viewModel: CoCongregationsViewModel, onClose: () -> Unit) {
    var name by remember(c.id) { mutableStateOf(c.name) }
    var province by remember(c.id) { mutableStateOf(c.province) }
    var city by remember(c.id) { mutableStateOf(c.cityMunicipality) }
    var barangay by remember(c.id) { mutableStateOf(c.barangay) }
    var languages by remember(c.id) { mutableStateOf(c.languages) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!saving) onClose() },
        title = { Text("Edit Congregation") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.uppercase(); error = null }, label = { Text("Congregation Name") }, singleLine = true, visualTransformation = VisualTransformation.None, modifier = Modifier.fillMaxWidth())
                PhilippineAddressPicker(province = province, cityMunicipality = city, barangay = barangay, onChanged = { p, m, b -> province = p; city = m; barangay = b; error = null }, modifier = Modifier.fillMaxWidth())
                // the code and the assigned overseer are managed by the Super Admin, so they are shown and not editable
                ReadOnlyField("Congregation Code", c.code.ifBlank { "-" })
                LanguagesTagInput(languages = languages, onAdd = { languages = languages + it }, onRemove = { languages = languages - it }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving,
                onClick = {
                    if (name.isBlank() || province.isNullOrBlank() || city.isNullOrBlank() || barangay.isNullOrBlank()) { error = "Name, Province, Municipality/City and Barangay are required."; return@Button }
                    saving = true
                    viewModel.edit(c, name, province.orEmpty(), city.orEmpty(), barangay.orEmpty(), languages) { problem -> saving = false; if (problem == null) onClose() else error = problem }
                },
            ) {
                if (saving) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).height(18.dp).widthIn(max = 18.dp), strokeWidth = 2.dp)
                Text("Save")
            }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onClose) { Text("Cancel") } },
    )
}
