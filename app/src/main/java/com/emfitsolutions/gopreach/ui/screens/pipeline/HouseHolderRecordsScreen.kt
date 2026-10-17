package com.emfitsolutions.gopreach.ui.screens.pipeline

import androidx.compose.foundation.clickable
import com.emfitsolutions.gopreach.ui.components.HideInFullScreen
import com.emfitsolutions.gopreach.ui.components.RecordViewToggle
import com.emfitsolutions.gopreach.ui.components.UniversalColumn
import com.emfitsolutions.gopreach.ui.components.recordItems
import com.emfitsolutions.gopreach.ui.components.rememberRecordViewMode
import com.emfitsolutions.gopreach.ui.components.RecordFound
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Search
import com.emfitsolutions.gopreach.ui.components.AlertDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.emfitsolutions.gopreach.ui.components.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
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
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.ui.components.formatRecordTimestamp

private fun PipelineStage.label(): String = when (this) {
    PipelineStage.SEARCHING -> "Search Record"
    PipelineStage.RETURN_VISIT -> "Return Visit"
    PipelineStage.BIBLE_STUDY -> "Bible Study"
}

/** One dropdown of the filter bar: [allLabel] clears the choice, [options] are (value, label) pairs. */
@Composable
private fun FilterDropdown(
    label: String,
    allLabel: String,
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val shown = options.firstOrNull { it.first == selected }?.second ?: allLabel
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(shown, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(allLabel) }, onClick = { open = false; onSelect(null) })
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(value) })
            }
        }
    }
}

/**
 * "House Holder Records" — every Search Record / Return Visit / Bible Study (an [InterestedPerson]) inside the signed-in
 * role's scope. [congregationIds] is that scope: null = every congregation (Super-Admin, or a Circuit Overseer with all
 * congregations), otherwise exactly the congregations the role may see (Admin: its own; Circuit Overseer: the assigned
 * ones). [groupId] further narrows a Regular Elder to its own Group's publishers.
 *
 * The filter bar narrows by FS Group Territory (the group the record's publisher belongs to, see [HouseHolderRecordsViewModel]), then by
 * Municipality/City and Barangay; the tabs pick the record type. Read-only: no add/edit/delete or stage change from here,
 * since the viewer is typically not the record's own publisher.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseHolderRecordsScreen(
    congregationIds: Set<String>?,
    groupId: String?,
    onBack: () -> Unit,
    viewModel: HouseHolderRecordsViewModel = koinViewModel(),
) {
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()
    val allGroups by viewModel.groups.collectAsStateWithLifecycle()
    val personNames by viewModel.personNames.collectAsStateWithLifecycle()
    val scopedCongregations = remember(congregations, congregationIds) {
        congregations.filter { congregationIds == null || it.id in congregationIds }
    }
    val congregationNames = remember(congregations) { congregations.associate { it.id to it.name } }
    val multiCongregation = scopedCongregations.size > 1
    val scopeLabel = when {
        congregationIds == null -> "All congregations"
        else -> scopedCongregations.joinToString(", ") { it.name }.ifBlank { "—" }
    } + if (groupId != null) " (own Group only)" else ""

    var stage by remember { mutableStateOf(PipelineStage.SEARCHING) }
    var filter by remember { mutableStateOf(HouseHolderFilter()) }
    val state by remember(stage, congregationIds, groupId, filter) { viewModel.records(stage, congregationIds, groupId, filter) }
        .collectAsStateWithLifecycle(initialValue = HouseHolderRecordsState())
    var showInactive by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selectedPerson by remember { mutableStateOf<InterestedPerson?>(null) }

    val groupOptions = remember(allGroups, scopedCongregations, filter.congregationId) {
        val ids = scopedCongregations.map { it.id }.toSet()
        allGroups
            .filter { it.congregationId in ids && (filter.congregationId == null || it.congregationId == filter.congregationId) }
            .map { it.id to (if (multiCongregation) "${it.name} · ${congregationNames[it.congregationId].orEmpty()}" else it.name) }
    }

    val people = remember(state.records, showInactive, query) {
        state.records
            .filter { showInactive || it.status == RecordStatus.ACTIVE }
            .filter { p -> query.isBlank() || p.name.contains(query, ignoreCase = true) || p.address.contains(query, ignoreCase = true) }
            .sortedBy { it.name }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("House Holder Records") },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                )
                ScrollableTabRow(selectedTabIndex = PipelineStage.entries.indexOf(stage)) {
                    PipelineStage.entries.forEach { s ->
                        Tab(selected = stage == s, onClick = { stage = s }, text = { Text(s.label()) })
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            HideInFullScreen(hideWhileTyping = true) {
            Text(
                "Scope: $scopeLabel",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (multiCongregation) {
                    FilterDropdown(
                        label = "Congregation",
                        allLabel = "All Congregations",
                        options = scopedCongregations.map { it.id to it.name },
                        selected = filter.congregationId,
                        onSelect = { filter = HouseHolderFilter(congregationId = it) },
                    )
                }
                FilterDropdown(
                    label = "FS Group Territory",
                    allLabel = "All FS Group",
                    options = groupOptions,
                    selected = filter.fsGroupId,
                    onSelect = { filter = filter.copy(fsGroupId = it) },
                )
                FilterDropdown(
                    label = "Province",
                    allLabel = "All Provinces",
                    options = state.provinces.map { it to it },
                    selected = filter.province?.let { p -> state.provinces.firstOrNull { it.equals(p, ignoreCase = true) } },
                    onSelect = { filter = filter.copy(province = it, city = null, barangay = null) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterDropdown(
                        label = "Municipality / City",
                        allLabel = "All Municipalities / Cities",
                        options = state.cities.map { it to it },
                        selected = filter.city?.let { c -> state.cities.firstOrNull { it.equals(c, ignoreCase = true) } },
                        onSelect = { filter = filter.copy(city = it, barangay = null) },
                        modifier = Modifier.weight(1f),
                    )
                    FilterDropdown(
                        label = "Barangay",
                        allLabel = "All Barangays",
                        options = state.barangays.map { it to it },
                        selected = filter.barangay?.let { b -> state.barangays.firstOrNull { it.equals(b, ignoreCase = true) } },
                        onSelect = { filter = filter.copy(barangay = it) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            }
            HideInFullScreen {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search by name or place of origin") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            androidx.compose.material3.FilterChip(selected = showInactive, onClick = { showInactive = !showInactive }, label = { Text("Include inactive") }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            if (people.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    RecordFound(0)
                    Text("No ${stage.label()} records found in this scope.", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                val viewMode = rememberRecordViewMode("elder_interested")
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { RecordFound(people.size) }
                    item { RecordViewToggle(viewMode) }
                    recordItems(
                        viewMode, people, { it.id },
                        listOf(
                                UniversalColumn("Name", 200.dp) { it.name },
                                UniversalColumn("Publisher", 180.dp) { personNames[it.publisherPersonId] ?: "Unassigned" },
                                UniversalColumn("Address", 260.dp) { it.address },
                                UniversalColumn("Status", 90.dp) { if (it.status == RecordStatus.INACTIVE) "Inactive" else "Active" },
                        ),
                        onRowClick = { selectedPerson = it },
                    ) { person ->
                        val publisherName by remember(person.publisherPersonId) { viewModel.personName(person.publisherPersonId) }.collectAsStateWithLifecycle(initialValue = null)
                        Card(modifier = Modifier.fillMaxWidth().clickable { selectedPerson = person }) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(person.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    publisherName?.let { "Publisher: $it" } ?: "Unassigned",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(person.address, style = MaterialTheme.typography.bodySmall)
                                if (person.status == RecordStatus.INACTIVE) {
                                    Text("Inactive", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                item(key = "end-summary") {
                    com.emfitsolutions.gopreach.ui.components.EndSummary(
                        listOf(
                            "Total Records" to people.size.toString(),
                            "Search Records" to people.count { it.pipelineStage == com.emfitsolutions.gopreach.data.model.PipelineStage.SEARCHING }.toString(),
                            "Return Visits" to people.count { it.pipelineStage == com.emfitsolutions.gopreach.data.model.PipelineStage.RETURN_VISIT }.toString(),
                            "Bible Studies" to people.count { it.pipelineStage == com.emfitsolutions.gopreach.data.model.PipelineStage.BIBLE_STUDY }.toString(),
                        ),
                        Modifier.padding(top = 8.dp),
                    )
                }
                }
            }
        }
    }

    val current = selectedPerson
    if (current != null) {
        val publisherName by remember(current.publisherPersonId) { viewModel.personName(current.publisherPersonId) }.collectAsStateWithLifecycle(initialValue = null)
        InterestedPersonReadOnlyDialog(person = current, publisherName = publisherName, onDismiss = { selectedPerson = null })
    }
}

/** Every field [InterestedPerson] carries, plain read-only text — no action
 * buttons, per this screen's own "see, don't manage" scope note above. */
@Composable
private fun InterestedPersonReadOnlyDialog(person: InterestedPerson, publisherName: String?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(person.name) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ReadOnlyRow("Publisher", publisherName ?: "Unassigned")
                ReadOnlyRow("Place of Origin", person.address)
                if (!person.province.isNullOrBlank() || !person.cityMunicipality.isNullOrBlank() || !person.barangay.isNullOrBlank()) {
                    ReadOnlyRow("Current Address", listOfNotNull(person.barangay, person.cityMunicipality, person.province).joinToString(", "))
                }
                person.gender?.let { ReadOnlyRow("Gender", it.name) }
                person.ageYears?.let { ReadOnlyRow("Age", it.toString()) }
                person.spouse?.let { if (it.isNotBlank()) ReadOnlyRow("Spouse", it) }
                person.children?.let { if (it.isNotBlank()) ReadOnlyRow("Children", it) }
                person.placeOrigin?.let { if (it.isNotBlank() && it != person.address) ReadOnlyRow("Previously Recorded Place Origin", it) }
                person.language?.let { if (it.isNotBlank()) ReadOnlyRow("Language", it) }
                person.religion?.let { if (it.isNotBlank()) ReadOnlyRow("Religion", it) }
                person.literaturePlace?.let { if (it.isNotBlank()) ReadOnlyRow("Literature Left At", it) }
                person.contact?.let { if (it.isNotBlank()) ReadOnlyRow("Contact", it) }
                if (person.hasGpsLocation) {
                    com.emfitsolutions.gopreach.ui.components.RecordLocationActions(lat = person.gpsLat!!, lng = person.gpsLng!!, name = person.name)
                }
                ReadOnlyRow("Stage", person.pipelineStage.label())
                ReadOnlyRow("Status", person.status.name)
                ReadOnlyRow("Date Added", formatRecordTimestamp(person.createdAt))
                person.remarks?.let { if (it.isNotBlank()) ReadOnlyRow("Remarks", it) }
                person.notes?.let { if (it.isNotBlank()) ReadOnlyRow("Notes", it) }
            }
        },
    )
}

@Composable
private fun ReadOnlyRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
