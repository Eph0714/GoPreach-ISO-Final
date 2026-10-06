package com.emfitsolutions.gopreach.ui.screens.pipeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.PublisherVisibilitySettings
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.ui.components.CongregationFilterDropdown
import com.emfitsolutions.gopreach.ui.components.RecordFound
import com.emfitsolutions.gopreach.ui.components.SelectCongregationPrompt
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.components.rememberCongregationContext

private fun PipelineStage.assignmentLabel(): String = when (this) {
    PipelineStage.SEARCHING -> "Searching"
    PipelineStage.RETURN_VISIT -> "Return Visit"
    PipelineStage.BIBLE_STUDY -> "Bible Study"
}

private enum class AssignmentFilter(val label: String) {
    ALL("All"), ASSIGNED("Assigned"), UNASSIGNED("Unassigned"), BY_PUBLISHER("By Publisher"), BY_STATUS("By Status")
}

/**
 * "Publisher Assignment" — the Admin's central place to manage Searching, Return Visit and Bible Study records and who
 * they are assigned to. It is a management/assignment layer over the existing records: it reuses
 * [PipelinePersonDialog] (add/edit) and [PipelinePersonDetailScreen] (visit history), and only changes
 * [InterestedPerson.publisherPersonId] when assigning — assignment never touches the record itself, and unassigned
 * (blank) is a normal state. [fixedCongregationId] is the access boundary; null (Super-Admin) must pick a Congregation
 * first and keeps it while adding/editing/assigning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublisherAssignmentScreen(
    fixedCongregationId: String?,
    currentPersonId: String,
    onSearchCoordinates: () -> Unit,
    onBack: () -> Unit,
    viewModel: PipelineViewModel = hiltViewModel(),
) {
    var stageIndex by rememberSaveable { mutableStateOf(0) }
    val stage = PipelineStage.entries[stageIndex]
    var selectedPersonId by rememberSaveable { mutableStateOf(PublisherAssignmentPreset.take()) }
    var congregationFilter by rememberCongregationContext("publisher_assignment")
    val congregationId = fixedCongregationId ?: congregationFilter
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()
    val showToast = rememberActionToast()
    LaunchedEffect(Unit) { viewModel.errorEvents.collect { showToast(it) } }

    // Open record (visit history, GPS, ...) — same detail screen the publisher side uses, with admin rights.
    val openPerson by remember(selectedPersonId) { viewModel.observePerson(selectedPersonId.orEmpty()) }.collectAsStateWithLifecycle(initialValue = null)
    val opened = openPerson
    // Access check: a record outside this Admin's congregation scope is never opened, whatever route led here.
    if (selectedPersonId != null && opened != null && fixedCongregationId != null && opened.congregationId != fixedCongregationId) {
        LaunchedEffect(selectedPersonId) { selectedPersonId = null }
    } else if (selectedPersonId != null && opened != null) {
        val congregationName by remember(opened.congregationId) { viewModel.congregationName(opened.congregationId) }.collectAsStateWithLifecycle(initialValue = null)
        PipelinePersonDetailScreen(
            person = opened,
            currentPersonId = currentPersonId,
            congregationName = congregationName ?: "—",
            stage = opened.pipelineStage,
            canManageAllVisitHistory = true,
            onBack = { selectedPersonId = null },
            viewModel = viewModel,
        )
        return
    }

    if (congregationId == null) {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text("Publisher Assignment") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        }) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                CongregationFilterDropdown(
                    congregations = congregations,
                    selectedCongregationId = congregationFilter,
                    onSelected = { congregationFilter = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                SelectCongregationPrompt()
            }
        }
        return
    }

    val recordsFlow = remember(stage, congregationId) { viewModel.recordsInCongregation(stage, congregationId) }
    val records by recordsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val allFlow = remember(congregationId) { viewModel.allRecordsInCongregation(congregationId) }
    val allRecords by allFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val publishersFlow = remember(congregationId) { viewModel.publishersFor(congregationId) }
    val publishers by publishersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val names by viewModel.personNames.collectAsStateWithLifecycle(initialValue = emptyMap())
    val settingsFlow = remember(congregationId) { viewModel.visibilitySettingsFor(congregationId) }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = PublisherVisibilitySettings.defaultsFor(congregationId))
    val congregationName = congregations.firstOrNull { it.id == congregationId }?.name

    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(AssignmentFilter.ALL) }
    var filterPublisherId by rememberSaveable { mutableStateOf<String?>(null) }
    var filterStatus by rememberSaveable { mutableStateOf(RecordStatus.ACTIVE) }
    var showCreate by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<InterestedPerson?>(null) }
    var assigning by remember { mutableStateOf<InterestedPerson?>(null) }
    var deleting by remember { mutableStateOf<InterestedPerson?>(null) }
    var showVisibility by remember { mutableStateOf(false) }

    val shown = records.filter { p ->
        val publisherName = names[p.publisherPersonId].orEmpty()
        val passesFilter = when (filter) {
            AssignmentFilter.ALL -> true
            AssignmentFilter.ASSIGNED -> p.publisherPersonId.isNotBlank()
            AssignmentFilter.UNASSIGNED -> p.publisherPersonId.isBlank()
            AssignmentFilter.BY_PUBLISHER -> filterPublisherId == null || p.publisherPersonId == filterPublisherId
            AssignmentFilter.BY_STATUS -> p.status == filterStatus
        }
        passesFilter && (query.isBlank() || listOf(p.name, p.address, p.barangay.orEmpty(), p.cityMunicipality.orEmpty(), p.province.orEmpty(), publisherName)
            .any { it.contains(query, ignoreCase = true) })
    }.sortedBy { it.name }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(if (congregationName != null) "Publisher Assignment – $congregationName" else "Publisher Assignment") },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                    actions = {
                        IconButton(onClick = onSearchCoordinates) { Icon(Icons.Rounded.MyLocation, contentDescription = "Search Coordinates") }
                        IconButton(onClick = { showVisibility = true }) { Icon(Icons.Rounded.Visibility, contentDescription = "Publisher visibility settings") }
                    },
                )
                ScrollableTabRow(selectedTabIndex = stageIndex) {
                    PipelineStage.entries.forEachIndexed { index, s ->
                        val count = allRecords.count { it.pipelineStage == s }
                        Tab(selected = stageIndex == index, onClick = { stageIndex = index }, text = { Text("${s.assignmentLabel()} ($count)") })
                    }
                }
            }
        },
        floatingActionButton = {
            // Bible Studies are viewed and (re)assigned here, not created or edited.
            if (stage != PipelineStage.BIBLE_STUDY) {
                FloatingActionButton(onClick = { showCreate = true }) { Icon(Icons.Rounded.Add, contentDescription = "Add ${stage.assignmentLabel()} Record") }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (fixedCongregationId == null) {
                CongregationFilterDropdown(
                    congregations = congregations,
                    selectedCongregationId = congregationFilter,
                    onSelected = { congregationFilter = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search name, location, barangay, municipality or publisher") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssignmentFilter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
            }
            if (filter == AssignmentFilter.BY_PUBLISHER) {
                SimpleDropdown(
                    label = "Publisher",
                    selectedText = publishers.firstOrNull { it.id == filterPublisherId }?.fullName ?: "All publishers",
                    options = listOf<Pair<String?, String>>(null to "All publishers") + publishers.map { it.id to it.fullName },
                    onSelected = { filterPublisherId = it },
                )
            }
            if (filter == AssignmentFilter.BY_STATUS) {
                SimpleDropdown(
                    label = "Status",
                    selectedText = if (filterStatus == RecordStatus.ACTIVE) "Active" else "Inactive",
                    options = listOf(RecordStatus.ACTIVE to "Active", RecordStatus.INACTIVE to "Inactive"),
                    onSelected = { filterStatus = it },
                )
            }
            val assignedCount = records.count { it.publisherPersonId.isNotBlank() }
            Text(
                "${stage.assignmentLabel()} Records (${records.size})  ·  Assigned: $assignedCount  ·  Unassigned: ${records.size - assignedCount}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            if (shown.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    RecordFound(0)
                    Text("No ${stage.assignmentLabel()} records found.", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { RecordFound(shown.size) }
                    items(shown, key = { it.id }) { person ->
                        Card(modifier = Modifier.fillMaxWidth().clickable { selectedPersonId = person.id }) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(person.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "Assigned Publisher: " + if (person.publisherPersonId.isBlank()) "Unassigned" else (names[person.publisherPersonId] ?: "—"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (person.publisherPersonId.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    )
                                    val location = listOfNotNull(person.barangay, person.cityMunicipality, person.province).filter { it.isNotBlank() }
                                        .ifEmpty { listOf(person.address) }.joinToString(", ")
                                    if (location.isNotBlank()) Text("Location: $location", style = MaterialTheme.typography.bodySmall)
                                    if (person.gpsLat != null && person.gpsLng != null) {
                                        Text("Coordinates: %.5f, %.5f".format(person.gpsLat, person.gpsLng), style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(
                                        "Status: " + if (person.status == RecordStatus.ACTIVE) "Active" else "Inactive",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { assigning = person }) {
                                    Icon(Icons.Rounded.PersonAdd, contentDescription = if (person.publisherPersonId.isBlank()) "Assign Publisher" else "Reassign Publisher")
                                }
                                if (stage != PipelineStage.BIBLE_STUDY) {
                                    IconButton(onClick = { editing = person }) { Icon(Icons.Rounded.Edit, contentDescription = "Edit") }
                                    IconButton(onClick = { deleting = person }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        PipelinePersonDialog(
            existingPerson = null,
            publisherPersonId = "",
            congregationId = congregationId,
            currentPersonId = currentPersonId,
            stage = stage,
            onSave = { viewModel.saveEnrolledRecord(it, currentPersonId); showToast("${stage.assignmentLabel()} record added.") },
            onDismiss = { showCreate = false },
            viewModel = viewModel,
            assignablePublishers = publishers,
        )
    }

    editing?.let { person ->
        PipelinePersonDialog(
            existingPerson = person,
            publisherPersonId = person.publisherPersonId,
            congregationId = person.congregationId,
            currentPersonId = currentPersonId,
            stage = person.pipelineStage,
            onSave = { viewModel.save(it); showToast("Record saved.") },
            onDismiss = { editing = null },
            viewModel = viewModel,
        )
    }

    assigning?.let { person ->
        AssignPublisherDialog(
            person = person,
            publishers = publishers,
            onConfirm = { newId ->
                viewModel.assignPublisher(person, newId, currentPersonId)
                showToast(if (newId.isBlank()) "\"${person.name}\" is now unassigned." else "\"${person.name}\" assigned.")
                assigning = null
            },
            onDismiss = { assigning = null },
        )
    }

    deleting?.let { person ->
        val assignedName = if (person.publisherPersonId.isBlank()) null else (names[person.publisherPersonId] ?: "—")
        val label = person.pipelineStage.assignmentLabel()
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { deleting = null },
            title = { Text(if (assignedName != null) "⚠️ WARNING: This record is currently assigned to a Publisher." else "Delete $label Record?") },
            text = {
                Text(
                    if (assignedName != null) {
                        "Deleting this record will permanently remove the $label record and its related record history according to the system's deletion rules.\n\n" +
                            "Assigned Publisher: $assignedName\n\nAre you sure you want to delete this record?"
                    } else {
                        "This record is currently unassigned. Deleting it will remove the record from the system.\n\nAre you sure you want to continue?"
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.permanentlyDelete(person, currentPersonId)
                        showToast("\"${person.name}\" deleted.")
                        deleting = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Yes, Delete Record") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    if (showVisibility) {
        VisibilitySettingsDialog(
            settings = settings,
            onSave = { viewModel.saveVisibilitySettings(it, currentPersonId); showToast("Visibility settings saved."); showVisibility = false },
            onDismiss = { showVisibility = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssignPublisherDialog(person: InterestedPerson, publishers: List<Person>, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var selectedId by remember { mutableStateOf(person.publisherPersonId) }
    AlertDialog(
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
        onDismissRequest = onDismiss,
        title = { Text(if (person.publisherPersonId.isBlank()) "Assign Publisher" else "Reassign Publisher") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Only the assignment of \"${person.name}\" changes — the record and its history stay as they are.", style = MaterialTheme.typography.bodySmall)
                SimpleDropdown(
                    label = "Assigned Publisher",
                    selectedText = publishers.firstOrNull { it.id == selectedId }?.fullName ?: "Unassigned",
                    options = listOf("" to "Unassigned") + publishers.map { it.id to it.fullName },
                    onSelected = { selectedId = it },
                )
                publishers.firstOrNull { it.id == selectedId }?.remarks?.trim()?.takeIf { it.isNotEmpty() }?.let { Text("Remarks: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (publishers.isEmpty()) Text("No eligible publishers in this congregation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { Button(onClick = { onConfirm(selectedId) }, enabled = selectedId != person.publisherPersonId) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun VisibilitySettingsDialog(settings: PublisherVisibilitySettings, onSave: (PublisherVisibilitySettings) -> Unit, onDismiss: () -> Unit) {
    var searching by remember { mutableStateOf(settings.showOthersSearching) }
    var returnVisit by remember { mutableStateOf(settings.showOthersReturnVisit) }
    var bibleStudy by remember { mutableStateOf(settings.showOthersBibleStudy) }
    var followUpText by remember { mutableStateOf(settings.followUpValue.toString()) }
    var followUpUnit by remember { mutableStateOf(runCatching { com.emfitsolutions.gopreach.data.model.FollowUpUnit.valueOf(settings.followUpUnit) }.getOrDefault(com.emfitsolutions.gopreach.data.model.FollowUpUnit.DAYS)) }
    val followUpValue = followUpText.toIntOrNull()?.takeIf { it > 0 }
    AlertDialog(
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
        onDismissRequest = onDismiss,
        title = { Text("Publisher visibility and follow-up") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Unassigned Searching and Return Visit records are always visible to every Publisher.", style = MaterialTheme.typography.bodySmall)
                VisibilityRow("Allow other Publishers to see other Publishers' Search Records", searching) { searching = it }
                VisibilityRow("Allow other Publishers to see other Publishers' Return Visit Records", returnVisit) { returnVisit = it }
                VisibilityRow("Allow other Publishers to see other Publishers' Bible Study Records (view only — only the assigned Publisher can add a visit)", bibleStudy) { bibleStudy = it }
                Text("Follow-up Needed Settings", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Follow-up Needed After", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        value = followUpText,
                        onValueChange = { followUpText = it.filter(Char::isDigit).take(3) },
                        singleLine = true,
                        isError = followUpValue == null,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.width(76.dp),
                    )
                }
                SimpleDropdown(
                    label = "Unit",
                    selectedText = followUpUnit.label,
                    options = com.emfitsolutions.gopreach.data.model.FollowUpUnit.entries.map { it to it.label },
                    onSelected = { followUpUnit = it },
                )
                if (followUpValue == null) Text("Enter a number greater than zero.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                enabled = followUpValue != null,
                onClick = { onSave(settings.copy(showOthersSearching = searching, showOthersReturnVisit = returnVisit, showOthersBibleStudy = bibleStudy, followUpValue = followUpValue ?: 7, followUpUnit = followUpUnit.name)) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun VisibilityRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(if (checked) "YES" else "NO", style = MaterialTheme.typography.labelLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SimpleDropdown(label: String, selectedText: String, options: List<Pair<T, String>>, onSelected: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onSelected(value); expanded = false }) }
        }
    }
}
